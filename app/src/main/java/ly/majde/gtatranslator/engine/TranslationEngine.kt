package ly.majde.gtatranslator.engine

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ly.majde.gtatranslator.data.AppSettings
import ly.majde.gtatranslator.data.GeminiClient
import ly.majde.gtatranslator.data.GeminiException
import ly.majde.gtatranslator.data.Part
import ly.majde.gtatranslator.util.TextSimilarity

/**
 * Serializes all Gemini traffic through one worker so the app respects the free-tier
 * rate limit, merges bursts of subtitles into a single request, drops stale live input,
 * and backs off automatically on 429s.
 */
class TranslationEngine(
    private val client: GeminiClient,
    private val settings: StateFlow<AppSettings>,
    scope: CoroutineScope,
) {
    private sealed interface Work {
        val source: Source
        val isLive: Boolean get() = source == Source.CAMERA || source == Source.AUDIO
    }

    private class TextWork(val text: String, override val source: Source) : Work
    private class ImageWork(val jpeg: ByteArray) : Work {
        override val source: Source get() = Source.SNAPSHOT
    }
    private class AudioWork(val wav: ByteArray) : Work {
        override val source: Source get() = Source.AUDIO
    }
    private class DeepWork(val entryId: Long) : Work {
        override val source: Source get() = Source.MANUAL
    }

    private val lock = Any()
    private val pending = ArrayDeque<Work>()
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)
    private val ids = AtomicLong(0)

    @Volatile private var lastRequestAt = 0L
    @Volatile private var cooldownUntil = 0L

    private val _entries = MutableStateFlow<List<TranslationEntry>>(emptyList())
    val entries: StateFlow<List<TranslationEntry>> = _entries.asStateFlow()

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    init {
        scope.launch { runWorker() }
    }

    fun submitText(text: String, source: Source) {
        val t = text.trim()
        if (t.length >= 2) enqueue(TextWork(t, source))
    }

    fun submitImage(jpeg: ByteArray) = enqueue(ImageWork(jpeg))

    fun submitAudio(wav: ByteArray) = enqueue(AudioWork(wav))

    fun explainMore(entryId: Long) {
        val entry = _entries.value.firstOrNull { it.id == entryId } ?: return
        if (entry.deepLoading || entry.deepExplanation != null) return
        updateEntry(entryId) { it.copy(deepLoading = true) }
        enqueue(DeepWork(entryId), front = true)
    }

    fun clearHistory() {
        _entries.value = emptyList()
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null, noticeDetail = null) }
    }

    // ---- Queue -------------------------------------------------------------------------

    private fun enqueue(work: Work, front: Boolean = false) {
        synchronized(lock) {
            if (front) pending.addFirst(work) else pending.addLast(work)
            // Live input must stay live: if we fall behind, drop the oldest live items.
            while (pending.count { it.isLive } > MAX_LIVE_PENDING) {
                pending.removeAt(pending.indexOfFirst { it.isLive })
            }
            publishPending()
        }
        wakeUp.trySend(Unit)
    }

    private fun publishPending() {
        val size = pending.size
        _state.update { it.copy(pending = size) }
    }

    private fun hasPending(): Boolean = synchronized(lock) { pending.isNotEmpty() }

    /** Takes the next unit of work; consecutive camera subtitles are merged into one request. */
    private fun takeBatch(): List<Work> = synchronized(lock) {
        val first = pending.removeFirstOrNull() ?: return emptyList()
        val batch = mutableListOf(first)
        if (first is TextWork && first.source == Source.CAMERA) {
            while (batch.size < MAX_MERGE) {
                val next = pending.firstOrNull()
                if (next is TextWork && next.source == Source.CAMERA) {
                    pending.removeFirst()
                    batch += next
                } else {
                    break
                }
            }
        }
        publishPending()
        batch
    }

    private fun requeueFront(items: List<Work>) {
        if (items.isEmpty()) return
        synchronized(lock) {
            items.asReversed().forEach { pending.addFirst(it) }
            publishPending()
        }
    }

    // ---- Worker ------------------------------------------------------------------------

    private suspend fun runWorker() {
        while (true) {
            wakeUp.receive()
            while (hasPending()) {
                waitForSlot()
                val batch = takeBatch()
                if (batch.isEmpty()) break
                process(batch)
            }
        }
    }

    private suspend fun waitForSlot() {
        while (true) {
            val rpm = settings.value.requestsPerMinute.coerceIn(1, 60)
            val gap = 60_000L / rpm
            val now = SystemClock.elapsedRealtime()
            val readyAt = maxOf(lastRequestAt + gap, cooldownUntil)
            if (readyAt <= now) break
            delay(minOf(readyAt - now, 1_000L))
        }
        lastRequestAt = SystemClock.elapsedRealtime()
        if (cooldownUntil != 0L) {
            cooldownUntil = 0L
            _state.update {
                if (it.notice == Notice.RATE_LIMIT) it.copy(notice = null, cooldownUntil = 0L) else it.copy(cooldownUntil = 0L)
            }
        }
    }

    private suspend fun process(batch: List<Work>) {
        val s = settings.value
        if (s.apiKey.isBlank()) {
            synchronized(lock) {
                pending.clear()
                publishPending()
            }
            batch.filterIsInstance<DeepWork>().forEach { w -> updateEntry(w.entryId) { it.copy(deepLoading = false) } }
            setNotice(Notice.NO_KEY)
            return
        }
        _state.update { it.copy(busy = true) }
        try {
            val first = batch.first()
            if (first is DeepWork) {
                runDeep(first, s)
                clearTransientNotice()
            } else {
                val found = runTranslate(batch, s)
                if (!found && (first.source == Source.MANUAL || first.source == Source.SNAPSHOT)) {
                    setNotice(Notice.NOTHING_FOUND)
                } else {
                    clearTransientNotice()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GeminiException) {
            handleError(e, batch)
        } catch (e: Exception) {
            handleError(GeminiException(GeminiException.Kind.NETWORK, e.message ?: e.javaClass.simpleName), batch)
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    private suspend fun runTranslate(batch: List<Work>, s: AppSettings): Boolean {
        val parts = buildParts(batch, recentContext())
        val raw = client.generate(
            apiKey = s.apiKey,
            model = s.model,
            systemPrompt = Prompts.system(s.style, s.detail),
            parts = parts,
            responseSchema = Prompts.responseSchema(),
        )
        val items = Prompts.parseItems(raw)
        val source = batch.first().source
        val now = System.currentTimeMillis()
        val recent = _entries.value.take(DEDUPE_WINDOW).map { TextSimilarity.normalize(it.original) }
        val fresh = items
            .filter { it.translation.isNotBlank() }
            .filter { item ->
                if (!batch.first().isLive) return@filter true
                val norm = TextSimilarity.normalize(item.original)
                recent.none { TextSimilarity.similarity(it, norm) >= DUPLICATE_SIMILARITY }
            }
            .map { item ->
                TranslationEntry(
                    id = ids.incrementAndGet(),
                    source = source,
                    original = item.original,
                    speaker = item.speaker,
                    translation = item.translation,
                    explanation = item.explanation,
                    slang = item.slang,
                    kind = item.kind,
                    timestamp = now,
                )
            }
        if (fresh.isNotEmpty()) {
            _entries.update { (fresh.asReversed() + it).take(MAX_HISTORY) }
        }
        return fresh.isNotEmpty()
    }

    private suspend fun runDeep(work: DeepWork, s: AppSettings) {
        val entry = _entries.value.firstOrNull { it.id == work.entryId } ?: return
        val text = client.generate(
            apiKey = s.apiKey,
            model = s.model,
            systemPrompt = Prompts.deepSystem(s.style),
            parts = listOf(Part.Text(Prompts.deepUser(entry))),
            responseSchema = null,
        )
        updateEntry(work.entryId) { it.copy(deepExplanation = text.trim(), deepLoading = false) }
    }

    private fun buildParts(batch: List<Work>, context: List<String>): List<Part> {
        val contextBlock = if (context.isEmpty()) "" else buildString {
            append("Previous lines, for context only (do NOT output them again):\n")
            context.forEach { append("- ").append(it).append('\n') }
            append('\n')
        }
        return when (val first = batch.first()) {
            is TextWork -> {
                val sourceLabel = if (first.source == Source.MANUAL) {
                    "text typed by the player (a line or word they saw or heard in the game)"
                } else {
                    "OCR of on-screen subtitles, may contain OCR errors"
                }
                val body = if (batch.size == 1) {
                    first.text
                } else {
                    batch.mapIndexed { i, w -> "${i + 1}) ${(w as TextWork).text}" }.joinToString("\n")
                }
                listOf(Part.Text("${contextBlock}Source: $sourceLabel.\nNEW TEXT:\n<<<\n$body\n>>>"))
            }
            is ImageWork -> listOf(
                Part.Text(
                    "${contextBlock}Source: a photo of the TV screen taken with the phone camera. " +
                        "Read the subtitles or dialogue text (usually bottom center) and any mission objective text."
                ),
                Part.Inline("image/jpeg", first.jpeg),
            )
            is AudioWork -> listOf(
                Part.Text(
                    "${contextBlock}Source: audio captured by the phone microphone next to the TV. " +
                        "Transcribe ONLY clearly intelligible spoken dialogue (ignore music, songs and sound effects), " +
                        "then output one item per spoken line."
                ),
                Part.Inline("audio/wav", first.wav),
            )
            is DeepWork -> emptyList()
        }
    }

    private fun recentContext(): List<String> =
        _entries.value.take(CONTEXT_LINES).asReversed().map { it.original }.filter { it.isNotBlank() }

    private fun handleError(e: GeminiException, batch: List<Work>) {
        val notice = e.toNotice()
        when (e.kind) {
            GeminiException.Kind.RATE_LIMIT -> {
                cooldownUntil = SystemClock.elapsedRealtime() + (e.retryAfterMs ?: DEFAULT_COOLDOWN_MS)
                requeueFront(batch.filterNot { it.isLive })
                setNotice(notice, cooldown = cooldownUntil)
                return
            }
            GeminiException.Kind.SERVER, GeminiException.Kind.NETWORK -> {
                cooldownUntil = SystemClock.elapsedRealtime() + RETRY_PAUSE_MS
                requeueFront(batch.filterNot { it.isLive })
            }
            else -> {
                batch.filterIsInstance<DeepWork>().forEach { w -> updateEntry(w.entryId) { it.copy(deepLoading = false) } }
            }
        }
        setNotice(notice, detail = e.message)
    }

    private fun setNotice(notice: Notice, detail: String? = null, cooldown: Long = 0L) {
        _state.update { it.copy(notice = notice, noticeDetail = detail, cooldownUntil = cooldown) }
    }

    /** A successful request means any previous problem is resolved. */
    private fun clearTransientNotice() {
        _state.update { if (it.notice == null) it else it.copy(notice = null, noticeDetail = null) }
    }

    private fun updateEntry(id: Long, transform: (TranslationEntry) -> TranslationEntry) {
        _entries.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    private companion object {
        const val MAX_LIVE_PENDING = 3
        const val MAX_MERGE = 4
        const val MAX_HISTORY = 200
        const val CONTEXT_LINES = 3
        const val DEDUPE_WINDOW = 8
        const val DUPLICATE_SIMILARITY = 0.9
        const val DEFAULT_COOLDOWN_MS = 30_000L
        const val RETRY_PAUSE_MS = 3_000L
    }
}

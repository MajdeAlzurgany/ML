package ly.majde.gtatranslator.engine

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import ly.majde.gtatranslator.ai.AiEvent
import ly.majde.gtatranslator.ai.AiException
import ly.majde.gtatranslator.ai.AiMessage
import ly.majde.gtatranslator.ai.AiRouter
import ly.majde.gtatranslator.ai.AiTask
import ly.majde.gtatranslator.ai.LlmOptions
import ly.majde.gtatranslator.ai.Role
import ly.majde.gtatranslator.data.AppSettings
import ly.majde.gtatranslator.data.DetailLevel
import ly.majde.gtatranslator.translate.OfflineTranslator
import ly.majde.gtatranslator.util.TextSimilarity

/**
 * Live pipeline with a calm, readable output:
 *  1. the line is created instantly and translated on the device (ML Kit, offline);
 *  2. an AI model streams a natural translation + explanation;
 *  3. [LiveLine.shown] is set exactly once: the AI translation as soon as it is complete,
 *     or the instant translation if the AI is slow or unavailable. It never changes after,
 *     so the reader is never disturbed by text that keeps rewriting itself.
 */
class LiveEngine(
    private val router: AiRouter,
    private val offline: OfflineTranslator,
    private val settings: StateFlow<AppSettings>,
    private val scope: CoroutineScope,
    initial: List<LiveLine>,
) {
    private val _lines = MutableStateFlow(
        initial.map { it.copy(shown = it.shown.ifBlank { it.bestTranslation }, arComplete = true) }
    )
    val lines: StateFlow<List<LiveLine>> = _lines.asStateFlow()

    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private val _completed = MutableSharedFlow<LiveLine>(extraBufferCapacity = 8)
    /** Emits each line once its translation is final (used for read-aloud). */
    val completed: SharedFlow<LiveLine> = _completed.asSharedFlow()

    private val ids = AtomicLong(initial.maxOfOrNull { it.id } ?: 0L)
    private val refineSlots = Semaphore(MAX_PARALLEL_REFINES)
    private val transcribeSlots = Semaphore(MAX_PARALLEL_TRANSCRIBE)

    fun submitText(text: String, source: Source) {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.length < 2) return
        if (source.isLive && isRecentDuplicate(clean, source)) return
        val line = LiveLine(id = ids.incrementAndGet(), source = source, original = clean)
        _lines.update { (listOf(line) + it).take(MAX_LINES) }
        startPipeline(line.id, clean, source, rev = 0)
    }

    /**
     * The camera first read only part of a subtitle (e.g. one of two lines) and now sees all of it:
     * update that line in place instead of adding a near-duplicate.
     */
    fun amend(text: String) {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.length < 2) return
        val now = System.currentTimeMillis()
        val target = _lines.value.firstOrNull { it.source == Source.CAMERA && now - it.timestamp < AMEND_WINDOW_MS }
        if (target == null) {
            submitText(clean, Source.CAMERA)
            return
        }
        val newRev = target.rev + 1
        _lines.update { list ->
            list.map {
                if (it.id != target.id) it else it.copy(
                    original = clean,
                    draft = "",
                    translation = "",
                    explanation = "",
                    slang = emptyList(),
                    shown = "",
                    arComplete = false,
                    status = LineStatus.DRAFT,
                    provider = "",
                    rev = newRev,
                )
            }
        }
        startPipeline(target.id, clean, Source.CAMERA, newRev)
    }

    fun submitImage(jpeg: ByteArray) {
        if (!router.hasProvider) {
            setNotice(Notice.NO_KEY)
            return
        }
        scope.launch {
            busy(+1)
            try {
                val s = settings.value
                val context = recentOriginals(before = Long.MAX_VALUE)
                val acc = StringBuilder()
                var provider = ""
                router.stream(
                    task = AiTask.VISION,
                    system = { LinePrompt.system(s.style, s.detail) },
                    messages = { listOf(AiMessage(Role.USER, LinePrompt.userImage(context), imageJpeg = jpeg)) },
                    options = LlmOptions(maxTokens = 1_000),
                ).collect { event ->
                    when (event) {
                        is AiEvent.Started -> provider = event.target.label
                        is AiEvent.Delta -> acc.append(event.text)
                    }
                }
                val raw = acc.toString()
                val items = if (LinePrompt.isSkip(raw)) emptyList() else LinePrompt.parse(raw).filter { it.translation.isNotBlank() }
                if (items.isEmpty()) {
                    setNotice(Notice.NOTHING_FOUND)
                } else {
                    val created = items.map { item ->
                        LiveLine(
                            id = ids.incrementAndGet(),
                            source = Source.SNAPSHOT,
                            original = item.original,
                            speaker = item.speaker,
                            translation = item.translation,
                            explanation = item.explanation,
                            slang = item.slang,
                            status = LineStatus.DONE,
                            provider = provider,
                            shown = item.translation,
                            arComplete = true,
                        )
                    }
                    _lines.update { (created.asReversed() + it).take(MAX_LINES) }
                    created.lastOrNull()?.let { _completed.tryEmit(it) }
                    clearNotice()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                setNotice(e.toNotice(), e.message)
            } catch (e: Exception) {
                setNotice(Notice.NETWORK, e.message)
            } finally {
                busy(-1)
            }
        }
    }

    fun submitAudio(wav: ByteArray) {
        if (!router.hasProvider) {
            setNotice(Notice.NO_KEY)
            return
        }
        scope.launch {
            transcribeSlots.withPermit {
                try {
                    val transcript = router.transcribe(wav)
                    if (transcript != null) submitText(transcript.text, Source.AUDIO)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AiException) {
                    setNotice(e.toNotice(), e.message)
                } catch (e: Exception) {
                    setNotice(Notice.NETWORK, e.message)
                }
            }
        }
    }

    /** Asks the AI again for one line; the user asked for it, so the shown text may change. */
    fun retry(id: Long) {
        val line = _lines.value.firstOrNull { it.id == id } ?: return
        val newRev = line.rev + 1
        _lines.update { list -> list.map { if (it.id == id) it.copy(shown = "", arComplete = false, rev = newRev) else it } }
        scope.launch { refine(id, line.original, Source.MANUAL, newRev) }
    }

    fun clear() {
        _lines.value = emptyList()
    }

    fun dismissNotice() = clearNotice()

    // ---------------------------------------------------------------------------------

    private fun startPipeline(lineId: Long, text: String, source: Source, rev: Int) {
        scope.launch {
            val draft = offline.translate(text) ?: return@launch
            update(lineId, rev) { it.copy(draft = draft) }
            if (!router.hasProvider) {
                update(lineId, rev) { it.copy(shown = it.shown.ifBlank { draft }, status = LineStatus.OFFLINE) }
                return@launch
            }
            // If the AI has not finished the sentence by now, show the instant one (and keep it).
            delay(STABLE_FALLBACK_MS)
            update(lineId, rev) { line ->
                if (line.shown.isBlank() && !line.arComplete && line.draft.isNotBlank()) line.copy(shown = line.draft) else line
            }
        }
        scope.launch { refine(lineId, text, source, rev) }
    }

    private suspend fun refine(lineId: Long, text: String, source: Source, rev: Int) {
        if (!router.hasProvider) {
            markOffline(lineId, rev)
            setNotice(Notice.OFFLINE_ONLY)
            return
        }
        update(lineId, rev) { it.copy(status = LineStatus.REFINING) }
        refineSlots.withPermit {
            // Fell behind: newer lines matter more than old ones during live play.
            if (source.isLive && ids.get() - lineId > STALE_GAP) {
                markOffline(lineId, rev)
                return@withPermit
            }
            if (currentRev(lineId) != rev) return@withPermit
            busy(+1)
            try {
                val s = settings.value
                val context = recentOriginals(before = lineId)
                val acc = StringBuilder()
                var provider = ""
                router.stream(
                    task = AiTask.LINE,
                    system = { LinePrompt.system(s.style, s.detail) },
                    messages = { listOf(AiMessage(Role.USER, LinePrompt.userText(text, context))) },
                    options = LlmOptions(maxTokens = if (s.detail == DetailLevel.DETAILED) 480 else 320),
                ).collect { event ->
                    when (event) {
                        is AiEvent.Started -> provider = event.target.label
                        is AiEvent.Delta -> {
                            acc.append(event.text)
                            applyPartial(lineId, rev, acc.toString())
                        }
                    }
                }
                finish(lineId, rev, acc.toString(), provider, source)
                clearNotice()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                markOffline(lineId, rev)
                setNotice(e.toNotice(), e.message)
            } catch (e: Exception) {
                markOffline(lineId, rev)
                setNotice(Notice.NETWORK, e.message)
            } finally {
                busy(-1)
            }
        }
    }

    private fun applyPartial(lineId: Long, rev: Int, raw: String) {
        if (LinePrompt.isSkip(raw)) return
        val parsed = LinePrompt.parseFirst(raw) ?: return
        val arDone = LinePrompt.isArComplete(raw)
        update(lineId, rev) { line ->
            val translation = parsed.translation.ifBlank { line.translation }
            line.copy(
                translation = translation,
                explanation = parsed.explanation.ifBlank { line.explanation },
                slang = parsed.slang.ifEmpty { line.slang },
                speaker = parsed.speaker.ifBlank { line.speaker },
                arComplete = line.arComplete || (arDone && translation.isNotBlank()),
                shown = if (line.shown.isBlank() && arDone && translation.isNotBlank()) translation else line.shown,
            )
        }
    }

    private fun finish(lineId: Long, rev: Int, raw: String, provider: String, source: Source) {
        if (currentRev(lineId) != rev) return
        if (LinePrompt.isSkip(raw)) {
            if (source.isLive) {
                _lines.update { list -> list.filterNot { it.id == lineId } }
            } else {
                update(lineId, rev) { it.copy(status = LineStatus.DONE, provider = provider, shown = it.shown.ifBlank { it.draft }) }
                if (source == Source.MANUAL) setNotice(Notice.NOTHING_FOUND)
            }
            return
        }
        val parsed = LinePrompt.parseFirst(raw)
        if (parsed == null || parsed.translation.isBlank()) {
            markOffline(lineId, rev)
            return
        }
        // The AI fixes OCR noise; if the corrected line is clearly the one just before, drop it.
        // Only very close in time, so a character repeating a line later is never removed.
        if (source.isLive && parsed.original.isNotBlank()) {
            val norm = TextSimilarity.normalize(parsed.original)
            val self = _lines.value.firstOrNull { it.id == lineId }
            val duplicate = self != null && _lines.value
                .filter { it.id < lineId && self.timestamp - it.timestamp < DUPLICATE_WINDOW_MS }
                .take(2)
                .any { TextSimilarity.similarity(TextSimilarity.normalize(it.original), norm) >= 0.92 }
            if (duplicate) {
                _lines.update { list -> list.filterNot { it.id == lineId } }
                return
            }
        }
        update(lineId, rev) { line ->
            line.copy(
                original = parsed.original.ifBlank { line.original },
                speaker = parsed.speaker,
                translation = parsed.translation,
                explanation = parsed.explanation,
                slang = parsed.slang,
                status = LineStatus.DONE,
                provider = provider,
                arComplete = true,
                shown = line.shown.ifBlank { parsed.translation },
            )
        }
        _lines.value.firstOrNull { it.id == lineId }?.let { _completed.tryEmit(it) }
    }

    private fun markOffline(lineId: Long, rev: Int) {
        update(lineId, rev) { it.copy(status = LineStatus.OFFLINE, shown = it.shown.ifBlank { it.draft }) }
    }

    private fun isRecentDuplicate(text: String, source: Source): Boolean {
        val norm = TextSimilarity.normalize(text)
        if (norm.length < 3) return true
        val now = System.currentTimeMillis()
        val recent = _lines.value
            .take(DUPLICATE_WINDOW + 2)
            .filter { now - it.timestamp < DUPLICATE_WINDOW_MS }
        if (recent.any { TextSimilarity.similarity(TextSimilarity.normalize(it.original), norm) >= 0.86 }) return true

        // Camera + microphone together: the same dialogue often arrives from both sources,
        // split differently. Skip a line whose words the other source already captured.
        val tokens = norm.split(' ').filter { it.length > 1 }
        if (tokens.size < 3) return false
        val pool = recent
            .filter { it.source != source && now - it.timestamp < CROSS_SOURCE_WINDOW_MS }
            .flatMap { TextSimilarity.normalize(it.original).split(' ') }
            .toHashSet()
        if (pool.isEmpty()) return false
        val covered = tokens.count { it in pool }.toDouble() / tokens.size
        return covered >= 0.75
    }

    private fun recentOriginals(before: Long): List<String> =
        _lines.value.asSequence()
            .filter { it.id < before && it.original.isNotBlank() }
            .take(CONTEXT_LINES)
            .map { if (it.speaker.isNotBlank()) "${it.speaker}: ${it.original}" else it.original }
            .toList()
            .asReversed()

    private fun currentRev(id: Long): Int? = _lines.value.firstOrNull { it.id == id }?.rev

    /** Applies [transform] only if the line still has revision [rev]. */
    private fun update(id: Long, rev: Int, transform: (LiveLine) -> LiveLine) {
        _lines.update { list -> list.map { if (it.id == id && it.rev == rev) transform(it) else it } }
    }

    private fun busy(delta: Int) {
        _status.update { it.copy(busy = (it.busy + delta).coerceAtLeast(0)) }
    }

    private fun setNotice(notice: Notice, detail: String? = null) {
        _status.update { it.copy(notice = notice, detail = detail) }
    }

    private fun clearNotice() {
        _status.update { if (it.notice == null) it else it.copy(notice = null, detail = null) }
    }

    private companion object {
        const val MAX_LINES = 2_000
        const val MAX_PARALLEL_REFINES = 3
        const val MAX_PARALLEL_TRANSCRIBE = 2
        const val STALE_GAP = 8
        const val CONTEXT_LINES = 3
        const val DUPLICATE_WINDOW = 6
        const val DUPLICATE_WINDOW_MS = 8_000L
        const val CROSS_SOURCE_WINDOW_MS = 25_000L
        const val AMEND_WINDOW_MS = 10_000L
        /** How long to wait for the AI before showing the instant translation instead. */
        const val STABLE_FALLBACK_MS = 1_500L
    }
}

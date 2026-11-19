package ly.majde.gtatranslator.engine

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
 * Two-stage live pipeline:
 *  1. the line appears instantly with an on-device translation (ML Kit, offline);
 *  2. an AI model streams a natural translation + explanation that replaces it.
 * Live input that falls behind is never queued forever: stale lines keep their
 * instant translation so the feed always stays in sync with the game.
 */
class LiveEngine(
    private val router: AiRouter,
    private val offline: OfflineTranslator,
    private val settings: StateFlow<AppSettings>,
    private val scope: CoroutineScope,
    initial: List<LiveLine>,
) {
    private val _lines = MutableStateFlow(initial)
    val lines: StateFlow<List<LiveLine>> = _lines.asStateFlow()

    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private val _completed = MutableSharedFlow<LiveLine>(extraBufferCapacity = 8)
    /** Emits each line once its AI translation is complete (used for read-aloud). */
    val completed: SharedFlow<LiveLine> = _completed.asSharedFlow()

    private val ids = AtomicLong(initial.maxOfOrNull { it.id } ?: 0L)
    private val refineSlots = Semaphore(MAX_PARALLEL_REFINES)
    private val transcribeSlots = Semaphore(MAX_PARALLEL_TRANSCRIBE)

    fun submitText(text: String, source: Source) {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.length < 2) return
        if (source.isLive && isRecentDuplicate(clean)) return
        val line = LiveLine(id = ids.incrementAndGet(), source = source, original = clean)
        _lines.update { (listOf(line) + it).take(MAX_LINES) }

        scope.launch {
            val draft = offline.translate(clean) ?: return@launch
            update(line.id) { if (it.translation.isBlank()) it.copy(draft = draft) else it }
        }
        scope.launch { refine(line.id, clean, source) }
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

    /** Re-runs the AI translation for one line (e.g. after adding a key). */
    fun retry(id: Long) {
        val line = _lines.value.firstOrNull { it.id == id } ?: return
        scope.launch { refine(line.id, line.original, Source.MANUAL) }
    }

    fun clear() {
        _lines.value = emptyList()
    }

    fun dismissNotice() = clearNotice()

    // ---------------------------------------------------------------------------------

    private suspend fun refine(lineId: Long, text: String, source: Source) {
        if (!router.hasProvider) {
            update(lineId) { it.copy(status = LineStatus.OFFLINE) }
            setNotice(Notice.OFFLINE_ONLY)
            return
        }
        update(lineId) { it.copy(status = LineStatus.REFINING) }
        refineSlots.withPermit {
            // Fell behind: newer lines matter more than old ones during live play.
            if (source.isLive && ids.get() - lineId > STALE_GAP) {
                update(lineId) { it.copy(status = LineStatus.OFFLINE) }
                return@withPermit
            }
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
                            applyPartial(lineId, acc.toString())
                        }
                    }
                }
                finish(lineId, acc.toString(), provider, source)
                clearNotice()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                update(lineId) { it.copy(status = LineStatus.OFFLINE) }
                setNotice(e.toNotice(), e.message)
            } catch (e: Exception) {
                update(lineId) { it.copy(status = LineStatus.OFFLINE) }
                setNotice(Notice.NETWORK, e.message)
            } finally {
                busy(-1)
            }
        }
    }

    private fun applyPartial(lineId: Long, raw: String) {
        if (LinePrompt.isSkip(raw)) return
        val parsed = LinePrompt.parseFirst(raw) ?: return
        update(lineId) { line ->
            line.copy(
                translation = parsed.translation.ifBlank { line.translation },
                explanation = parsed.explanation.ifBlank { line.explanation },
                slang = parsed.slang.ifEmpty { line.slang },
                speaker = parsed.speaker.ifBlank { line.speaker },
            )
        }
    }

    private fun finish(lineId: Long, raw: String, provider: String, source: Source) {
        if (LinePrompt.isSkip(raw)) {
            if (source.isLive) {
                _lines.update { list -> list.filterNot { it.id == lineId } }
            } else {
                update(lineId) { it.copy(status = LineStatus.DONE, provider = provider) }
                if (source == Source.MANUAL) setNotice(Notice.NOTHING_FOUND)
            }
            return
        }
        val parsed = LinePrompt.parseFirst(raw)
        if (parsed == null || parsed.translation.isBlank()) {
            update(lineId) { it.copy(status = LineStatus.OFFLINE) }
            return
        }
        // The AI fixes OCR noise; if the corrected line duplicates a recent one, drop it.
        if (source.isLive && parsed.original.isNotBlank()) {
            val norm = TextSimilarity.normalize(parsed.original)
            val duplicate = _lines.value
                .filter { it.id != lineId }
                .take(DUPLICATE_WINDOW)
                .any { TextSimilarity.similarity(TextSimilarity.normalize(it.original), norm) >= 0.9 }
            if (duplicate) {
                _lines.update { list -> list.filterNot { it.id == lineId } }
                return
            }
        }
        update(lineId) { line ->
            line.copy(
                original = parsed.original.ifBlank { line.original },
                speaker = parsed.speaker,
                translation = parsed.translation,
                explanation = parsed.explanation,
                slang = parsed.slang,
                status = LineStatus.DONE,
                provider = provider,
            )
        }
        _lines.value.firstOrNull { it.id == lineId }?.let { _completed.tryEmit(it) }
    }

    private fun isRecentDuplicate(text: String): Boolean {
        val norm = TextSimilarity.normalize(text)
        if (norm.length < 3) return true
        val now = System.currentTimeMillis()
        return _lines.value
            .take(DUPLICATE_WINDOW)
            .filter { now - it.timestamp < DUPLICATE_WINDOW_MS }
            .any { TextSimilarity.similarity(TextSimilarity.normalize(it.original), norm) >= 0.86 }
    }

    private fun recentOriginals(before: Long): List<String> =
        _lines.value.asSequence()
            .filter { it.id < before && it.original.isNotBlank() }
            .take(CONTEXT_LINES)
            .map { if (it.speaker.isNotBlank()) "${it.speaker}: ${it.original}" else it.original }
            .toList()
            .asReversed()

    private fun update(id: Long, transform: (LiveLine) -> LiveLine) {
        _lines.update { list -> list.map { if (it.id == id) transform(it) else it } }
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
        const val MAX_PARALLEL_REFINES = 2
        const val MAX_PARALLEL_TRANSCRIBE = 2
        const val STALE_GAP = 4
        const val CONTEXT_LINES = 3
        const val DUPLICATE_WINDOW = 6
        const val DUPLICATE_WINDOW_MS = 45_000L
    }
}

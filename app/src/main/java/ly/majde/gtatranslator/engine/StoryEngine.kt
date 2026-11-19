package ly.majde.gtatranslator.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ly.majde.gtatranslator.ai.AiEvent
import ly.majde.gtatranslator.ai.AiException
import ly.majde.gtatranslator.ai.AiMessage
import ly.majde.gtatranslator.ai.AiRouter
import ly.majde.gtatranslator.ai.AiTask
import ly.majde.gtatranslator.ai.LlmOptions
import ly.majde.gtatranslator.ai.Role
import ly.majde.gtatranslator.data.AppSettings

/**
 * Builds the character guide and the "previously on" recap from the transcript.
 * Both are cached and only rebuilt when enough new dialogue has arrived.
 */
class StoryEngine(
    private val router: AiRouter,
    private val settings: StateFlow<AppSettings>,
    private val lines: StateFlow<List<LiveLine>>,
    private val scope: CoroutineScope,
    initial: StoryState,
) {
    private val _state = MutableStateFlow(initial.copy(loadingCharacters = false, loadingRecap = false, error = null, showRecapCard = false))
    val state: StateFlow<StoryState> = _state.asStateFlow()

    /** Characters are refreshed when at least [MIN_NEW_LINES] lines were added since last time. */
    fun refreshCharacters(force: Boolean = false) {
        val s = _state.value
        val count = lines.value.size
        if (s.loadingCharacters || count < MIN_LINES) return
        if (!force && s.characters.isNotEmpty() && count - s.charactersLineCount < MIN_NEW_LINES) return
        if (!router.hasProvider) {
            _state.update { it.copy(error = Notice.NO_KEY) }
            return
        }
        _state.update { it.copy(loadingCharacters = true, error = null) }
        scope.launch {
            try {
                val raw = generate(StoryPrompt.charactersSystem(settings.value.style), maxTokens = 1_400)
                val parsed = StoryPrompt.parseCharacters(raw)
                _state.update {
                    it.copy(
                        characters = if (parsed.isNotEmpty()) parsed else it.characters,
                        charactersLineCount = count,
                        loadingCharacters = false,
                    )
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(loadingCharacters = false) }
                throw e
            } catch (e: AiException) {
                _state.update { it.copy(loadingCharacters = false, error = e.toNotice()) }
            } catch (e: Exception) {
                _state.update { it.copy(loadingCharacters = false, error = Notice.NETWORK) }
            }
        }
    }

    fun refreshRecap(force: Boolean = false, showCard: Boolean = false) {
        val s = _state.value
        val count = lines.value.size
        if (s.loadingRecap || count < MIN_LINES) return
        if (!force && s.recap.isNotBlank() && count - s.recapLineCount < MIN_NEW_LINES) {
            if (showCard) _state.update { it.copy(showRecapCard = true) }
            return
        }
        if (!router.hasProvider) {
            _state.update { it.copy(error = Notice.NO_KEY) }
            return
        }
        _state.update { it.copy(loadingRecap = true, error = null, showRecapCard = showCard || it.showRecapCard) }
        scope.launch {
            try {
                val raw = generate(StoryPrompt.recapSystem(settings.value.style), maxTokens = 700)
                _state.update { it.copy(recap = raw.trim(), recapLineCount = count, loadingRecap = false) }
            } catch (e: CancellationException) {
                _state.update { it.copy(loadingRecap = false) }
                throw e
            } catch (e: AiException) {
                _state.update { it.copy(loadingRecap = false, error = e.toNotice(), showRecapCard = false) }
            } catch (e: Exception) {
                _state.update { it.copy(loadingRecap = false, error = Notice.NETWORK, showRecapCard = false) }
            }
        }
    }

    /** Called on app start: offer a recap if the player is coming back after a break. */
    fun maybeOfferRecap() {
        val newest = lines.value.firstOrNull() ?: return
        val away = System.currentTimeMillis() - newest.timestamp
        if (away >= BREAK_MS && lines.value.size >= MIN_LINES && router.hasProvider) {
            refreshRecap(showCard = true)
        }
    }

    fun dismissRecapCard() {
        _state.update { it.copy(showRecapCard = false) }
    }

    fun clear() {
        _state.value = StoryState()
    }

    private suspend fun generate(system: String, maxTokens: Int): String {
        val acc = StringBuilder()
        router.stream(
            task = AiTask.CHAT,
            system = { system },
            messages = { target ->
                val budget = if (target.client.providerName == "Gemini") 80_000 else 9_000
                listOf(AiMessage(Role.USER, StoryPrompt.user(ChatPrompt.transcript(lines.value, budget))))
            },
            options = LlmOptions(maxTokens = maxTokens),
        ).collect { if (it is AiEvent.Delta) acc.append(it.text) }
        return acc.toString()
    }

    private companion object {
        const val MIN_LINES = 4
        const val MIN_NEW_LINES = 12
        const val BREAK_MS = 30 * 60 * 1000L
    }
}

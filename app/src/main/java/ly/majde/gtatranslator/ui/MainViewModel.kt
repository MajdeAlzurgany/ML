package ly.majde.gtatranslator.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ly.majde.gtatranslator.ai.AiException
import ly.majde.gtatranslator.ai.AiRouter
import ly.majde.gtatranslator.audio.Speaker
import ly.majde.gtatranslator.audio.SpeechSegmenter
import ly.majde.gtatranslator.camera.SubtitleTracker
import ly.majde.gtatranslator.camera.toJpegBytes
import ly.majde.gtatranslator.data.AppSettings
import ly.majde.gtatranslator.data.HistoryStore
import ly.majde.gtatranslator.data.LiveMode
import ly.majde.gtatranslator.data.SettingsRepository
import ly.majde.gtatranslator.engine.ChatEngine
import ly.majde.gtatranslator.engine.ChatMessage
import ly.majde.gtatranslator.engine.EngineStatus
import ly.majde.gtatranslator.engine.LiveEngine
import ly.majde.gtatranslator.engine.LiveLine
import ly.majde.gtatranslator.engine.Notice
import ly.majde.gtatranslator.engine.Source
import ly.majde.gtatranslator.engine.toNotice
import ly.majde.gtatranslator.translate.OfflineTranslator
import okhttp3.OkHttpClient

enum class Provider { GROQ, GEMINI }

sealed interface KeyCheck {
    data object Idle : KeyCheck
    data object Checking : KeyCheck
    data class Ok(val modelCount: Int) : KeyCheck
    data class Failed(val notice: Notice, val detail: String? = null) : KeyCheck
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepo = SettingsRepository(app)
    val settings: StateFlow<AppSettings> = settingsRepo.settings

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val router = AiRouter(settings, http)
    private val offline = OfflineTranslator()
    private val store = HistoryStore(app)
    private val speaker = Speaker(app)

    private val live: LiveEngine
    private val chat: ChatEngine
    private val _listening = MutableStateFlow(false)

    init {
        val (savedLines, savedChat) = store.load()
        live = LiveEngine(router, offline, settings, viewModelScope, savedLines)
        chat = ChatEngine(router, settings, live.lines, viewModelScope, savedChat)

        viewModelScope.launch { offline.prepare() }

        // Persist history (debounced) so the story chat keeps its context.
        viewModelScope.launch(Dispatchers.IO) {
            combine(live.lines, chat.messages) { l, c -> l to c }.collectLatest { (l, c) ->
                delay(1_500)
                runCatching { store.save(l, c.filter { !it.streaming }) }
            }
        }

        // Read finished translations aloud (camera mode only, to avoid mic feedback).
        viewModelScope.launch {
            live.completed.collect { line ->
                val s = settings.value
                if (s.speak && !_listening.value) speaker.speak(line.bestTranslation)
            }
        }
    }

    val lines: StateFlow<List<LiveLine>> = live.lines
    val status: StateFlow<EngineStatus> = live.status
    val offlineState: StateFlow<OfflineTranslator.State> = offline.state
    val chatMessages: StateFlow<List<ChatMessage>> = chat.messages
    val chatStreaming: StateFlow<Boolean> = chat.streaming

    fun updateSettings(transform: (AppSettings) -> AppSettings) = settingsRepo.update(transform)
    fun setLiveMode(mode: LiveMode) {
        if (mode != LiveMode.MIC) stopListening()
        updateSettings { it.copy(liveMode = mode) }
    }

    fun retryOfflinePack() {
        viewModelScope.launch { offline.prepare() }
    }

    fun submitManual(text: String) = live.submitText(text, Source.MANUAL)
    fun retryLine(id: Long) = live.retry(id)
    fun clearLines() = live.clear()
    fun dismissNotice() = live.dismissNotice()
    fun speak(line: LiveLine) = speaker.speak(line.bestTranslation)

    // ---- Ask about a line → chat -----------------------------------------------------

    private val _askRequest = MutableStateFlow<LiveLine?>(null)
    val askRequest: StateFlow<LiveLine?> = _askRequest.asStateFlow()
    fun ask(line: LiveLine) {
        _askRequest.value = line
    }
    fun consumeAsk() {
        _askRequest.value = null
    }

    fun sendChat(text: String, quote: LiveLine?) = chat.send(text, quote)
    fun stopChat() = chat.stop()
    fun clearChat() = chat.clear()

    // ---- Camera ------------------------------------------------------------------------

    private val tracker = SubtitleTracker()
    private val _liveText = MutableStateFlow("")
    val liveText: StateFlow<String> = _liveText.asStateFlow()
    private val _cameraPaused = MutableStateFlow(false)
    val cameraPaused: StateFlow<Boolean> = _cameraPaused.asStateFlow()

    fun onOcrText(text: String) {
        if (_liveText.value != text) _liveText.value = text
        if (_cameraPaused.value) return
        tracker.offer(text)?.let { live.submitText(it, Source.CAMERA) }
    }

    fun toggleCameraPaused() {
        _cameraPaused.update { !it }
        tracker.reset()
    }

    fun onSnapshot(bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.Default) { live.submitImage(bitmap.toJpegBytes()) }
    }

    // ---- Microphone ----------------------------------------------------------------------

    private var listenJob: Job? = null
    val listening: StateFlow<Boolean> = _listening.asStateFlow()
    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()
    private val _micError = MutableStateFlow(false)
    val micError: StateFlow<Boolean> = _micError.asStateFlow()

    fun startListening() {
        if (listenJob?.isActive == true) return
        _micError.value = false
        speaker.stop()
        listenJob = viewModelScope.launch {
            _listening.value = true
            try {
                SpeechSegmenter(
                    context = getApplication<Application>(),
                    sensitivity = { settings.value.micSensitivity },
                    onLevel = { _micLevel.value = it },
                    onSegment = { live.submitAudio(it) },
                ).run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _micError.value = true
            } finally {
                _listening.value = false
                _micLevel.value = 0f
            }
        }
    }

    fun stopListening() {
        listenJob?.cancel()
        listenJob = null
    }

    // ---- Keys ------------------------------------------------------------------------------

    private val _keyChecks = MutableStateFlow<Map<Provider, KeyCheck>>(emptyMap())
    val keyChecks: StateFlow<Map<Provider, KeyCheck>> = _keyChecks.asStateFlow()
    private val _groqModels = MutableStateFlow<List<String>>(emptyList())
    val groqModels: StateFlow<List<String>> = _groqModels.asStateFlow()
    private val _geminiModels = MutableStateFlow<List<String>>(emptyList())
    val geminiModels: StateFlow<List<String>> = _geminiModels.asStateFlow()

    /** Saves the key and verifies it by listing the provider's models. */
    fun saveAndCheckKey(provider: Provider, key: String) {
        val clean = key.trim()
        updateSettings {
            when (provider) {
                Provider.GROQ -> it.copy(groqKey = clean)
                Provider.GEMINI -> it.copy(geminiKey = clean)
            }
        }
        if (clean.isBlank()) {
            _keyChecks.update { it - provider }
            return
        }
        _keyChecks.update { it + (provider to KeyCheck.Checking) }
        viewModelScope.launch {
            val result = try {
                val models = when (provider) {
                    Provider.GROQ -> router.groq.listModels(clean).also { _groqModels.value = it }
                    Provider.GEMINI -> router.gemini.listModels(clean).also { _geminiModels.value = it }
                }
                KeyCheck.Ok(models.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                KeyCheck.Failed(e.toNotice(), e.message)
            } catch (e: Exception) {
                KeyCheck.Failed(Notice.NETWORK, e.message)
            }
            _keyChecks.update { it + (provider to result) }
        }
    }

    override fun onCleared() {
        stopListening()
        speaker.shutdown()
        offline.close()
        runCatching { store.save(live.lines.value, chat.messages.value.filter { !it.streaming }) }
        super.onCleared()
    }
}

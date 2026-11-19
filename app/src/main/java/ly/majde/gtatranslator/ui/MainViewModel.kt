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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ly.majde.gtatranslator.ai.AiException
import ly.majde.gtatranslator.ai.AiRouter
import ly.majde.gtatranslator.ai.QuotaInfo
import ly.majde.gtatranslator.audio.Speaker
import ly.majde.gtatranslator.audio.SpeechSegmenter
import ly.majde.gtatranslator.camera.ScanLine
import ly.majde.gtatranslator.camera.SubtitleLocator
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
import ly.majde.gtatranslator.engine.SavedWord
import ly.majde.gtatranslator.engine.Source
import ly.majde.gtatranslator.engine.StoryEngine
import ly.majde.gtatranslator.engine.StoryState
import ly.majde.gtatranslator.engine.WordsEngine
import ly.majde.gtatranslator.engine.toNotice
import ly.majde.gtatranslator.translate.OfflineTranslator
import ly.majde.gtatranslator.update.AppRelease
import ly.majde.gtatranslator.update.UpdateManager
import ly.majde.gtatranslator.update.UpdateState
import okhttp3.OkHttpClient

enum class Provider { GROQ, GEMINI }

sealed interface KeyCheck {
    data object Idle : KeyCheck
    data object Checking : KeyCheck
    data class Ok(val modelCount: Int) : KeyCheck
    data class Failed(val notice: Notice, val detail: String? = null) : KeyCheck
}

enum class ScanState { IDLE, SCANNING, FOUND, NOT_FOUND }

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
    private val updater = UpdateManager(app, http)

    private val live: LiveEngine
    private val chat: ChatEngine
    private val story: StoryEngine
    private val wordsEngine: WordsEngine
    private val _listening = MutableStateFlow(false)

    init {
        val (savedLines, savedChat) = store.load()
        live = LiveEngine(router, offline, settings, viewModelScope, savedLines)
        story = StoryEngine(router, settings, live.lines, viewModelScope, store.loadStory())
        chat = ChatEngine(router, settings, live.lines, story.state, viewModelScope, savedChat)
        wordsEngine = WordsEngine(store.loadWords())

        viewModelScope.launch { offline.prepare() }
        viewModelScope.launch { updater.check() }
        story.maybeOfferRecap()

        // Persist everything (debounced) so the story keeps its context across restarts.
        viewModelScope.launch(Dispatchers.IO) {
            combine(live.lines, chat.messages) { l, c -> l to c }.drop(1).collectLatest { (l, c) ->
                delay(1_500)
                runCatching { store.save(l, c.filter { !it.streaming }) }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            story.state.drop(1).collectLatest { s ->
                delay(500)
                runCatching { store.saveStory(s) }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            wordsEngine.words.drop(1).collectLatest { w ->
                delay(500)
                runCatching { store.saveWords(w) }
            }
        }

        // Read finished translations aloud (never while the mic is listening).
        viewModelScope.launch {
            live.completed.collect { line ->
                if (settings.value.speak && !_listening.value) speaker.speak(line.bestTranslation)
            }
        }
    }

    val lines: StateFlow<List<LiveLine>> = live.lines
    val status: StateFlow<EngineStatus> = live.status
    val offlineState: StateFlow<OfflineTranslator.State> = offline.state
    val chatMessages: StateFlow<List<ChatMessage>> = chat.messages
    val chatStreaming: StateFlow<Boolean> = chat.streaming
    val storyState: StateFlow<StoryState> = story.state
    val words: StateFlow<List<SavedWord>> = wordsEngine.words
    val quotas: StateFlow<Map<String, QuotaInfo>> = router.quota.quotas
    val activeModel: StateFlow<String?> = router.quota.active
    val updateState: StateFlow<UpdateState> = updater.state

    fun updateSettings(transform: (AppSettings) -> AppSettings) = settingsRepo.update(transform)

    fun setLiveMode(mode: LiveMode) {
        if (mode == LiveMode.CAMERA) stopListening()
        updateSettings { it.copy(liveMode = mode) }
    }

    fun retryOfflinePack() {
        viewModelScope.launch { offline.prepare() }
    }

    fun submitManual(text: String) = live.submitText(text, Source.MANUAL)
    fun retryLine(id: Long) = live.retry(id)
    fun clearLines() {
        live.clear()
        story.clear()
    }
    fun dismissNotice() = live.dismissNotice()
    fun speak(line: LiveLine) = speaker.speak(line.bestTranslation)
    fun speakEnglish(text: String) = speaker.speak(text, english = true)

    // ---- Ask about a line → story chat ------------------------------------------------

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

    // ---- Story: characters & recap ------------------------------------------------------

    fun refreshCharacters(force: Boolean = false) = story.refreshCharacters(force)
    fun refreshRecap(force: Boolean = false) = story.refreshRecap(force)
    fun dismissRecapCard() = story.dismissRecapCard()

    // ---- Words ------------------------------------------------------------------------------

    /** Saves (or un-saves) a slang term with the line it came from. Returns true if saved. */
    fun toggleWord(term: String, meaning: String, line: LiveLine?): Boolean = wordsEngine.toggle(term, meaning, line)

    /** Saves a whole line as a phrase to learn. */
    fun toggleLine(line: LiveLine): Boolean = wordsEngine.toggle(line.original, line.bestTranslation, line)
    fun removeWord(id: Long) = wordsEngine.remove(id)
    fun gradeWord(id: Long, knew: Boolean) = wordsEngine.grade(id, knew)
    fun dueWords(): List<SavedWord> = wordsEngine.due()

    // ---- Camera ------------------------------------------------------------------------

    private val tracker = SubtitleTracker()
    private val _liveText = MutableStateFlow("")
    val liveText: StateFlow<String> = _liveText.asStateFlow()
    private val _cameraPaused = MutableStateFlow(false)
    val cameraPaused: StateFlow<Boolean> = _cameraPaused.asStateFlow()
    private val _scanState = MutableStateFlow(ScanState.IDLE)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

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

    fun onScanStarted() {
        _scanState.value = ScanState.SCANNING
    }

    /** Places the subtitle region from a full-frame scan. Returns true if found. */
    fun onScanResult(lines: List<ScanLine>, viewWidth: Int, viewHeight: Int): Boolean {
        val suggestion = SubtitleLocator.suggest(lines, viewWidth.toFloat(), viewHeight.toFloat())
        return if (suggestion != null) {
            updateSettings { it.copy(roi = suggestion, roiConfigured = true) }
            tracker.reset()
            _scanState.value = ScanState.FOUND
            true
        } else {
            _scanState.value = ScanState.NOT_FOUND
            false
        }
    }

    fun clearScanState() {
        _scanState.value = ScanState.IDLE
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

    // ---- Updates -----------------------------------------------------------------------------

    fun checkForUpdates() {
        viewModelScope.launch { updater.check(force = true) }
    }

    fun installUpdate(release: AppRelease) {
        viewModelScope.launch { updater.downloadAndInstall(release) }
    }

    fun retryInstall() {
        val s = updater.state.value
        if (s is UpdateState.ReadyToInstall) updater.install(s.apk)
    }

    fun openUpdateInBrowser(release: AppRelease) = updater.openInBrowser(release)
    fun dismissUpdate() = updater.dismiss()

    // ---- Keys ------------------------------------------------------------------------------

    private val _keyChecks = MutableStateFlow<Map<Provider, KeyCheck>>(emptyMap())
    val keyChecks: StateFlow<Map<Provider, KeyCheck>> = _keyChecks.asStateFlow()
    private val _groqModels = MutableStateFlow<List<String>>(emptyList())
    val groqModels: StateFlow<List<String>> = _groqModels.asStateFlow()
    private val _geminiModels = MutableStateFlow<List<String>>(emptyList())
    val geminiModels: StateFlow<List<String>> = _geminiModels.asStateFlow()

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
        runCatching {
            store.save(live.lines.value, chat.messages.value.filter { !it.streaming })
            store.saveStory(story.state.value)
            store.saveWords(wordsEngine.words.value)
        }
        super.onCleared()
    }
}

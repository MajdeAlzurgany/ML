package ly.majde.gtatranslator.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ly.majde.gtatranslator.audio.SpeechSegmenter
import ly.majde.gtatranslator.camera.SubtitleTracker
import ly.majde.gtatranslator.camera.toJpegBytes
import ly.majde.gtatranslator.data.AppSettings
import ly.majde.gtatranslator.data.GeminiClient
import ly.majde.gtatranslator.data.GeminiException
import ly.majde.gtatranslator.data.SettingsRepository
import ly.majde.gtatranslator.engine.EngineState
import ly.majde.gtatranslator.engine.Notice
import ly.majde.gtatranslator.engine.Source
import ly.majde.gtatranslator.engine.TranslationEngine
import ly.majde.gtatranslator.engine.TranslationEntry
import ly.majde.gtatranslator.engine.toNotice
import okhttp3.OkHttpClient

sealed interface KeyCheck {
    data object Idle : KeyCheck
    data object Checking : KeyCheck
    data class Ok(val modelCount: Int) : KeyCheck
    data class Failed(val notice: Notice, val detail: String? = null) : KeyCheck
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepo = SettingsRepository(app)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()
    private val client = GeminiClient(http)
    private val engine = TranslationEngine(client, settingsRepo.settings, viewModelScope)

    val settings: StateFlow<AppSettings> = settingsRepo.settings
    val entries: StateFlow<List<TranslationEntry>> = engine.entries
    val engineState: StateFlow<EngineState> = engine.state

    fun updateSettings(transform: (AppSettings) -> AppSettings) = settingsRepo.update(transform)
    fun submitManual(text: String) = engine.submitText(text, Source.MANUAL)
    fun explainMore(id: Long) = engine.explainMore(id)
    fun clearHistory() = engine.clearHistory()
    fun dismissNotice() = engine.dismissNotice()

    // ---- Camera ------------------------------------------------------------------------

    private val tracker = SubtitleTracker()
    private val _liveText = MutableStateFlow("")
    val liveText: StateFlow<String> = _liveText.asStateFlow()
    private val _cameraPaused = MutableStateFlow(false)
    val cameraPaused: StateFlow<Boolean> = _cameraPaused.asStateFlow()

    /** Called from the camera analysis thread for every analyzed frame. */
    fun onOcrText(text: String) {
        if (_liveText.value != text) _liveText.value = text
        if (_cameraPaused.value) return
        tracker.offer(text)?.let { engine.submitText(it, Source.CAMERA) }
    }

    fun toggleCameraPaused() {
        _cameraPaused.update { !it }
        tracker.reset()
    }

    fun onSnapshot(bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.Default) {
            engine.submitImage(bitmap.toJpegBytes())
        }
    }

    // ---- Audio -------------------------------------------------------------------------

    private var listenJob: Job? = null
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()
    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()
    private val _micError = MutableStateFlow(false)
    val micError: StateFlow<Boolean> = _micError.asStateFlow()

    fun startListening() {
        if (listenJob?.isActive == true) return
        _micError.value = false
        listenJob = viewModelScope.launch {
            _listening.value = true
            try {
                SpeechSegmenter(
                    sensitivity = { settings.value.micSensitivity },
                    onLevel = { _micLevel.value = it },
                    onSegment = { engine.submitAudio(it) },
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

    // ---- Settings: key validation & model list ---------------------------------------

    private val _models = MutableStateFlow<List<String>>(emptyList())
    val models: StateFlow<List<String>> = _models.asStateFlow()
    private val _keyCheck = MutableStateFlow<KeyCheck>(KeyCheck.Idle)
    val keyCheck: StateFlow<KeyCheck> = _keyCheck.asStateFlow()

    fun checkKey(key: String = settings.value.apiKey) {
        if (key.isBlank()) {
            _keyCheck.value = KeyCheck.Failed(Notice.NO_KEY)
            return
        }
        _keyCheck.value = KeyCheck.Checking
        viewModelScope.launch {
            _keyCheck.value = try {
                val list = client.listModels(key)
                _models.value = list
                KeyCheck.Ok(list.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: GeminiException) {
                KeyCheck.Failed(e.toNotice(), e.message)
            } catch (e: Exception) {
                KeyCheck.Failed(Notice.NETWORK, e.message)
            }
        }
    }

    override fun onCleared() {
        stopListening()
        super.onCleared()
    }
}

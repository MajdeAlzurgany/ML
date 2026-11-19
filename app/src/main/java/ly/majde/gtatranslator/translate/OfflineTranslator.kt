package ly.majde.gtatranslator.translate

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/**
 * Instant, offline, unlimited translation on the device (Google ML Kit, the same models
 * as Google Translate's offline mode). Used as the immediate "draft" for every line,
 * and as the fallback whenever the AI providers are unavailable.
 */
class OfflineTranslator {

    enum class State { IDLE, DOWNLOADING, READY, FAILED }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val english = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.ARABIC)
            .build()
    )
    private val spanish = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.SPANISH)
            .setTargetLanguage(TranslateLanguage.ARABIC)
            .build()
    )
    private val languageId = LanguageIdentification.getClient()

    @Volatile
    private var spanishReady = false

    suspend fun prepare() {
        if (_state.value == State.READY || _state.value == State.DOWNLOADING) return
        _state.value = State.DOWNLOADING
        try {
            val conditions = DownloadConditions.Builder().build()
            english.downloadModelIfNeeded(conditions).await()
            _state.value = State.READY
            try {
                spanish.downloadModelIfNeeded(conditions).await()
                spanishReady = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                spanishReady = false
            }
        } catch (e: CancellationException) {
            if (_state.value == State.DOWNLOADING) _state.value = State.IDLE
            throw e
        } catch (e: Exception) {
            _state.value = State.FAILED
        }
    }

    /** Returns the Arabic draft, or null if the model is not ready. */
    suspend fun translate(text: String): String? {
        if (_state.value != State.READY) return null
        return try {
            val language = if (spanishReady && text.count { it == ' ' } >= 2) {
                runCatching { languageId.identifyLanguage(text).await() }.getOrDefault("und")
            } else {
                "en"
            }
            val translator = if (language == "es" && spanishReady) spanish else english
            translator.translate(text).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    fun close() {
        english.close()
        spanish.close()
        languageId.close()
    }
}

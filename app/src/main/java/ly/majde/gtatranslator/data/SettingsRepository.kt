package ly.majde.gtatranslator.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Single source of truth for user settings, persisted in SharedPreferences. */
class SettingsRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings) {
        val old = _settings.value
        val new = transform(old)
        if (new == old) return
        _settings.value = new
        prefs.edit {
            if (new.groqKey != old.groqKey) putSecret(this, K_GROQ_KEY, new.groqKey)
            if (new.geminiKey != old.geminiKey) {
                putSecret(this, K_GEMINI_KEY, new.geminiKey)
                remove(K_LEGACY_KEY)
            }
            putString(K_GROQ_MODEL, new.groqModel)
            putString(K_GEMINI_MODEL, new.geminiModel)
            putString(K_STYLE, new.style.name)
            putString(K_DETAIL, new.detail.name)
            putString(K_MODE, new.liveMode.name)
            putFloat(K_MIC, new.micSensitivity)
            putFloat(K_SCALE, new.textScale)
            putString(K_DISPLAY, new.displayMode.name)
            putFloat(K_READING_SCALE, new.readingScale)
            putInt(K_READING_COLOR, new.readingColor)
            putBoolean(K_READING_PREVIOUS, new.readingShowPrevious)
            putBoolean(K_SHOW_ORIGINAL, new.showOriginal)
            putBoolean(K_SPEAK, new.speak)
            putBoolean(K_WEB_SEARCH, new.webSearch)
            putBoolean(K_ONBOARDING, new.onboardingDone)
            putBoolean(K_ROI_CONFIGURED, new.roiConfigured)
            putFloat(K_ROI_L, new.roi.left)
            putFloat(K_ROI_T, new.roi.top)
            putFloat(K_ROI_R, new.roi.right)
            putFloat(K_ROI_B, new.roi.bottom)
        }
    }

    private fun putSecret(editor: SharedPreferences.Editor, key: String, value: String) {
        val encrypted = if (value.isBlank()) null else runCatching { KeyCipher.encrypt(value) }.getOrNull()
        if (encrypted == null) editor.remove(key) else editor.putString(key, encrypted)
    }

    private fun secret(key: String): String = prefs.getString(key, null)?.let { KeyCipher.decrypt(it) }.orEmpty()

    private fun load(): AppSettings {
        val d = AppSettings()
        val geminiKey = secret(K_GEMINI_KEY).ifBlank { secret(K_LEGACY_KEY) }
        val groqKey = secret(K_GROQ_KEY)
        return AppSettings(
            groqKey = groqKey,
            geminiKey = geminiKey,
            groqModel = prefs.getString(K_GROQ_MODEL, null).orEmpty(),
            geminiModel = prefs.getString(K_GEMINI_MODEL, null)?.takeIf { it.isNotBlank() }
                ?: prefs.getString(K_LEGACY_MODEL, null)?.takeIf { it.isNotBlank() }
                ?: d.geminiModel,
            style = enumOr(prefs.getString(K_STYLE, null), d.style),
            detail = enumOr(prefs.getString(K_DETAIL, null), d.detail),
            liveMode = enumOr(prefs.getString(K_MODE, null), d.liveMode),
            micSensitivity = prefs.getFloat(K_MIC, d.micSensitivity).coerceIn(0f, 1f),
            textScale = prefs.getFloat(K_SCALE, d.textScale).coerceIn(0.8f, 1.8f),
            displayMode = enumOr(prefs.getString(K_DISPLAY, null), d.displayMode),
            readingScale = prefs.getFloat(K_READING_SCALE, d.readingScale).coerceIn(0.5f, 3f),
            readingColor = prefs.getInt(K_READING_COLOR, d.readingColor).coerceIn(0, 2),
            readingShowPrevious = prefs.getBoolean(K_READING_PREVIOUS, d.readingShowPrevious),
            showOriginal = prefs.getBoolean(K_SHOW_ORIGINAL, d.showOriginal),
            speak = prefs.getBoolean(K_SPEAK, d.speak),
            webSearch = prefs.getBoolean(K_WEB_SEARCH, d.webSearch),
            onboardingDone = prefs.getBoolean(K_ONBOARDING, false) || groqKey.isNotBlank() || geminiKey.isNotBlank(),
            roiConfigured = prefs.getBoolean(K_ROI_CONFIGURED, prefs.contains(K_ROI_L)),
            roi = NormRect(
                prefs.getFloat(K_ROI_L, d.roi.left),
                prefs.getFloat(K_ROI_T, d.roi.top),
                prefs.getFloat(K_ROI_R, d.roi.right),
                prefs.getFloat(K_ROI_B, d.roi.bottom),
            ).sanitized(),
        )
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    private companion object {
        const val K_LEGACY_KEY = "api_key_enc"
        const val K_LEGACY_MODEL = "model"
        const val K_GROQ_KEY = "groq_key_enc"
        const val K_GEMINI_KEY = "gemini_key_enc"
        const val K_GROQ_MODEL = "groq_model"
        const val K_GEMINI_MODEL = "gemini_model"
        const val K_STYLE = "style"
        const val K_DETAIL = "detail"
        const val K_MODE = "live_mode"
        const val K_MIC = "mic_sensitivity"
        const val K_SCALE = "text_scale"
        const val K_DISPLAY = "display_mode"
        const val K_READING_SCALE = "reading_scale"
        const val K_READING_COLOR = "reading_color"
        const val K_READING_PREVIOUS = "reading_previous"
        const val K_SHOW_ORIGINAL = "show_original"
        const val K_SPEAK = "speak"
        const val K_WEB_SEARCH = "web_search"
        const val K_ONBOARDING = "onboarding_done"
        const val K_ROI_CONFIGURED = "roi_configured"
        const val K_ROI_L = "roi_l"
        const val K_ROI_T = "roi_t"
        const val K_ROI_R = "roi_r"
        const val K_ROI_B = "roi_b"
    }
}

package ly.majde.gtatranslator.data

import android.content.Context
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
            if (new.apiKey != old.apiKey) {
                val encrypted = if (new.apiKey.isBlank()) null else runCatching { KeyCipher.encrypt(new.apiKey) }.getOrNull()
                if (encrypted == null) remove(K_API_KEY) else putString(K_API_KEY, encrypted)
            }
            putString(K_MODEL, new.model)
            putString(K_STYLE, new.style.name)
            putString(K_DETAIL, new.detail.name)
            putInt(K_RPM, new.requestsPerMinute)
            putFloat(K_MIC, new.micSensitivity)
            putFloat(K_ROI_L, new.roi.left)
            putFloat(K_ROI_T, new.roi.top)
            putFloat(K_ROI_R, new.roi.right)
            putFloat(K_ROI_B, new.roi.bottom)
        }
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            apiKey = prefs.getString(K_API_KEY, null)?.let { KeyCipher.decrypt(it) }.orEmpty(),
            model = prefs.getString(K_MODEL, null)?.takeIf { it.isNotBlank() } ?: d.model,
            style = enumOr(prefs.getString(K_STYLE, null), d.style),
            detail = enumOr(prefs.getString(K_DETAIL, null), d.detail),
            requestsPerMinute = prefs.getInt(K_RPM, d.requestsPerMinute).coerceIn(1, 60),
            micSensitivity = prefs.getFloat(K_MIC, d.micSensitivity).coerceIn(0f, 1f),
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
        const val K_API_KEY = "api_key_enc"
        const val K_MODEL = "model"
        const val K_STYLE = "style"
        const val K_DETAIL = "detail"
        const val K_RPM = "rpm"
        const val K_MIC = "mic_sensitivity"
        const val K_ROI_L = "roi_l"
        const val K_ROI_T = "roi_t"
        const val K_ROI_R = "roi_r"
        const val K_ROI_B = "roi_b"
    }
}

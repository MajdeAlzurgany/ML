package ly.majde.gtatranslator.data

enum class OutputStyle { LIBYAN, MSA }

enum class DetailLevel { SHORT, DETAILED }

enum class LiveMode { CAMERA, MIC }

/** Region of the camera preview (normalized 0..1) where subtitles are read. */
data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {

    fun moveBy(dx: Float, dy: Float): NormRect {
        val w = right - left
        val h = bottom - top
        val nl = (left + dx).coerceIn(0f, 1f - w)
        val nt = (top + dy).coerceIn(0f, 1f - h)
        return NormRect(nl, nt, nl + w, nt + h)
    }

    fun resizeTopLeft(dx: Float, dy: Float): NormRect = NormRect(
        left = (left + dx).coerceIn(0f, right - MIN_SIZE),
        top = (top + dy).coerceIn(0f, bottom - MIN_SIZE),
        right = right,
        bottom = bottom,
    )

    fun resizeBottomRight(dx: Float, dy: Float): NormRect = NormRect(
        left = left,
        top = top,
        right = (right + dx).coerceIn(left + MIN_SIZE, 1f),
        bottom = (bottom + dy).coerceIn(top + MIN_SIZE, 1f),
    )

    fun sanitized(): NormRect {
        val valid = left in 0f..1f && right in 0f..1f && top in 0f..1f && bottom in 0f..1f &&
            right - left >= MIN_SIZE && bottom - top >= MIN_SIZE
        return if (valid) this else DEFAULT
    }

    companion object {
        const val MIN_SIZE = 0.06f
        val DEFAULT = NormRect(0.04f, 0.62f, 0.96f, 0.94f)
    }
}

data class AppSettings(
    val groqKey: String = "",
    val geminiKey: String = "",
    /** Empty = choose automatically. */
    val groqModel: String = "",
    val geminiModel: String = DEFAULT_GEMINI_MODEL,
    val style: OutputStyle = OutputStyle.LIBYAN,
    val detail: DetailLevel = DetailLevel.SHORT,
    val micSensitivity: Float = 0.5f,
    val roi: NormRect = NormRect.DEFAULT,
    val liveMode: LiveMode = LiveMode.CAMERA,
    val textScale: Float = 1f,
    val showOriginal: Boolean = true,
    val speak: Boolean = false,
    val webSearch: Boolean = true,
    val onboardingDone: Boolean = false,
) {
    val hasAnyKey: Boolean get() = groqKey.isNotBlank() || geminiKey.isNotBlank()

    companion object {
        const val DEFAULT_GEMINI_MODEL = "gemini-flash-latest"
    }
}

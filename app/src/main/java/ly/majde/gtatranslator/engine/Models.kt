package ly.majde.gtatranslator.engine

import ly.majde.gtatranslator.data.GeminiException

enum class Source { CAMERA, SNAPSHOT, AUDIO, MANUAL }

data class SlangTerm(val term: String, val meaning: String)

data class TranslationEntry(
    val id: Long,
    val source: Source,
    val original: String,
    val speaker: String,
    val translation: String,
    val explanation: String,
    val slang: List<SlangTerm>,
    val kind: String,
    val timestamp: Long,
    val deepExplanation: String? = null,
    val deepLoading: Boolean = false,
)

/** User-facing conditions; the UI maps each one to a localized message. */
enum class Notice {
    NO_KEY, INVALID_KEY, RATE_LIMIT, DAILY_QUOTA, BLOCKED, NETWORK, SERVER,
    MODEL_NOT_FOUND, REGION, BAD_REQUEST, PARSE, NOTHING_FOUND,
}

data class EngineState(
    val busy: Boolean = false,
    val pending: Int = 0,
    val notice: Notice? = null,
    val noticeDetail: String? = null,
    /** SystemClock.elapsedRealtime() until which requests are paused (rate limit). */
    val cooldownUntil: Long = 0L,
)

fun GeminiException.toNotice(): Notice = when (kind) {
    GeminiException.Kind.INVALID_KEY -> Notice.INVALID_KEY
    GeminiException.Kind.RATE_LIMIT -> if (dailyQuota) Notice.DAILY_QUOTA else Notice.RATE_LIMIT
    GeminiException.Kind.BLOCKED -> Notice.BLOCKED
    GeminiException.Kind.NETWORK -> Notice.NETWORK
    GeminiException.Kind.SERVER -> Notice.SERVER
    GeminiException.Kind.MODEL_NOT_FOUND -> Notice.MODEL_NOT_FOUND
    GeminiException.Kind.REGION -> Notice.REGION
    GeminiException.Kind.BAD_REQUEST -> Notice.BAD_REQUEST
    GeminiException.Kind.PARSE -> Notice.PARSE
}

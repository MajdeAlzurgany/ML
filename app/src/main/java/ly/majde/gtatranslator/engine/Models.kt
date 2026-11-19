package ly.majde.gtatranslator.engine

import ly.majde.gtatranslator.ai.AiException
import ly.majde.gtatranslator.ai.Role

enum class Source { CAMERA, SNAPSHOT, AUDIO, MANUAL;

    val isLive: Boolean get() = this == CAMERA || this == AUDIO
}

enum class LineStatus { DRAFT, REFINING, DONE, OFFLINE }

data class SlangTerm(val term: String, val meaning: String)

/** One translated line of game dialogue. */
data class LiveLine(
    val id: Long,
    val source: Source,
    val original: String,
    val speaker: String = "",
    /** Instant on-device translation. */
    val draft: String = "",
    /** AI translation (streams in). */
    val translation: String = "",
    val explanation: String = "",
    val slang: List<SlangTerm> = emptyList(),
    val status: LineStatus = LineStatus.DRAFT,
    val provider: String = "",
    val timestamp: Long = System.currentTimeMillis(),
) {
    val bestTranslation: String get() = translation.ifBlank { draft }
}

data class ChatMessage(
    val id: Long,
    val role: Role,
    val text: String,
    val quote: String? = null,
    val streaming: Boolean = false,
    val provider: String = "",
    val error: Notice? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

/** User-facing conditions; the UI maps each one to a localized message. */
enum class Notice {
    NO_KEY, OFFLINE_ONLY, INVALID_KEY, RATE_LIMIT, DAILY_LIMIT, NETWORK, SERVER, REGION,
    BLOCKED, NOTHING_FOUND, MIC_ERROR, BAD_REQUEST,
}

data class EngineStatus(
    val busy: Int = 0,
    val notice: Notice? = null,
    val detail: String? = null,
)

fun AiException.toNotice(): Notice = when (kind) {
    AiException.Kind.NO_PROVIDER -> Notice.NO_KEY
    AiException.Kind.INVALID_KEY -> Notice.INVALID_KEY
    AiException.Kind.RATE_LIMIT -> if (daily) Notice.DAILY_LIMIT else Notice.RATE_LIMIT
    AiException.Kind.BLOCKED -> Notice.BLOCKED
    AiException.Kind.NETWORK -> Notice.NETWORK
    AiException.Kind.SERVER -> Notice.SERVER
    AiException.Kind.MODEL_NOT_FOUND -> Notice.SERVER
    AiException.Kind.REGION -> Notice.REGION
    AiException.Kind.BAD_REQUEST -> Notice.BAD_REQUEST
    AiException.Kind.PARSE -> Notice.SERVER
}

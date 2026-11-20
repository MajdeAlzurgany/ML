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
    /**
     * The sentence shown on screen in "stable" mode. Set once (AI translation as soon as it
     * is complete, or the instant translation if the AI is slow/unavailable) and never changed.
     */
    val shown: String = "",
    /** True once the AI has finished writing the translation itself (explanation may follow). */
    val arComplete: Boolean = false,
    /** Bumped when the line is re-read or retried, so late results of old work are ignored. */
    val rev: Int = 0,
) {
    val bestTranslation: String get() = translation.ifBlank { draft }
    val stableText: String get() = shown.ifBlank { if (status == LineStatus.DONE || status == LineStatus.OFFLINE) bestTranslation else "" }
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

/** A character in the story, built from the dialogue the player has seen. */
data class StoryCharacter(
    val name: String,
    val role: String,
    val relations: String,
    val latest: String,
)

data class StoryState(
    val characters: List<StoryCharacter> = emptyList(),
    /** Number of translated lines the character list was built from. */
    val charactersLineCount: Int = 0,
    val recap: String = "",
    val recapLineCount: Int = 0,
    val loadingCharacters: Boolean = false,
    val loadingRecap: Boolean = false,
    val error: Notice? = null,
    /** True when a "previously on" recap should be shown on the live screen. */
    val showRecapCard: Boolean = false,
)

/** A word or phrase the player saved to learn. Reviewed with a Leitner schedule. */
data class SavedWord(
    val id: Long,
    val term: String,
    val meaning: String,
    val example: String = "",
    val exampleTranslation: String = "",
    val box: Int = 0,
    val due: Long = 0L,
    val added: Long = System.currentTimeMillis(),
    val reviews: Int = 0,
    val correct: Int = 0,
)

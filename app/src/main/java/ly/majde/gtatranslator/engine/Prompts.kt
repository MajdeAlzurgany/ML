package ly.majde.gtatranslator.engine

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ly.majde.gtatranslator.data.DetailLevel
import ly.majde.gtatranslator.data.OutputStyle

data class ParsedLine(
    val original: String,
    val speaker: String,
    val translation: String,
    val explanation: String,
    val slang: List<SlangTerm>,
)

private fun styleText(style: OutputStyle) = when (style) {
    OutputStyle.LIBYAN -> "natural Libyan Arabic dialect (Tripoli style) written in Arabic script, the way a Libyan friend would say it"
    OutputStyle.MSA -> "clear, natural Modern Standard Arabic"
}

/**
 * Line translation uses a compact tagged format instead of JSON: it streams nicely
 * (the translation shows up while it is being written) and is cheap in tokens.
 */
object LinePrompt {

    fun system(style: OutputStyle, detail: DetailLevel): String {
        val why = when (detail) {
            DetailLevel.SHORT -> "one short sentence"
            DetailLevel.DETAILED -> "two or three sentences"
        }
        return """
            You translate dialogue from the video game Grand Theft Auto VI (Leonida is Rockstar's version of Florida, with Vice City; the leads are Jason and Lucia) for an Arabic-speaking player from Libya.
            Translate into ${styleText(style)}.
            Reply in EXACTLY this format and nothing else (the AR line must come first, it is shown live):
            AR: <natural translation; keep names as they are; keep the tone, sarcasm and humor; for profanity use an equivalent register, never more graphic>
            ORIGINAL: <the line in clean English or Spanish; silently fix obvious OCR or speech-recognition errors>
            SPEAKER: <speaker name if it is shown, otherwise ->
            WHY: <$why explaining the real meaning, subtext, joke, slang or cultural reference; "-" if the line is obvious>
            SLANG: <term = short Arabic meaning | term = meaning ... or ->
            If the input is not dialogue or a meaningful game sentence (HUD numbers, menus, button prompts, song lyrics, gibberish), reply only: SKIP
        """.trimIndent()
    }

    fun userText(line: String, context: List<String>): String = buildString {
        if (context.isNotEmpty()) {
            append("Earlier lines (context only):\n")
            context.forEach { append("- ").append(it).append('\n') }
            append('\n')
        }
        append("LINE: ").append(line)
    }

    fun userImage(context: List<String>): String = buildString {
        if (context.isNotEmpty()) {
            append("Earlier lines (context only):\n")
            context.forEach { append("- ").append(it).append('\n') }
            append('\n')
        }
        append("This is a photo of the TV. Read every subtitle or dialogue line and any mission objective text. ")
        append("Output one block in the format above for each, separated by a line containing only ---. ")
        append("If there is nothing to translate, reply only: SKIP")
    }

    private val afterAr = Regex("(?im)^\\s*\\**\\s*(ORIGINAL|SPEAKER|WHY|SLANG)\\s*\\**\\s*[:：]")
    private val arTag = Regex("(?im)^\\s*\\**\\s*AR\\s*\\**\\s*[:：]")

    /** True when the streamed text already contains a tag after AR, i.e. the translation is final. */
    fun isArComplete(raw: String): Boolean {
        val ar = arTag.find(raw) ?: return false
        return afterAr.containsMatchIn(raw.substring(ar.range.last + 1))
    }

    fun isSkip(raw: String): Boolean = raw.trim().trimStart('*', ' ').uppercase().startsWith("SKIP")

    private val tag = Regex("^\\s*\\**\\s*(ORIGINAL|SPEAKER|AR|WHY|SLANG)\\s*\\**\\s*[:：]\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val separator = Regex("(?m)^\\s*-{3,}\\s*$")

    fun parse(raw: String): List<ParsedLine> = raw.split(separator).mapNotNull { parseBlock(it) }

    fun parseFirst(raw: String): ParsedLine? = parse(raw).firstOrNull()

    private fun parseBlock(block: String): ParsedLine? {
        val fields = LinkedHashMap<String, StringBuilder>()
        var current: String? = null
        for (line in block.lines()) {
            val m = tag.find(line)
            if (m != null) {
                val key = m.groupValues[1].uppercase()
                current = key
                fields[key] = StringBuilder(m.groupValues[2].trim())
            } else if (current != null && line.isNotBlank()) {
                fields[current]?.append(' ')?.append(line.trim())
            }
        }
        if (fields.isEmpty()) return null
        fun field(key: String): String {
            val v = fields[key]?.toString()?.trim().orEmpty()
            return if (v == "-" || v == "—" || v.equals("none", ignoreCase = true)) "" else v
        }
        val slang = field("SLANG").split('|').mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val term = part.substring(0, idx).trim()
            val meaning = part.substring(idx + 1).trim()
            if (term.isEmpty() || meaning.isEmpty()) null else SlangTerm(term, meaning)
        }
        return ParsedLine(
            original = field("ORIGINAL"),
            speaker = field("SPEAKER"),
            translation = field("AR"),
            explanation = field("WHY"),
            slang = slang,
        )
    }
}

object ChatPrompt {

    fun system(style: OutputStyle, transcript: String, characters: List<StoryCharacter> = emptyList()): String {
        val header = """
            You are the player's smart companion for the video game Grand Theft Auto VI, chatting in ${styleText(style)}.
            You help them understand the story, the characters and their relationships, the dialogue, slang, jokes, and American / Florida / Latino culture references.
            Below is the transcript of the dialogue the player has actually seen so far (oldest first), with translations. Treat it as your main source about the story.
            Do not spoil events beyond what the player has seen unless they explicitly ask for spoilers. If you are not sure about something, say so honestly instead of inventing it.
            Answer clearly and warmly. Use short paragraphs or bullet points. Keep English quotes short.
        """.trimIndent()
        val body = transcript.ifBlank { "(empty: the player has not translated any dialogue yet)" }
        val cast = if (characters.isEmpty()) "" else {
            "\n\nKNOWN CHARACTERS (from the dialogue so far):\n" + characters.joinToString("\n") { c ->
                "- ${c.name}: ${c.role}" + (if (c.relations.isNotBlank()) " | ${c.relations}" else "")
            }
        }
        return "$header$cast\n\nTRANSCRIPT:\n$body"
    }

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)

    /** Builds the transcript from the newest lines backwards until [maxChars]. */
    fun transcript(linesNewestFirst: List<LiveLine>, maxChars: Int): String {
        val picked = ArrayList<String>()
        var used = 0
        for (line in linesNewestFirst) {
            if (line.original.isBlank()) continue
            val speaker = if (line.speaker.isNotBlank()) "${line.speaker}: " else ""
            val entry = "[${timeFormat.format(Date(line.timestamp))}] $speaker\"${line.original}\" → ${line.bestTranslation}"
            if (used + entry.length > maxChars) break
            picked += entry
            used += entry.length + 1
        }
        return picked.asReversed().joinToString("\n")
    }
}

/** Character list and "previously on" recap, both built only from what the player has seen. */
object StoryPrompt {

    fun charactersSystem(style: OutputStyle): String = """
        You build a character guide for a player of Grand Theft Auto VI, using ONLY the dialogue transcript provided (what the player has seen so far).
        Write in ${styleText(style)}. Keep character names in their original Latin spelling.
        List the characters who speak or are clearly mentioned, most important first (max 12).
        For each, reply in EXACTLY this format, blocks separated by a line containing only ---:
        NAME: <name as used in the game>
        ROLE: <who they are, one sentence>
        RELATION: <their relation to the main characters, one sentence, or ->
        LATEST: <the latest thing that happened with them in the transcript, one sentence>
        Do not invent facts that are not supported by the transcript. No spoilers beyond it.
    """.trimIndent()

    fun recapSystem(style: OutputStyle): String = """
        You write a short "previously on" recap for a player returning to Grand Theft Auto VI.
        Use ONLY the dialogue transcript provided. Write in ${styleText(style)}.
        Give 4 to 7 short bullet points (start each with "• ") covering what happened most recently and what the current goal seems to be.
        Mention characters by name. No spoilers beyond the transcript. No introduction, just the bullets.
    """.trimIndent()

    fun user(transcript: String): String = "TRANSCRIPT (oldest first):\n$transcript"

    private val tag = Regex("^\\s*\\**\\s*(NAME|ROLE|RELATION|LATEST)\\s*\\**\\s*[:：]\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val separator = Regex("(?m)^\\s*-{3,}\\s*$")

    fun parseCharacters(raw: String): List<StoryCharacter> = raw.split(separator).mapNotNull { block ->
        val fields = HashMap<String, String>()
        for (line in block.lines()) {
            val m = tag.find(line) ?: continue
            fields[m.groupValues[1].uppercase()] = m.groupValues[2].trim()
        }
        val name = fields["NAME"].orEmpty().trim('*', ' ')
        if (name.isEmpty()) {
            null
        } else {
            fun f(k: String) = fields[k].orEmpty().let { if (it == "-") "" else it }
            StoryCharacter(name = name, role = f("ROLE"), relations = f("RELATION"), latest = f("LATEST"))
        }
    }
}

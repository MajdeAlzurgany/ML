package ly.majde.gtatranslator.engine

import ly.majde.gtatranslator.data.DetailLevel
import ly.majde.gtatranslator.data.GeminiException
import ly.majde.gtatranslator.data.OutputStyle
import org.json.JSONArray
import org.json.JSONObject

data class ParsedItem(
    val original: String,
    val speaker: String,
    val translation: String,
    val explanation: String,
    val slang: List<SlangTerm>,
    val kind: String,
)

object Prompts {

    private fun styleText(style: OutputStyle) = when (style) {
        OutputStyle.LIBYAN ->
            "natural Libyan Arabic dialect (Tripoli style), written in Arabic script, the way a Libyan friend would say it"
        OutputStyle.MSA -> "clear, natural Modern Standard Arabic"
    }

    private fun detailText(detail: DetailLevel) = when (detail) {
        DetailLevel.SHORT -> "one or two short sentences"
        DetailLevel.DETAILED -> "two to four sentences"
    }

    fun system(style: OutputStyle, detail: DetailLevel): String = """
        You are a real-time companion translator for the video game Grand Theft Auto VI
        (set in Leonida, Rockstar's fictional Florida, including Vice City; lead characters Jason and Lucia).
        The player is an Arabic speaker from Libya who plays on a console while you help through their phone.

        The input is one of: OCR text of on-screen subtitles (may contain OCR mistakes and HUD noise),
        a photo of a TV screen, or a short audio clip of game audio (voices mixed with music, gunfire, traffic).

        For every spoken line or meaningful on-screen sentence you find, output one item:
        - "original": the exact English (or Spanish) line, cleaned up. Fix obvious OCR errors (I/l, missing apostrophes, broken words). Never invent lines that are not present.
        - "speaker": the speaker's name only if it is shown or obvious, otherwise "".
        - "translation": the line in ${styleText(style)}. Keep character, place and brand names as they are. Preserve the tone (sarcasm, threats, jokes, flirting). For vulgar lines, convey the meaning with an equivalent Arabic register, never more graphic than the original.
        - "explanation": ${detailText(detail)} in the same Arabic variety explaining what the line really means: subtext, sarcasm, jokes, American / Florida / Latino cultural or pop-culture references, and why the character says it. If the line is plain, keep it very short.
        - "slang": slang words, idioms, Spanish words, abbreviations or references from the line, each with a short Arabic meaning. Empty list if none.
        - "kind": "dialogue" for spoken lines or subtitles, "objective" for mission instructions or help text, "other" for any other meaningful text.

        Ignore money counters, timers, button prompts such as "Press X", menu labels, stray single words and anything unreadable.
        In audio, ignore music and song lyrics completely: only process spoken dialogue.
        If nothing meaningful is present, return {"items": []}.
        Respond only with JSON matching the schema.
    """.trimIndent()

    fun deepSystem(style: OutputStyle): String = """
        You are an expert in American English slang, Florida and Latino culture, and the Grand Theft Auto series.
        Explain a line from Grand Theft Auto VI to an Arabic-speaking player in ${styleText(style)}.
        Be friendly and concise (about 120 words). Plain text only, no markdown headings; you may use short bullet points starting with "•".
        Do not reveal story spoilers beyond the line itself.
    """.trimIndent()

    fun deepUser(entry: TranslationEntry): String = buildString {
        append("Line: \"").append(entry.original).append("\"\n")
        if (entry.speaker.isNotBlank()) append("Speaker: ").append(entry.speaker).append('\n')
        append("Current translation: ").append(entry.translation).append("\n\n")
        append("Explain in more depth: the exact meaning, tone and subtext, any slang, idioms or wordplay, ")
        append("cultural references, and give one or two alternative ways to say it in Arabic.")
    }

    fun responseSchema(): JSONObject = JSONObject(SCHEMA)

    fun parseItems(raw: String): List<ParsedItem> {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val items: JSONArray = try {
            if (cleaned.startsWith("[")) JSONArray(cleaned)
            else JSONObject(cleaned).optJSONArray("items") ?: JSONArray()
        } catch (e: Exception) {
            throw GeminiException(GeminiException.Kind.PARSE, "Could not parse model output")
        }
        val result = mutableListOf<ParsedItem>()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val slang = mutableListOf<SlangTerm>()
            val slangArr = o.optJSONArray("slang")
            if (slangArr != null) {
                for (j in 0 until slangArr.length()) {
                    val s = slangArr.optJSONObject(j) ?: continue
                    val term = s.optString("term").trim()
                    if (term.isNotEmpty()) slang += SlangTerm(term, s.optString("meaning").trim())
                }
            }
            result += ParsedItem(
                original = o.optString("original").trim(),
                speaker = o.optString("speaker").trim(),
                translation = o.optString("translation").trim(),
                explanation = o.optString("explanation").trim(),
                slang = slang,
                kind = o.optString("kind", "dialogue").trim().ifEmpty { "dialogue" },
            )
        }
        return result
    }

    private const val SCHEMA = """
{
  "type": "OBJECT",
  "properties": {
    "items": {
      "type": "ARRAY",
      "items": {
        "type": "OBJECT",
        "properties": {
          "original": { "type": "STRING" },
          "speaker": { "type": "STRING" },
          "translation": { "type": "STRING" },
          "explanation": { "type": "STRING" },
          "slang": {
            "type": "ARRAY",
            "items": {
              "type": "OBJECT",
              "properties": {
                "term": { "type": "STRING" },
                "meaning": { "type": "STRING" }
              },
              "required": ["term", "meaning"]
            }
          },
          "kind": { "type": "STRING" }
        },
        "required": ["original", "translation", "explanation", "slang", "kind"],
        "propertyOrdering": ["original", "speaker", "translation", "explanation", "slang", "kind"]
      }
    }
  },
  "required": ["items"]
}
"""
}

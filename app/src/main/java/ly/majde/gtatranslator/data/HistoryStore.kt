package ly.majde.gtatranslator.data

import android.content.Context
import java.io.File
import ly.majde.gtatranslator.ai.Role
import ly.majde.gtatranslator.engine.ChatMessage
import ly.majde.gtatranslator.engine.LineStatus
import ly.majde.gtatranslator.engine.LiveLine
import ly.majde.gtatranslator.engine.SlangTerm
import ly.majde.gtatranslator.engine.Source
import org.json.JSONArray
import org.json.JSONObject

/** Persists translated lines and the story chat so context survives app restarts. */
class HistoryStore(context: Context) {

    private val file = File(context.filesDir, "history.json")

    fun load(): Pair<List<LiveLine>, List<ChatMessage>> = try {
        if (!file.exists()) {
            emptyList<LiveLine>() to emptyList<ChatMessage>()
        } else {
            val root = JSONObject(file.readText())
            val lines = root.optJSONArray("lines")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { lineFromJson(it) } }
            }.orEmpty()
            val chat = root.optJSONArray("chat")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { chatFromJson(it) } }
            }.orEmpty()
            lines to chat
        }
    } catch (e: Exception) {
        emptyList<LiveLine>() to emptyList<ChatMessage>()
    }

    @Synchronized
    fun save(lines: List<LiveLine>, chat: List<ChatMessage>) {
        val root = JSONObject()
            .put("version", 1)
            .put("lines", JSONArray().apply { lines.forEach { put(lineToJson(it)) } })
            .put("chat", JSONArray().apply { chat.forEach { put(chatToJson(it)) } })
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(root.toString())
            tmp.delete()
        }
    }

    private fun lineToJson(l: LiveLine): JSONObject = JSONObject()
        .put("id", l.id)
        .put("source", l.source.name)
        .put("original", l.original)
        .put("speaker", l.speaker)
        .put("draft", l.draft)
        .put("translation", l.translation)
        .put("explanation", l.explanation)
        .put("slang", JSONArray().apply { l.slang.forEach { put(JSONObject().put("t", it.term).put("m", it.meaning)) } })
        .put("status", l.status.name)
        .put("provider", l.provider)
        .put("ts", l.timestamp)

    private fun lineFromJson(o: JSONObject): LiveLine? {
        val original = o.optString("original")
        if (original.isBlank()) return null
        val slangArr = o.optJSONArray("slang")
        val slang = if (slangArr == null) emptyList() else (0 until slangArr.length()).mapNotNull { i ->
            slangArr.optJSONObject(i)?.let { SlangTerm(it.optString("t"), it.optString("m")) }
        }
        val status = enumOr(o.optString("status"), LineStatus.DONE).let {
            // Lines that were mid-translation when the app closed keep their draft.
            if (it == LineStatus.DRAFT || it == LineStatus.REFINING) LineStatus.OFFLINE else it
        }
        return LiveLine(
            id = o.optLong("id"),
            source = enumOr(o.optString("source"), Source.CAMERA),
            original = original,
            speaker = o.optString("speaker"),
            draft = o.optString("draft"),
            translation = o.optString("translation"),
            explanation = o.optString("explanation"),
            slang = slang,
            status = status,
            provider = o.optString("provider"),
            timestamp = o.optLong("ts", System.currentTimeMillis()),
        )
    }

    private fun chatToJson(m: ChatMessage): JSONObject = JSONObject()
        .put("id", m.id)
        .put("role", m.role.name)
        .put("text", m.text)
        .put("quote", m.quote ?: JSONObject.NULL)
        .put("provider", m.provider)
        .put("ts", m.timestamp)

    private fun chatFromJson(o: JSONObject): ChatMessage? {
        val text = o.optString("text")
        if (text.isBlank()) return null
        return ChatMessage(
            id = o.optLong("id"),
            role = enumOr(o.optString("role"), Role.USER),
            text = text,
            quote = if (o.isNull("quote")) null else o.optString("quote").takeIf { it.isNotBlank() },
            provider = o.optString("provider"),
            timestamp = o.optLong("ts", System.currentTimeMillis()),
        )
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default
}

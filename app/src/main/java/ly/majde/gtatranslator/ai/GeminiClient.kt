package ly.majde.gtatranslator.ai

import android.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gemini Developer API client (REST v1beta) with SSE streaming, images, audio and
 * optional Google Search grounding. Falls back automatically when a model rejects
 * a feature (thinking config, search tool).
 */
class GeminiClient(private val http: OkHttpClient) : LlmClient {

    override val providerName: String = "Gemini"

    private val noThinkingConfig: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var searchUnsupported = false

    override fun stream(
        apiKey: String,
        model: String,
        system: String,
        messages: List<AiMessage>,
        options: LlmOptions,
    ): Flow<String> = flow {
        val m = normalizeModel(model)
        var sendThinking = m !in noThinkingConfig && thinkingConfigFor(m) != null
        var search = options.webSearch && !searchUnsupported
        var retries = 0
        while (true) {
            try {
                http.sseFlow(
                    request = buildRequest(apiKey, m, system, messages, options, sendThinking, search),
                    parseError = { c, b, h -> parseError(c, b, h) },
                    onData = { d -> parseChunk(d) },
                ).collect { emit(it) }
                return@flow
            } catch (e: AiException) {
                val msg = e.message.orEmpty()
                if (retries < 3 && e.kind == AiException.Kind.BAD_REQUEST) {
                    if (sendThinking && msg.contains("thinking", ignoreCase = true)) {
                        noThinkingConfig += m
                        sendThinking = false
                        retries++
                        continue
                    }
                    if (search && (msg.contains("search", true) || msg.contains("tool", true) || msg.contains("grounding", true))) {
                        searchUnsupported = true
                        search = false
                        retries++
                        continue
                    }
                }
                if (search && e.kind == AiException.Kind.RATE_LIMIT && retries < 3) {
                    // Grounding has its own quota; answer without search instead of failing.
                    search = false
                    retries++
                    continue
                }
                throw e
            }
        }
    }

    override suspend fun listModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/models?pageSize=1000")
            .header("x-goog-api-key", apiKey)
            .get()
            .build()
        val (code, payload) = http.executeForText(request)
        val (body, headers) = payload
        if (code !in 200..299) throw parseError(code, body, headers)
        val models = JSONObject(body).optJSONArray("models") ?: JSONArray()
        val names = mutableListOf<String>()
        for (i in 0 until models.length()) {
            val o = models.optJSONObject(i) ?: continue
            val name = o.optString("name").removePrefix("models/")
            val methods = o.optJSONArray("supportedGenerationMethods") ?: continue
            val canGenerate = (0 until methods.length()).any { methods.optString(it) == "generateContent" }
            val excluded = EXCLUDED_HINTS.any { name.contains(it) }
            if (canGenerate && name.startsWith("gemini") && !excluded) names += name
        }
        names.distinct().sortedWith(compareBy<String>({ !it.endsWith("-latest") }, { !it.contains("flash") }, { it }))
    }

    private fun buildRequest(
        apiKey: String,
        model: String,
        system: String,
        messages: List<AiMessage>,
        options: LlmOptions,
        sendThinking: Boolean,
        search: Boolean,
    ): Request {
        val contents = JSONArray()
        for (msg in messages) {
            val parts = JSONArray().put(JSONObject().put("text", msg.text))
            msg.imageJpeg?.let { parts.put(inline("image/jpeg", it)) }
            msg.audioWav?.let { parts.put(inline("audio/wav", it)) }
            contents.put(JSONObject().put("role", if (msg.role == Role.USER) "user" else "model").put("parts", parts))
        }
        val generationConfig = JSONObject().put("maxOutputTokens", options.maxTokens)
        if (sendThinking) thinkingConfigFor(model)?.let { generationConfig.put("thinkingConfig", it) }

        val safety = JSONArray()
        for (category in SAFETY_CATEGORIES) {
            safety.put(JSONObject().put("category", category).put("threshold", "BLOCK_NONE"))
        }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("safetySettings", safety)
            .put("generationConfig", generationConfig)
        if (search) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))

        return Request.Builder()
            .url("$BASE_URL/models/$model:streamGenerateContent?alt=sse")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
    }

    private fun inline(mime: String, data: ByteArray): JSONObject =
        JSONObject().put(
            "inlineData",
            JSONObject().put("mimeType", mime).put("data", Base64.encodeToString(data, Base64.NO_WRAP))
        )

    private fun thinkingConfigFor(model: String): JSONObject? {
        val m = model.lowercase()
        return when {
            m.startsWith("gemma") -> null
            m.contains("gemini-1.") || m.contains("gemini-2.0") -> null
            m.contains("gemini-2.5") -> if (m.contains("pro")) null else JSONObject().put("thinkingBudget", 0)
            else -> JSONObject().put("thinkingLevel", "low")
        }
    }

    private fun parseChunk(data: String): String? {
        val root = try {
            JSONObject(data)
        } catch (e: Exception) {
            return null
        }
        root.optJSONObject("error")?.let { err ->
            throw AiException(AiException.Kind.SERVER, err.optString("message", "Stream error"))
        }
        val blockReason = root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
        if (blockReason.isNotEmpty()) throw AiException(AiException.Kind.BLOCKED, blockReason)
        val candidate = root.optJSONArray("candidates")?.optJSONObject(0) ?: return null
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
        val sb = StringBuilder()
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                if (p.optBoolean("thought", false)) continue
                if (p.has("text")) sb.append(p.optString("text"))
            }
        }
        if (sb.isEmpty() && candidate.optString("finishReason") in BLOCK_REASONS) {
            throw AiException(AiException.Kind.BLOCKED, candidate.optString("finishReason"))
        }
        return sb.toString()
    }

    private fun parseError(code: Int, body: String, headers: Headers): AiException {
        val err = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
        val message = err?.optString("message").orEmpty().ifBlank { "HTTP $code" }
        val status = err?.optString("status").orEmpty()
        val kind = when {
            code == 429 || status == "RESOURCE_EXHAUSTED" -> AiException.Kind.RATE_LIMIT
            message.contains("location is not supported", ignoreCase = true) -> AiException.Kind.REGION
            message.contains("API key", ignoreCase = true) -> AiException.Kind.INVALID_KEY
            code == 401 || code == 403 -> AiException.Kind.INVALID_KEY
            code == 404 -> AiException.Kind.MODEL_NOT_FOUND
            code >= 500 -> AiException.Kind.SERVER
            else -> AiException.Kind.BAD_REQUEST
        }
        var retryAfter: Long? = headers["retry-after"]?.toDoubleOrNull()?.let { (it * 1000).toLong() }
        val details = err?.optJSONArray("details")
        if (retryAfter == null && details != null) {
            for (i in 0 until details.length()) {
                val delay = details.optJSONObject(i)?.optString("retryDelay").orEmpty()
                val seconds = delay.removeSuffix("s").toDoubleOrNull() ?: continue
                retryAfter = (seconds * 1000).toLong()
                break
            }
        }
        return AiException(kind, message, retryAfter, daily = body.contains("PerDay"))
    }

    private fun normalizeModel(model: String) = model.trim().removePrefix("models/").ifBlank { DEFAULT_MODEL }

    companion object {
        const val DEFAULT_MODEL = "gemini-flash-latest"
        const val FAST_MODEL = "gemini-flash-lite-latest"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val SAFETY_CATEGORIES = listOf(
            "HARM_CATEGORY_HARASSMENT",
            "HARM_CATEGORY_HATE_SPEECH",
            "HARM_CATEGORY_SEXUALLY_EXPLICIT",
            "HARM_CATEGORY_DANGEROUS_CONTENT",
        )
        private val BLOCK_REASONS = setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII")
        private val EXCLUDED_HINTS = listOf("tts", "image", "embedding", "live", "native-audio", "robotics", "computer-use")
    }
}

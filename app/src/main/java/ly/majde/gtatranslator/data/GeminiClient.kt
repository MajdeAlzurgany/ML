package ly.majde.gtatranslator.data

import android.util.Base64
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/** One piece of a multimodal prompt. */
sealed interface Part {
    data class Text(val text: String) : Part
    class Inline(val mimeType: String, val data: ByteArray) : Part
}

class GeminiException(
    val kind: Kind,
    message: String,
    val retryAfterMs: Long? = null,
    val dailyQuota: Boolean = false,
) : Exception(message) {
    enum class Kind { INVALID_KEY, RATE_LIMIT, BLOCKED, NETWORK, SERVER, MODEL_NOT_FOUND, REGION, BAD_REQUEST, PARSE }
}

/**
 * Minimal, dependency-light client for the Gemini Developer API (REST, v1beta).
 * Uses structured JSON output, permissive safety settings (the game is rated M and its
 * dialogue is full of profanity) and automatic retry / capability fallback.
 */
class GeminiClient(private val http: OkHttpClient) {

    /** Models that rejected our thinkingConfig, so we stop sending it to them. */
    private val noThinkingConfig: MutableSet<String> = ConcurrentHashMap.newKeySet()

    suspend fun generate(
        apiKey: String,
        model: String,
        systemPrompt: String,
        parts: List<Part>,
        responseSchema: JSONObject?,
    ): String = withContext(Dispatchers.IO) {
        val m = normalizeModel(model)
        var sendThinking = m !in noThinkingConfig && thinkingConfigFor(m) != null
        var attempt = 0
        var result: String? = null
        while (result == null) {
            val body = buildBody(m, systemPrompt, parts, responseSchema, sendThinking)
            val request = Request.Builder()
                .url("$BASE_URL/models/$m:generateContent")
                .header("x-goog-api-key", apiKey)
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build()
            val (code, text) = execute(request)
            if (code in 200..299) {
                result = extractText(text)
                continue
            }

            val error = parseError(code, text)
            if (code == 400 && sendThinking && error.message.orEmpty().contains("thinking", ignoreCase = true)) {
                noThinkingConfig += m
                sendThinking = false
                continue
            }
            if (error.kind == GeminiException.Kind.SERVER && attempt < 2) {
                attempt++
                delay(1_500L * attempt)
                continue
            }
            throw error
        }
        requireNotNull(result)
    }

    /** Lists Gemini models that support generateContent (useful to validate a key too). */
    suspend fun listModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/models?pageSize=1000")
            .header("x-goog-api-key", apiKey)
            .get()
            .build()
        val (code, text) = execute(request)
        if (code !in 200..299) throw parseError(code, text)
        val models = JSONObject(text).optJSONArray("models") ?: JSONArray()
        val names = mutableListOf<String>()
        for (i in 0 until models.length()) {
            val o = models.optJSONObject(i) ?: continue
            val name = o.optString("name").removePrefix("models/")
            val methods = o.optJSONArray("supportedGenerationMethods") ?: continue
            val canGenerate = (0 until methods.length()).any { methods.optString(it) == "generateContent" }
            val excluded = EXCLUDED_MODEL_HINTS.any { name.contains(it) }
            if (canGenerate && name.startsWith("gemini") && !excluded) names += name
        }
        names.distinct().sortedWith(
            compareBy<String>({ !it.endsWith("-latest") }, { !it.contains("flash") }, { it })
        )
    }

    private fun buildBody(
        model: String,
        systemPrompt: String,
        parts: List<Part>,
        responseSchema: JSONObject?,
        sendThinking: Boolean,
    ): JSONObject {
        val partsJson = JSONArray()
        for (p in parts) {
            when (p) {
                is Part.Text -> partsJson.put(JSONObject().put("text", p.text))
                is Part.Inline -> partsJson.put(
                    JSONObject().put(
                        "inlineData",
                        JSONObject()
                            .put("mimeType", p.mimeType)
                            .put("data", Base64.encodeToString(p.data, Base64.NO_WRAP))
                    )
                )
            }
        }
        val generationConfig = JSONObject()
        if (responseSchema != null) {
            generationConfig.put("responseMimeType", "application/json")
            generationConfig.put("responseSchema", responseSchema)
        }
        if (sendThinking) thinkingConfigFor(model)?.let { generationConfig.put("thinkingConfig", it) }

        val safety = JSONArray()
        for (category in SAFETY_CATEGORIES) {
            safety.put(JSONObject().put("category", category).put("threshold", "BLOCK_NONE"))
        }
        return JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", partsJson)))
            .put("safetySettings", safety)
            .put("generationConfig", generationConfig)
    }

    /** Low latency matters more than deep reasoning for live subtitles. */
    private fun thinkingConfigFor(model: String): JSONObject? {
        val m = model.lowercase()
        return when {
            m.startsWith("gemma") -> null
            m.contains("gemini-1.") || m.contains("gemini-2.0") -> null
            m.contains("gemini-2.5") -> if (m.contains("pro")) null else JSONObject().put("thinkingBudget", 0)
            else -> JSONObject().put("thinkingLevel", "low") // Gemini 3+ and *-latest aliases
        }
    }

    private fun extractText(body: String): String {
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw GeminiException(GeminiException.Kind.PARSE, "Invalid JSON from API")
        }
        val blockReason = root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
        if (blockReason.isNotEmpty()) throw GeminiException(GeminiException.Kind.BLOCKED, blockReason)

        val candidate = root.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw GeminiException(GeminiException.Kind.PARSE, "No candidates")
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
        val sb = StringBuilder()
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                if (p.optBoolean("thought", false)) continue
                sb.append(p.optString("text", ""))
            }
        }
        if (sb.isBlank()) {
            val finish = candidate.optString("finishReason")
            if (finish in BLOCK_FINISH_REASONS) throw GeminiException(GeminiException.Kind.BLOCKED, finish)
            throw GeminiException(GeminiException.Kind.PARSE, "Empty response ($finish)")
        }
        return sb.toString()
    }

    private fun parseError(code: Int, body: String): GeminiException {
        val err = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
        val message = err?.optString("message").orEmpty().ifBlank { "HTTP $code" }
        val status = err?.optString("status").orEmpty()
        val kind = when {
            code == 429 || status == "RESOURCE_EXHAUSTED" -> GeminiException.Kind.RATE_LIMIT
            message.contains("location is not supported", ignoreCase = true) -> GeminiException.Kind.REGION
            message.contains("API key", ignoreCase = true) -> GeminiException.Kind.INVALID_KEY
            code == 401 || code == 403 -> GeminiException.Kind.INVALID_KEY
            code == 404 -> GeminiException.Kind.MODEL_NOT_FOUND
            code >= 500 -> GeminiException.Kind.SERVER
            else -> GeminiException.Kind.BAD_REQUEST
        }
        return GeminiException(
            kind = kind,
            message = message,
            retryAfterMs = if (kind == GeminiException.Kind.RATE_LIMIT) parseRetryDelay(err) else null,
            dailyQuota = kind == GeminiException.Kind.RATE_LIMIT && body.contains("PerDay"),
        )
    }

    private fun parseRetryDelay(err: JSONObject?): Long? {
        val details = err?.optJSONArray("details") ?: return null
        for (i in 0 until details.length()) {
            val delayText = details.optJSONObject(i)?.optString("retryDelay").orEmpty()
            val seconds = delayText.removeSuffix("s").toDoubleOrNull() ?: continue
            return (seconds * 1000).toLong().coerceIn(2_000L, 120_000L)
        }
        return null
    }

    private suspend fun execute(request: Request): Pair<Int, String> = try {
        http.newCall(request).await().use { response -> response.code to (response.body?.string() ?: "") }
    } catch (e: IOException) {
        throw GeminiException(GeminiException.Kind.NETWORK, e.message ?: "Network error")
    }

    private fun normalizeModel(model: String): String =
        model.trim().removePrefix("models/").ifBlank { AppSettings.DEFAULT_MODEL }

    private companion object {
        const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        val SAFETY_CATEGORIES = listOf(
            "HARM_CATEGORY_HARASSMENT",
            "HARM_CATEGORY_HATE_SPEECH",
            "HARM_CATEGORY_SEXUALLY_EXPLICIT",
            "HARM_CATEGORY_DANGEROUS_CONTENT",
        )
        val BLOCK_FINISH_REASONS = setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION")
        val EXCLUDED_MODEL_HINTS = listOf("tts", "image", "embedding", "live", "native-audio", "robotics", "computer-use")
    }
}

/** Suspends on an OkHttp call and cancels it if the coroutine is cancelled. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

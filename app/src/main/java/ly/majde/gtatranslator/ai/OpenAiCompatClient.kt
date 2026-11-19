package ly.majde.gtatranslator.ai

import android.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class Transcript(val text: String, val language: String)

/**
 * Client for OpenAI-compatible APIs (Groq). Streams chat completions over SSE and
 * transcribes speech with Whisper.
 */
class OpenAiCompatClient(
    override val providerName: String,
    private val baseUrl: String,
    private val http: OkHttpClient,
) : LlmClient {

    /** Models that rejected our reasoning parameters. */
    private val noReasoningParams: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun stream(
        apiKey: String,
        model: String,
        system: String,
        messages: List<AiMessage>,
        options: LlmOptions,
    ): Flow<String> = flow {
        var sendReasoning = model !in noReasoningParams && reasoningParams(model) != null
        var retried = false
        while (true) {
            try {
                http.sseFlow(
                    request = chatRequest(apiKey, model, system, messages, options, sendReasoning),
                    parseError = { c, b, h -> parseError(c, b, h) },
                    onData = { d -> parseDelta(d) },
                ).collect { emit(it) }
                return@flow
            } catch (e: AiException) {
                val msg = e.message.orEmpty()
                val reasoningProblem = msg.contains("reasoning", ignoreCase = true) || msg.contains("include_reasoning", ignoreCase = true)
                if (!retried && sendReasoning && e.kind == AiException.Kind.BAD_REQUEST && reasoningProblem) {
                    noReasoningParams += model
                    sendReasoning = false
                    retried = true
                    continue
                }
                throw e
            }
        }
    }

    override suspend fun listModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/models")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        val (code, payload) = http.executeForText(request)
        val (body, headers) = payload
        if (code !in 200..299) throw parseError(code, body, headers)
        val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
        (0 until data.length()).mapNotNull { i -> data.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() } }
    }

    /** Whisper transcription (Groq). Filters segments that are probably music or silence. */
    suspend fun transcribe(apiKey: String, wav: ByteArray, model: String, prompt: String): Transcript? =
        withContext(Dispatchers.IO) {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "speech.wav", wav.toRequestBody("audio/wav".toMediaType()))
                .addFormDataPart("model", model)
                .addFormDataPart("response_format", "verbose_json")
                .addFormDataPart("temperature", "0")
                .addFormDataPart("prompt", prompt)
                .build()
            val request = Request.Builder()
                .url("$baseUrl/audio/transcriptions")
                .header("Authorization", "Bearer $apiKey")
                .post(body)
                .build()
            val (code, payload) = http.executeForText(request)
            val (text, headers) = payload
            if (code !in 200..299) throw parseError(code, text, headers)
            val root = JSONObject(text)
            val language = root.optString("language")
            val segments = root.optJSONArray("segments")
            val kept = StringBuilder()
            if (segments != null && segments.length() > 0) {
                for (i in 0 until segments.length()) {
                    val seg = segments.optJSONObject(i) ?: continue
                    val noSpeech = seg.optDouble("no_speech_prob", 0.0)
                    val logProb = seg.optDouble("avg_logprob", 0.0)
                    val compression = seg.optDouble("compression_ratio", 1.0)
                    val segText = seg.optString("text").trim()
                    val plausible = noSpeech < 0.6 && logProb > -1.1 && compression < 2.4
                    if (plausible && segText.isNotEmpty() && !isHallucination(segText)) {
                        if (kept.isNotEmpty()) kept.append(' ')
                        kept.append(segText)
                    }
                }
            } else {
                val whole = root.optString("text").trim()
                if (!isHallucination(whole)) kept.append(whole)
            }
            val result = kept.toString().trim()
            if (result.length < 2) null else Transcript(result, language)
        }

    private fun chatRequest(
        apiKey: String,
        model: String,
        system: String,
        messages: List<AiMessage>,
        options: LlmOptions,
        sendReasoning: Boolean,
    ): Request {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        for (m in messages) {
            val role = if (m.role == Role.USER) "user" else "assistant"
            val image = m.imageJpeg
            if (image != null) {
                val content = JSONArray()
                    .put(JSONObject().put("type", "text").put("text", m.text))
                    .put(
                        JSONObject().put("type", "image_url").put(
                            "image_url",
                            JSONObject().put("url", "data:image/jpeg;base64," + Base64.encodeToString(image, Base64.NO_WRAP))
                        )
                    )
                msgs.put(JSONObject().put("role", role).put("content", content))
            } else {
                msgs.put(JSONObject().put("role", role).put("content", m.text))
            }
        }
        val body = JSONObject()
            .put("model", model)
            .put("messages", msgs)
            .put("stream", true)
            .put("max_completion_tokens", options.maxTokens)
            .put("temperature", 0.3)
        if (sendReasoning) {
            val extra = reasoningParams(model)
            if (extra != null) {
                val keys = extra.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    body.put(k, extra.get(k))
                }
            }
        }
        return Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
    }

    /** Keeps reasoning models fast: we need translations, not essays. */
    private fun reasoningParams(model: String): JSONObject? {
        val m = model.lowercase()
        return when {
            m.contains("gpt-oss") -> JSONObject().put("reasoning_effort", "low").put("include_reasoning", false)
            m.contains("qwen3") || m.contains("qwen-3") -> JSONObject().put("reasoning_effort", "none")
            m.contains("r1") || m.contains("reason") -> JSONObject().put("reasoning_format", "hidden")
            else -> null
        }
    }

    private fun parseDelta(data: String): String? {
        val obj = try {
            JSONObject(data)
        } catch (e: Exception) {
            return null
        }
        obj.optJSONObject("error")?.let { err ->
            throw AiException(AiException.Kind.SERVER, err.optString("message", "Stream error"))
        }
        val delta = obj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: return null
        if (delta.isNull("content")) return null
        return delta.optString("content")
    }

    private fun parseError(code: Int, body: String, headers: Headers): AiException {
        val err = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
        val message = err?.optString("message").orEmpty().ifBlank { "HTTP $code" }
        val lower = message.lowercase()
        val kind = when {
            code == 429 -> AiException.Kind.RATE_LIMIT
            code == 401 -> AiException.Kind.INVALID_KEY
            code == 403 && (lower.contains("country") || lower.contains("region") || lower.contains("location")) -> AiException.Kind.REGION
            code == 403 -> AiException.Kind.INVALID_KEY
            code == 404 -> AiException.Kind.MODEL_NOT_FOUND
            code == 400 && (lower.contains("decommissioned") || lower.contains("does not exist") || lower.contains("not found")) ->
                AiException.Kind.MODEL_NOT_FOUND
            code >= 500 -> AiException.Kind.SERVER
            else -> AiException.Kind.BAD_REQUEST
        }
        val retryAfter = headers["retry-after"]?.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: parseTryAgainMs(message)
        val daily = lower.contains("per day") || lower.contains("(rpd)") || lower.contains("(tpd)") || lower.contains("(asd)")
        return AiException(kind, message, retryAfter, daily)
    }

    private fun isHallucination(text: String): Boolean {
        val t = text.lowercase().trim().trimEnd('.', '!', '?')
        return t.isEmpty() || t in WHISPER_HALLUCINATIONS
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        val WHISPER_HALLUCINATIONS = setOf(
            "you", "thank you", "thanks", "thank you for watching", "thanks for watching", "bye",
            "please subscribe", "subscribe", "music", "[music]", "(music)", "♪", "...", "oh", "uh", "um",
        )
    }
}

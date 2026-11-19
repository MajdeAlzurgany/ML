package ly.majde.gtatranslator.ai

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request

class AiException(
    val kind: Kind,
    message: String,
    val retryAfterMs: Long? = null,
    val daily: Boolean = false,
) : Exception(message) {
    enum class Kind {
        NO_PROVIDER, INVALID_KEY, RATE_LIMIT, BLOCKED, NETWORK, SERVER,
        MODEL_NOT_FOUND, REGION, BAD_REQUEST, PARSE,
    }
}

enum class Role { USER, ASSISTANT }

class AiMessage(
    val role: Role,
    val text: String,
    val imageJpeg: ByteArray? = null,
    val audioWav: ByteArray? = null,
)

data class LlmOptions(
    val maxTokens: Int = 600,
    val webSearch: Boolean = false,
)

/** A chat model provider that streams its answer as text deltas. */
interface LlmClient {
    val providerName: String

    fun stream(
        apiKey: String,
        model: String,
        system: String,
        messages: List<AiMessage>,
        options: LlmOptions,
    ): Flow<String>

    suspend fun listModels(apiKey: String): List<String>
}

/**
 * Executes [request] and reads a Server-Sent-Events body line by line.
 * [onData] turns each `data:` payload into a text delta (or null to skip).
 * The HTTP call is cancelled as soon as the collector stops.
 */
internal fun OkHttpClient.sseFlow(
    request: Request,
    parseError: (code: Int, body: String, headers: Headers) -> AiException,
    onData: (String) -> String?,
): Flow<String> = flow {
    val call = newCall(request)
    val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
    try {
        val response = try {
            call.execute()
        } catch (e: IOException) {
            throw AiException(AiException.Kind.NETWORK, e.message ?: "Network error")
        }
        response.use { resp ->
            if (!resp.isSuccessful) {
                val body = resp.body?.string().orEmpty()
                throw parseError(resp.code, body, resp.headers)
            }
            val source = resp.body?.source() ?: throw AiException(AiException.Kind.PARSE, "Empty body")
            while (true) {
                val line = try {
                    source.readUtf8Line()
                } catch (e: IOException) {
                    if (call.isCanceled()) null else throw AiException(AiException.Kind.NETWORK, e.message ?: "Stream interrupted")
                } ?: break
                if (!line.startsWith("data:")) continue
                val data = line.substring(5).trim()
                if (data == "[DONE]") break
                if (data.isEmpty()) continue
                val delta = onData(data)
                if (!delta.isNullOrEmpty()) emit(delta)
            }
        }
    } finally {
        handle?.dispose()
    }
}.flowOn(Dispatchers.IO)

/** Executes a plain (non-streaming) request on the caller's dispatcher; call from IO. */
internal fun OkHttpClient.executeForText(request: Request): Pair<Int, Pair<String, Headers>> = try {
    newCall(request).execute().use { resp -> resp.code to ((resp.body?.string() ?: "") to resp.headers) }
} catch (e: IOException) {
    throw AiException(AiException.Kind.NETWORK, e.message ?: "Network error")
}

private val tryAgainRegex = Regex("try again in\\s+(?:(\\d+)h)?\\s*(?:(\\d+)m)?\\s*(?:([\\d.]+)s)?", RegexOption.IGNORE_CASE)

/** Parses "Please try again in 1m2.5s" style hints into milliseconds. */
internal fun parseTryAgainMs(message: String): Long? {
    val m = tryAgainRegex.find(message) ?: return null
    val h = m.groupValues[1].toLongOrNull() ?: 0L
    val min = m.groupValues[2].toLongOrNull() ?: 0L
    val s = m.groupValues[3].toDoubleOrNull() ?: 0.0
    val total = h * 3_600_000L + min * 60_000L + (s * 1000).toLong()
    return if (total > 0) total else null
}

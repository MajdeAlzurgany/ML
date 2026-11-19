package ly.majde.gtatranslator.ai

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ly.majde.gtatranslator.data.AppSettings
import okhttp3.OkHttpClient

enum class AiTask { LINE, CHAT, VISION }

data class AiTarget(val client: LlmClient, val apiKey: String, val model: String) {
    val id: String get() = "${client.providerName}:$model"
    val label: String get() = "${client.providerName} · ${model.substringAfterLast('/')}"
}

sealed interface AiEvent {
    data class Started(val target: AiTarget) : AiEvent
    data class Delta(val text: String) : AiEvent
}

/**
 * Picks the best free model for each task and fails over automatically: when a model
 * hits its free quota it cools down and the next one (or the next provider) takes over.
 */
class AiRouter(private val settings: StateFlow<AppSettings>, http: OkHttpClient) {

    val groq = OpenAiCompatClient("Groq", GROQ_BASE_URL, http)
    val gemini = GeminiClient(http)

    private val cooldownUntil = ConcurrentHashMap<String, Long>()
    private val missingModels: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val catalogMutex = Mutex()

    @Volatile private var groqCatalog: List<String>? = null
    @Volatile private var groqCatalogKey = ""
    @Volatile private var groqCatalogAt = 0L

    val hasProvider: Boolean get() = settings.value.hasAnyKey

    fun stream(
        task: AiTask,
        system: (AiTarget) -> String,
        messages: (AiTarget) -> List<AiMessage>,
        options: LlmOptions,
    ): Flow<AiEvent> = flow {
        val candidates = targets(task)
        if (candidates.isEmpty()) {
            if (!settings.value.hasAnyKey) throw AiException(AiException.Kind.NO_PROVIDER, "No API key")
            throw AiException(AiException.Kind.RATE_LIMIT, "All free models are cooling down", retryAfterMs = soonestCooldownMs())
        }
        var lastError: AiException? = null
        for (target in candidates) {
            var started = false
            try {
                target.client.stream(target.apiKey, target.model, system(target), messages(target), options).collect { delta ->
                    if (!started) {
                        started = true
                        emit(AiEvent.Started(target))
                    }
                    emit(AiEvent.Delta(delta))
                }
                if (!started) emit(AiEvent.Started(target))
                return@flow
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                if (started) throw e
                lastError = e
                penalize(target, e)
            } catch (e: Exception) {
                if (started) throw e
                val wrapped = AiException(AiException.Kind.NETWORK, e.message ?: e.javaClass.simpleName)
                lastError = wrapped
                penalize(target, wrapped)
            }
        }
        throw lastError ?: AiException(AiException.Kind.SERVER, "No provider available")
    }

    /** Speech to text: Groq Whisper first (best quality), Gemini audio as fallback. */
    suspend fun transcribe(wav: ByteArray): Transcript? {
        val s = settings.value
        var lastError: AiException? = null
        if (s.groqKey.isNotBlank()) {
            for (model in WHISPER_MODELS) {
                val id = "Groq:$model"
                if ((cooldownUntil[id] ?: 0L) > now()) continue
                try {
                    return groq.transcribe(s.groqKey, wav, model, WHISPER_PROMPT)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AiException) {
                    lastError = e
                    penalize(AiTarget(groq, s.groqKey, model), e)
                    if (e.kind == AiException.Kind.INVALID_KEY) break
                }
            }
        }
        if (s.geminiKey.isNotBlank()) {
            for (model in geminiModelsFor(AiTask.LINE, s)) {
                val target = AiTarget(gemini, s.geminiKey, model)
                val t = now()
                if ((cooldownUntil[target.id] ?: 0L) > t || (cooldownUntil[gemini.providerName] ?: 0L) > t) continue
                try {
                    val text = StringBuilder()
                    gemini.stream(
                        apiKey = s.geminiKey,
                        model = model,
                        system = TRANSCRIBE_SYSTEM,
                        messages = listOf(AiMessage(Role.USER, "Transcribe this clip.", audioWav = wav)),
                        options = LlmOptions(maxTokens = 300),
                    ).collect { text.append(it) }
                    val result = text.toString().trim()
                    return if (result.isEmpty() || result.equals("NONE", ignoreCase = true)) null else Transcript(result, "")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AiException) {
                    lastError = e
                    penalize(target, e)
                }
            }
        }
        throw lastError ?: AiException(AiException.Kind.NO_PROVIDER, "No API key")
    }

    suspend fun targets(task: AiTask): List<AiTarget> {
        val s = settings.value
        val groqTargets = if (s.groqKey.isBlank()) emptyList() else groqModelsFor(task, s).map { AiTarget(groq, s.groqKey, it) }
        val geminiTargets = if (s.geminiKey.isBlank()) emptyList() else geminiModelsFor(task, s).map { AiTarget(gemini, s.geminiKey, it) }
        val ordered = when (task) {
            AiTask.LINE -> groqTargets + geminiTargets
            AiTask.CHAT, AiTask.VISION -> geminiTargets + groqTargets
        }
        val t = now()
        return ordered.filter { target ->
            (cooldownUntil[target.id] ?: 0L) <= t &&
                (cooldownUntil[target.client.providerName] ?: 0L) <= t &&
                target.id !in missingModels
        }
    }

    suspend fun groqModels(): List<String> = groqCatalog(settings.value.groqKey) ?: emptyList()

    private suspend fun groqModelsFor(task: AiTask, s: AppSettings): List<String> {
        val catalog = groqCatalog(s.groqKey)
        val prefs = when (task) {
            AiTask.LINE -> GROQ_LINE_PREFS
            AiTask.CHAT -> GROQ_CHAT_PREFS
            AiTask.VISION -> GROQ_VISION_PREFS
        }
        val base = if (catalog == null) {
            prefs
        } else {
            val preferred = prefs.filter { it in catalog }
            val extras = if (task == AiTask.VISION) catalog.filter { looksVision(it) } else catalog.filter { isChatModel(it) }
            preferred + extras
        }
        val pinned = s.groqModel.takeIf { it.isNotBlank() && task != AiTask.VISION }
        return (listOfNotNull(pinned) + base).distinct().take(MAX_TARGETS_PER_PROVIDER)
    }

    private fun geminiModelsFor(task: AiTask, s: AppSettings): List<String> = when (task) {
        AiTask.LINE -> listOf(GeminiClient.FAST_MODEL, s.geminiModel)
        AiTask.CHAT, AiTask.VISION -> listOf(s.geminiModel, GeminiClient.FAST_MODEL)
    }.filter { it.isNotBlank() }.distinct()

    private suspend fun groqCatalog(key: String): List<String>? {
        if (key.isBlank()) return null
        val cached = groqCatalog
        if (cached != null && groqCatalogKey == key && now() - groqCatalogAt < CATALOG_TTL_MS) return cached
        return catalogMutex.withLock {
            val again = groqCatalog
            if (again != null && groqCatalogKey == key && now() - groqCatalogAt < CATALOG_TTL_MS) return@withLock again
            try {
                val list = groq.listModels(key)
                groqCatalog = list
                groqCatalogKey = key
                groqCatalogAt = now()
                list
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun penalize(target: AiTarget, e: AiException) {
        val t = now()
        when (e.kind) {
            AiException.Kind.RATE_LIMIT -> {
                val wait = if (e.daily) DAILY_COOLDOWN_MS else (e.retryAfterMs ?: DEFAULT_COOLDOWN_MS).coerceIn(2_000L, 15 * 60_000L)
                cooldownUntil[target.id] = t + wait
            }
            AiException.Kind.MODEL_NOT_FOUND -> missingModels += target.id
            AiException.Kind.INVALID_KEY, AiException.Kind.REGION -> cooldownUntil[target.client.providerName] = t + 5 * 60_000L
            AiException.Kind.SERVER, AiException.Kind.NETWORK -> cooldownUntil[target.id] = t + 5_000L
            AiException.Kind.BAD_REQUEST -> cooldownUntil[target.id] = t + 60_000L
            else -> Unit
        }
    }

    private fun soonestCooldownMs(): Long {
        val t = now()
        return cooldownUntil.values.filter { it > t }.minOrNull()?.minus(t) ?: DEFAULT_COOLDOWN_MS
    }

    private fun now() = SystemClock.elapsedRealtime()

    private fun isChatModel(id: String): Boolean {
        val m = id.lowercase()
        return NON_CHAT_HINTS.none { m.contains(it) }
    }

    private fun looksVision(id: String): Boolean {
        val m = id.lowercase()
        return m.contains("llama-4") || m.contains("vision") || m.contains("-vl")
    }

    companion object {
        const val GROQ_BASE_URL = "https://api.groq.com/openai/v1"
        private const val CATALOG_TTL_MS = 30 * 60_000L
        private const val DEFAULT_COOLDOWN_MS = 20_000L
        private const val DAILY_COOLDOWN_MS = 60 * 60_000L
        private const val MAX_TARGETS_PER_PROVIDER = 6

        val WHISPER_MODELS = listOf("whisper-large-v3-turbo", "whisper-large-v3")
        private const val WHISPER_PROMPT =
            "Dialogue from the video game Grand Theft Auto VI, set in Leonida and Vice City. Characters: Jason, Lucia."
        private const val TRANSCRIBE_SYSTEM =
            "Transcribe only clearly spoken dialogue from this game audio clip, in its original language. " +
                "Ignore music, song lyrics and sound effects. Output only the transcript, or NONE if there is no speech."

        /** Fast, non-reasoning models first: live subtitles need speed. */
        private val GROQ_LINE_PREFS = listOf(
            "llama-3.3-70b-versatile",
            "moonshotai/kimi-k2-instruct-0905",
            "moonshotai/kimi-k2-instruct",
            "openai/gpt-oss-120b",
            "qwen/qwen3-32b",
            "meta-llama/llama-4-maverick-17b-128e-instruct",
            "meta-llama/llama-4-scout-17b-16e-instruct",
            "openai/gpt-oss-20b",
            "llama-3.1-8b-instant",
        )
        private val GROQ_CHAT_PREFS = listOf(
            "openai/gpt-oss-120b",
            "moonshotai/kimi-k2-instruct-0905",
            "moonshotai/kimi-k2-instruct",
            "llama-3.3-70b-versatile",
            "qwen/qwen3-32b",
            "meta-llama/llama-4-maverick-17b-128e-instruct",
        )
        private val GROQ_VISION_PREFS = listOf(
            "meta-llama/llama-4-maverick-17b-128e-instruct",
            "meta-llama/llama-4-scout-17b-16e-instruct",
        )
        private val NON_CHAT_HINTS = listOf(
            "whisper", "guard", "orpheus", "playai", "tts", "compound", "prompt", "embed", "safeguard",
        )
    }
}

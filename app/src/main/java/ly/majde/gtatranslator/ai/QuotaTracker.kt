package ly.majde.gtatranslator.ai

import android.os.SystemClock
import java.util.Calendar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.Headers

data class QuotaInfo(
    val id: String,
    val provider: String,
    val model: String,
    /** Requests left today, as reported by the provider (Groq sends this in headers). */
    val remaining: Int? = null,
    val limit: Int? = null,
    /** Requests this app sent today. */
    val usedToday: Int = 0,
    /** SystemClock.elapsedRealtime() until which the model is resting after a limit. */
    val cooldownUntil: Long = 0L,
    val lastUsedAt: Long = 0L,
)

/** Live view of the free quota of every model the app has used today. */
class QuotaTracker {

    private val _quotas = MutableStateFlow<Map<String, QuotaInfo>>(emptyMap())
    val quotas: StateFlow<Map<String, QuotaInfo>> = _quotas.asStateFlow()

    private val _active = MutableStateFlow<String?>(null)
    /** Id of the model that answered last. */
    val active: StateFlow<String?> = _active.asStateFlow()

    private var day = currentDay()

    fun onRequest(provider: String, model: String) {
        rollDay()
        val id = "$provider:$model"
        _quotas.update { map ->
            val q = map[id] ?: QuotaInfo(id, provider, model)
            map + (id to q.copy(usedToday = q.usedToday + 1, lastUsedAt = System.currentTimeMillis()))
        }
    }

    fun onSuccess(provider: String, model: String) {
        _active.value = "$provider:$model"
    }

    fun onHeaders(provider: String, model: String, headers: Headers) {
        val remaining = headers["x-ratelimit-remaining-requests"]?.toIntOrNull()
        val limit = headers["x-ratelimit-limit-requests"]?.toIntOrNull()
        if (remaining == null && limit == null) return
        val id = "$provider:$model"
        _quotas.update { map ->
            val q = map[id] ?: QuotaInfo(id, provider, model)
            map + (id to q.copy(remaining = remaining ?: q.remaining, limit = limit ?: q.limit))
        }
    }

    fun onCooldown(provider: String, model: String, until: Long) {
        val id = "$provider:$model"
        _quotas.update { map ->
            val q = map[id] ?: QuotaInfo(id, provider, model)
            map + (id to q.copy(cooldownUntil = until))
        }
    }

    fun isResting(q: QuotaInfo): Boolean = q.cooldownUntil > SystemClock.elapsedRealtime()

    private fun rollDay() {
        val today = currentDay()
        if (today != day) {
            day = today
            _quotas.update { map -> map.mapValues { (_, q) -> q.copy(usedToday = 0) } }
        }
    }

    private fun currentDay(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    }
}

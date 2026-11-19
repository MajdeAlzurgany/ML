package ly.majde.gtatranslator.engine

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ly.majde.gtatranslator.ai.AiEvent
import ly.majde.gtatranslator.ai.AiException
import ly.majde.gtatranslator.ai.AiMessage
import ly.majde.gtatranslator.ai.AiRouter
import ly.majde.gtatranslator.ai.AiTask
import ly.majde.gtatranslator.ai.LlmOptions
import ly.majde.gtatranslator.ai.Role
import ly.majde.gtatranslator.data.AppSettings

/**
 * "Ask about the story": a streaming chat whose context is the whole dialogue
 * transcript the player has seen, so it can explain characters, plot and lines.
 */
class ChatEngine(
    private val router: AiRouter,
    private val settings: StateFlow<AppSettings>,
    private val lines: StateFlow<List<LiveLine>>,
    private val story: StateFlow<StoryState>,
    private val scope: CoroutineScope,
    initial: List<ChatMessage>,
) {
    private val _messages = MutableStateFlow(initial)
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _streaming = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = _streaming.asStateFlow()

    private val ids = AtomicLong(initial.maxOfOrNull { it.id } ?: 0L)
    private var job: Job? = null

    fun send(text: String, quote: LiveLine? = null) {
        val question = text.trim()
        if (question.isEmpty() || job?.isActive == true) return
        val quoteText = quote?.let { q ->
            val speaker = if (q.speaker.isNotBlank()) "${q.speaker}: " else ""
            "$speaker${q.original}\n${q.bestTranslation}"
        }
        val user = ChatMessage(id = ids.incrementAndGet(), role = Role.USER, text = question, quote = quoteText)
        val reply = ChatMessage(id = ids.incrementAndGet(), role = Role.ASSISTANT, text = "", streaming = true)
        val history = _messages.value.filter { !it.streaming && it.error == null && it.text.isNotBlank() }.takeLast(HISTORY_MESSAGES)
        _messages.update { (it + user + reply).takeLast(MAX_MESSAGES) }
        _streaming.value = true

        job = scope.launch {
            val acc = StringBuilder()
            try {
                val s = settings.value
                router.stream(
                    task = AiTask.CHAT,
                    system = { target ->
                        val budget = if (target.client.providerName == "Gemini") GEMINI_TRANSCRIPT_CHARS else SMALL_TRANSCRIPT_CHARS
                        ChatPrompt.system(s.style, ChatPrompt.transcript(lines.value, budget), story.value.characters)
                    },
                    messages = { (history + user).map { it.toAiMessage() } },
                    options = LlmOptions(maxTokens = 1_200, webSearch = s.webSearch),
                ).collect { event ->
                    when (event) {
                        is AiEvent.Started -> update(reply.id) { it.copy(provider = event.target.label) }
                        is AiEvent.Delta -> {
                            acc.append(event.text)
                            val snapshot = acc.toString()
                            update(reply.id) { it.copy(text = snapshot) }
                        }
                    }
                }
                update(reply.id) { it.copy(streaming = false, text = acc.toString().trim()) }
            } catch (e: CancellationException) {
                update(reply.id) { it.copy(streaming = false, text = acc.toString().trim()) }
                throw e
            } catch (e: AiException) {
                update(reply.id) { it.copy(streaming = false, error = e.toNotice(), text = acc.toString().trim()) }
            } catch (e: Exception) {
                update(reply.id) { it.copy(streaming = false, error = Notice.NETWORK, text = acc.toString().trim()) }
            } finally {
                _streaming.value = false
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    fun clear() {
        job?.cancel()
        _messages.value = emptyList()
    }

    private fun ChatMessage.toAiMessage(): AiMessage {
        val content = if (role == Role.USER && quote != null) {
            "About this line from the game:\n\"\"\"\n$quote\n\"\"\"\n\n$text"
        } else {
            text
        }
        return AiMessage(role, content)
    }

    private fun update(id: Long, transform: (ChatMessage) -> ChatMessage) {
        _messages.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    private companion object {
        const val HISTORY_MESSAGES = 12
        const val MAX_MESSAGES = 300
        const val GEMINI_TRANSCRIPT_CHARS = 80_000
        const val SMALL_TRANSCRIPT_CHARS = 9_000
    }
}

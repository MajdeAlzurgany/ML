package ly.majde.gtatranslator.engine

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * "Learn English from the game": saved words and phrases reviewed with spaced
 * repetition (Leitner boxes). Knowing a card moves it to a longer interval.
 */
class WordsEngine(initial: List<SavedWord>) {

    private val _words = MutableStateFlow(initial)
    val words: StateFlow<List<SavedWord>> = _words.asStateFlow()

    private val ids = AtomicLong(initial.maxOfOrNull { it.id } ?: 0L)

    fun isSaved(term: String): Boolean = _words.value.any { it.term.equals(term.trim(), ignoreCase = true) }

    /** Saves a word (or toggles it off if it is already saved). Returns true if now saved. */
    fun toggle(term: String, meaning: String, line: LiveLine?): Boolean {
        val clean = term.trim()
        if (clean.isEmpty()) return false
        val existing = _words.value.firstOrNull { it.term.equals(clean, ignoreCase = true) }
        if (existing != null) {
            _words.update { list -> list.filterNot { it.id == existing.id } }
            return false
        }
        val word = SavedWord(
            id = ids.incrementAndGet(),
            term = clean,
            meaning = meaning.trim(),
            example = line?.original.orEmpty(),
            exampleTranslation = line?.bestTranslation.orEmpty(),
            due = System.currentTimeMillis(),
        )
        _words.update { listOf(word) + it }
        return true
    }

    fun remove(id: Long) {
        _words.update { list -> list.filterNot { it.id == id } }
    }

    fun due(now: Long = System.currentTimeMillis()): List<SavedWord> =
        _words.value.filter { it.due <= now }.sortedBy { it.due }

    fun grade(id: Long, knew: Boolean) {
        val now = System.currentTimeMillis()
        _words.update { list ->
            list.map { w ->
                if (w.id != id) {
                    w
                } else {
                    val box = if (knew) (w.box + 1).coerceAtMost(INTERVALS.lastIndex) else 0
                    val wait = if (knew) INTERVALS[box] else RETRY_MS
                    w.copy(box = box, due = now + wait, reviews = w.reviews + 1, correct = w.correct + if (knew) 1 else 0)
                }
            }
        }
    }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
        const val RETRY_MS = 10 * 60 * 1000L
        val INTERVALS = longArrayOf(0L, DAY, 3 * DAY, 7 * DAY, 16 * DAY, 35 * DAY)
    }
}

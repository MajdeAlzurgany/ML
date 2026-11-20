package ly.majde.gtatranslator.camera

import android.os.SystemClock
import ly.majde.gtatranslator.util.TextSimilarity

sealed interface TrackEvent {
    /** A new subtitle appeared. */
    data class NewLine(val text: String) : TrackEvent

    /** The subtitle on screen is the same one, but now read more completely. */
    data class Amend(val text: String) : TrackEvent
}

/**
 * Turns a noisy stream of OCR readings into subtitle events that follow what is on the TV:
 *  - a reading must be stable for [stableFrames] frames (so fade-in animations are skipped);
 *  - while a subtitle stays on screen ("session"), partial or noisy re-reads of it are ignored,
 *    and a more complete read (e.g. both lines of a two-line subtitle) amends it in place;
 *  - the session ends when the subtitle disappears, so the next one is always new.
 */
class SubtitleTracker(
    private val stableFrames: Int = 3,
    private val sameLineSimilarity: Double = 0.85,
    private val repeatSimilarity: Double = 0.82,
    private val repeatWindowMs: Long = 20_000L,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private enum class Relation { SAME, SUPERSET, DIFFERENT }

    private var candidate = ""
    private var candidateRaw = ""
    private var count = 0
    private var emitted = false
    private var sessionLine = ""
    private var blankStreak = 0
    private val recent = ArrayDeque<Pair<String, Long>>()

    @Synchronized
    fun offer(raw: String): TrackEvent? {
        val norm = TextSimilarity.normalize(raw)
        if (norm.length < MIN_CHARS) {
            blankStreak++
            if (blankStreak >= SESSION_GAP_FRAMES) sessionLine = ""
            resetCandidate()
            return null
        }
        blankStreak = 0

        if (candidate.isNotEmpty() && TextSimilarity.similarity(norm, candidate) >= sameLineSimilarity) {
            count++
            if (raw.length > candidateRaw.length) {
                candidateRaw = raw
                candidate = norm
            }
        } else {
            candidate = norm
            candidateRaw = raw
            count = 1
            emitted = false
        }
        if (emitted || count < stableFrames) return null
        emitted = true

        if (sessionLine.isNotEmpty()) {
            when (relation(sessionLine, candidate)) {
                Relation.SAME -> return null
                Relation.SUPERSET -> {
                    sessionLine = candidate
                    remember(candidate)
                    return TrackEvent.Amend(candidateRaw.trim())
                }
                Relation.DIFFERENT -> Unit
            }
        }

        val now = clock()
        while (recent.isNotEmpty() && now - recent.first().second > repeatWindowMs) recent.removeFirst()
        sessionLine = candidate
        if (recent.any { TextSimilarity.similarity(it.first, candidate) >= repeatSimilarity }) return null
        remember(candidate)
        return TrackEvent.NewLine(candidateRaw.trim())
    }

    @Synchronized
    fun reset() {
        resetCandidate()
        sessionLine = ""
        blankStreak = 0
        recent.clear()
    }

    private fun remember(norm: String) {
        recent.addLast(norm to clock())
        while (recent.size > MAX_RECENT) recent.removeFirst()
    }

    private fun relation(old: String, new: String): Relation {
        if (TextSimilarity.similarity(old, new) >= 0.8) return Relation.SAME
        val a = tokens(old)
        val b = tokens(new)
        if (a.isEmpty() || b.isEmpty()) return Relation.DIFFERENT
        val common = a.intersect(b).size.toDouble()
        val oldCovered = common / a.size
        val newCovered = common / b.size
        return when {
            oldCovered >= 0.8 && b.size >= a.size * 1.3 -> Relation.SUPERSET
            newCovered >= 0.75 -> Relation.SAME
            else -> Relation.DIFFERENT
        }
    }

    private fun tokens(s: String): Set<String> = s.split(' ').filter { it.length > 1 }.toSet()

    private fun resetCandidate() {
        candidate = ""
        candidateRaw = ""
        count = 0
        emitted = false
    }

    private companion object {
        const val MIN_CHARS = 4
        const val MAX_RECENT = 12
        /** ~0.5 s of empty readings means the subtitle left the screen. */
        const val SESSION_GAP_FRAMES = 3
    }
}

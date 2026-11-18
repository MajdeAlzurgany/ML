package ly.majde.gtatranslator.camera

import android.os.SystemClock
import ly.majde.gtatranslator.util.TextSimilarity

/**
 * Turns a noisy stream of OCR readings into "a new subtitle line appeared" events.
 * A reading must be stable for [stableFrames] consecutive frames, and lines seen
 * recently are not emitted again (OCR flicker, subtitles that stay on screen).
 */
class SubtitleTracker(
    private val stableFrames: Int = 2,
    private val sameLineSimilarity: Double = 0.85,
    private val repeatSimilarity: Double = 0.8,
    private val repeatWindowMs: Long = 25_000L,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private var candidate = ""
    private var candidateRaw = ""
    private var count = 0
    private var emitted = false
    private val recent = ArrayDeque<Pair<String, Long>>()

    /** Returns the text to translate when a new stable subtitle is detected, else null. */
    @Synchronized
    fun offer(raw: String): String? {
        val norm = TextSimilarity.normalize(raw)
        if (norm.length < MIN_CHARS) {
            resetCandidate()
            return null
        }
        if (candidate.isNotEmpty() && TextSimilarity.similarity(norm, candidate) >= sameLineSimilarity) {
            count++
            // Keep the most complete reading of the same line.
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

        val now = clock()
        while (recent.isNotEmpty() && now - recent.first().second > repeatWindowMs) recent.removeFirst()
        if (recent.any { TextSimilarity.similarity(it.first, candidate) >= repeatSimilarity }) return null
        recent.addLast(candidate to now)
        while (recent.size > MAX_RECENT) recent.removeFirst()
        return candidateRaw.trim()
    }

    @Synchronized
    fun reset() {
        resetCandidate()
        recent.clear()
    }

    private fun resetCandidate() {
        candidate = ""
        candidateRaw = ""
        count = 0
        emitted = false
    }

    private companion object {
        const val MIN_CHARS = 4
        const val MAX_RECENT = 10
    }
}

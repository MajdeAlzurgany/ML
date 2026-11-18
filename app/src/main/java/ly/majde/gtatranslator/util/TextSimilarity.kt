package ly.majde.gtatranslator.util

/** Fuzzy text helpers used to de-duplicate noisy OCR / transcript readings. */
object TextSimilarity {

    private val nonWord = Regex("[^\\p{L}\\p{N}' ]+")
    private val spaces = Regex("\\s+")

    fun normalize(s: String): String =
        s.lowercase()
            .replace(nonWord, " ")
            .replace(spaces, " ")
            .trim()

    /** Levenshtein similarity in [0, 1]; inputs are expected to be normalized. */
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val x = a.take(MAX_LEN)
        val y = b.take(MAX_LEN)
        var prev = IntArray(y.length + 1) { it }
        var curr = IntArray(y.length + 1)
        for (i in 1..x.length) {
            curr[0] = i
            for (j in 1..y.length) {
                val cost = if (x[i - 1] == y[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        val distance = prev[y.length]
        return 1.0 - distance.toDouble() / maxOf(x.length, y.length)
    }

    private const val MAX_LEN = 300
}

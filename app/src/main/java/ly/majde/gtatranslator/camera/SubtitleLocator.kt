package ly.majde.gtatranslator.camera

import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import ly.majde.gtatranslator.data.NormRect

/** A line of text found on a full-frame scan, in preview-view pixels. */
data class ScanLine(val rect: RectF, val letters: Int)

/**
 * Finds where subtitles are: game subtitles are long, horizontally centered lines in the
 * lower part of the TV picture. Picks the best candidate, groups the lines that belong to
 * it, and returns a generous region so longer or two-line subtitles still fit.
 */
object SubtitleLocator {

    fun suggest(lines: List<ScanLine>, viewWidth: Float, viewHeight: Float): NormRect? {
        if (viewWidth <= 0f || viewHeight <= 0f) return null
        val candidates = lines.filter { it.letters >= 6 && it.rect.width() > viewWidth * 0.10f }
        if (candidates.isEmpty()) return null

        val best = candidates.maxByOrNull { line ->
            val cx = line.rect.centerX() / viewWidth
            val cy = line.rect.centerY() / viewHeight
            val centered = (1.2f - abs(cx - 0.5f) * 2f).coerceAtLeast(0.2f)
            val lower = 0.6f + cy
            line.letters * centered * lower
        } ?: return null

        val lineHeight = max(best.rect.height(), 8f)
        val group = candidates.filter { other ->
            val verticalGap = min(abs(other.rect.top - best.rect.bottom), abs(best.rect.top - other.rect.bottom))
            val sameRow = other === best
            val near = verticalGap < lineHeight * 1.6f || (other.rect.top <= best.rect.bottom && other.rect.bottom >= best.rect.top)
            val aligned = abs(other.rect.centerX() - best.rect.centerX()) < viewWidth * 0.25f
            sameRow || (near && aligned)
        }
        val union = RectF(best.rect)
        group.forEach { union.union(it.rect) }

        val cx = union.centerX()
        val halfWidth = max(union.width() * 0.8f, viewWidth * 0.32f)
        val left = (cx - halfWidth).coerceAtLeast(0f)
        val right = (cx + halfWidth).coerceAtMost(viewWidth)
        val top = (union.top - lineHeight * 1.3f).coerceAtLeast(0f)
        val bottom = (union.bottom + lineHeight * 0.7f).coerceAtMost(viewHeight)

        return NormRect(left / viewWidth, top / viewHeight, right / viewWidth, bottom / viewHeight).sanitized()
    }
}

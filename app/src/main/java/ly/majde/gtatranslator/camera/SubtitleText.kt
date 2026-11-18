package ly.majde.gtatranslator.camera

import android.graphics.RectF
import com.google.mlkit.vision.text.Text

/** Extracts the text lines whose centers fall inside the subtitle region. */
object SubtitleText {

    private val letter = Regex("\\p{L}")

    fun extract(text: Text, roi: RectF): String {
        if (roi.isEmpty) return ""
        val lines = mutableListOf<Pair<Int, String>>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                if (!roi.contains(box.exactCenterX(), box.exactCenterY())) continue
                val value = line.text.trim()
                // Skip HUD noise such as "$1,250" or "02:15".
                if (letter.findAll(value).count() < 2) continue
                lines += box.top to value
            }
        }
        return lines.sortedBy { it.first }.joinToString(" ") { it.second }.trim()
    }
}

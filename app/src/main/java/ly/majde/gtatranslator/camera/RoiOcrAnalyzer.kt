package ly.majde.gtatranslator.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import kotlin.math.max
import kotlin.math.min

/**
 * High-accuracy subtitle OCR:
 *  - maps the on-screen subtitle box back to camera-buffer pixels,
 *  - crops just that region from a high-resolution frame,
 *  - rotates it upright and upscales small text before running ML Kit.
 * Cropping + upscaling gives the recognizer far more pixels per letter than
 * running it on the whole frame, which is what makes TV subtitles readable.
 */
class RoiOcrAnalyzer(
    private val recognizer: TextRecognizer,
    private val roiInView: () -> RectF,
    private val intervalMs: Long,
    private val onText: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    @Volatile private var sensorToView: Matrix? = null
    @Volatile private var busy = false
    private var lastRun = 0L

    override fun getTargetCoordinateSystem(): Int = ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED

    override fun updateTransform(matrix: Matrix?) {
        sensorToView = matrix?.let { Matrix(it) }
    }

    override fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        val transform = sensorToView
        if (busy || transform == null || now - lastRun < intervalMs) {
            image.close()
            return
        }
        lastRun = now
        val crop = try {
            cropRoi(image, transform)
        } catch (e: Exception) {
            null
        } finally {
            image.close()
        }
        if (crop == null) return
        busy = true
        recognizer.process(InputImage.fromBitmap(crop, 0))
            .addOnSuccessListener { text -> onText(extractLines(text)) }
            .addOnCompleteListener { busy = false }
    }

    private fun cropRoi(image: ImageProxy, sensorToViewMatrix: Matrix): Bitmap? {
        val roi = roiInView()
        if (roi.isEmpty) return null

        // view -> buffer = inverse(buffer -> sensor -> view)
        val sensorToBuffer = Matrix(image.imageInfo.sensorToBufferTransformMatrix)
        val bufferToSensor = Matrix()
        if (!sensorToBuffer.invert(bufferToSensor)) return null
        val bufferToView = Matrix(bufferToSensor).apply { postConcat(sensorToViewMatrix) }
        val viewToBuffer = Matrix()
        if (!bufferToView.invert(viewToBuffer)) return null

        val rect = RectF(roi)
        viewToBuffer.mapRect(rect)

        val full = image.toBitmap()
        val left = rect.left.toInt().coerceIn(0, full.width - 1)
        val top = rect.top.toInt().coerceIn(0, full.height - 1)
        val right = rect.right.toInt().coerceIn(left + 1, full.width)
        val bottom = rect.bottom.toInt().coerceIn(top + 1, full.height)
        val width = right - left
        val height = bottom - top
        if (width < 16 || height < 8) return null

        val rotation = image.imageInfo.rotationDegrees
        val uprightHeight = if (rotation == 90 || rotation == 270) width else height
        val uprightWidth = if (rotation == 90 || rotation == 270) height else width
        // Upscale small text so letters are comfortably above ML Kit's minimum size,
        // but cap the size to keep recognition fast.
        var scale = if (uprightHeight < TARGET_MIN_HEIGHT) TARGET_MIN_HEIGHT.toFloat() / uprightHeight else 1f
        scale = min(scale, MAX_UPSCALE)
        if (uprightWidth * scale > MAX_WIDTH) scale = max(0.5f, MAX_WIDTH.toFloat() / uprightWidth)

        val matrix = Matrix().apply {
            postRotate(rotation.toFloat())
            postScale(scale, scale)
        }
        return Bitmap.createBitmap(full, left, top, width, height, matrix, true)
    }

    private fun extractLines(text: Text): String {
        val lines = ArrayList<Pair<Int, String>>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val value = line.text.trim()
                if (value.count { it.isLetter() } < 2) continue // HUD noise like "$1,250"
                lines += (line.boundingBox?.top ?: 0) to value
            }
        }
        return lines.sortedBy { it.first }.joinToString(" ") { it.second }.trim()
    }

    private companion object {
        const val TARGET_MIN_HEIGHT = 260
        const val MAX_UPSCALE = 3f
        const val MAX_WIDTH = 2_400
    }
}

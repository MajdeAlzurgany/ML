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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/**
 * High-accuracy subtitle OCR:
 *  - maps the on-screen subtitle box back to camera-buffer pixels,
 *  - crops just that region from a high-resolution frame,
 *  - rotates it upright, corrects the tilt of the TV, and upscales small text.
 * It can also scan the whole frame once to find where the subtitles are.
 */
class RoiOcrAnalyzer(
    private val recognizer: TextRecognizer,
    private val roiInView: () -> RectF,
    private val intervalMs: Long,
    private val onText: (String) -> Unit,
    private val onScan: (List<ScanLine>) -> Unit,
) : ImageAnalysis.Analyzer {

    @Volatile private var sensorToView: Matrix? = null
    @Volatile private var busy = false
    @Volatile var scanRequested = false

    /** Smoothed tilt of the subtitle text in degrees (positive = clockwise). */
    @Volatile private var tilt = 0f
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
        if (scanRequested) {
            scanRequested = false
            scanFullFrame(image, transform)
            return
        }
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
            .addOnSuccessListener { text ->
                updateTilt(text)
                onText(extractLines(text))
            }
            .addOnCompleteListener { busy = false }
    }

    private fun bufferToView(image: ImageProxy, sensorToViewMatrix: Matrix): Matrix? {
        val sensorToBuffer = Matrix(image.imageInfo.sensorToBufferTransformMatrix)
        val bufferToSensor = Matrix()
        if (!sensorToBuffer.invert(bufferToSensor)) return null
        return Matrix(bufferToSensor).apply { postConcat(sensorToViewMatrix) }
    }

    private fun cropRoi(image: ImageProxy, sensorToViewMatrix: Matrix): Bitmap? {
        val roi = roiInView()
        if (roi.isEmpty) return null
        val b2v = bufferToView(image, sensorToViewMatrix) ?: return null
        val viewToBuffer = Matrix()
        if (!b2v.invert(viewToBuffer)) return null

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
        var scale = if (uprightHeight < TARGET_MIN_HEIGHT) TARGET_MIN_HEIGHT.toFloat() / uprightHeight else 1f
        scale = min(scale, MAX_UPSCALE)
        if (uprightWidth * scale > MAX_WIDTH) scale = max(0.5f, MAX_WIDTH.toFloat() / uprightWidth)

        val matrix = Matrix().apply {
            postRotate(rotation.toFloat())
            // Straighten a tilted TV / phone so lines are horizontal for the recognizer.
            if (abs(tilt) in MIN_TILT..MAX_TILT) postRotate(-tilt)
            postScale(scale, scale)
        }
        return Bitmap.createBitmap(full, left, top, width, height, matrix, true)
    }

    /** Runs OCR on the whole frame and reports every line in preview-view coordinates. */
    private fun scanFullFrame(image: ImageProxy, sensorToViewMatrix: Matrix) {
        val prepared = try {
            val b2v = bufferToView(image, sensorToViewMatrix)
            if (b2v == null) {
                null
            } else {
                val full = image.toBitmap()
                val rotation = image.imageInfo.rotationDegrees
                val longest = max(full.width, full.height)
                val scale = if (longest > SCAN_MAX_SIDE) SCAN_MAX_SIDE.toFloat() / longest else 1f
                // buffer -> upright (rotate, scale, then shift into positive coordinates)
                val toUpright = Matrix().apply {
                    postRotate(rotation.toFloat())
                    postScale(scale, scale)
                }
                val bounds = RectF(0f, 0f, full.width.toFloat(), full.height.toFloat())
                toUpright.mapRect(bounds)
                toUpright.postTranslate(-bounds.left, -bounds.top)
                val upright = Bitmap.createBitmap(full, 0, 0, full.width, full.height, toUpright, true)
                val uprightToBuffer = Matrix()
                if (!toUpright.invert(uprightToBuffer)) {
                    null
                } else {
                    val uprightToView = Matrix(uprightToBuffer).apply { postConcat(b2v) }
                    upright to uprightToView
                }
            }
        } catch (e: Exception) {
            null
        } finally {
            image.close()
        }
        if (prepared == null) {
            onScan(emptyList())
            return
        }
        val (bitmap, uprightToView) = prepared
        busy = true
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text ->
                val lines = ArrayList<ScanLine>()
                for (block in text.textBlocks) {
                    for (line in block.lines) {
                        val box = line.boundingBox ?: continue
                        val r = RectF(box)
                        uprightToView.mapRect(r)
                        lines += ScanLine(r, line.text.count { it.isLetter() })
                    }
                }
                onScan(lines)
            }
            .addOnFailureListener { onScan(emptyList()) }
            .addOnCompleteListener { busy = false }
    }

    private fun updateTilt(text: Text) {
        val angles = ArrayList<Float>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val pts = line.cornerPoints ?: continue
                if (pts.size < 2 || line.text.length < 6) continue
                val dx = (pts[1].x - pts[0].x).toDouble()
                val dy = (pts[1].y - pts[0].y).toDouble()
                if (dx <= 0.0) continue
                angles += Math.toDegrees(atan2(dy, dx)).toFloat()
            }
        }
        if (angles.isEmpty()) return
        angles.sort()
        // The crop is already straightened by the current tilt, so this is the residual.
        val residual = angles[angles.size / 2]
        tilt = (tilt + residual * 0.5f).coerceIn(-MAX_TILT, MAX_TILT)
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
        const val SCAN_MAX_SIDE = 1_920
        const val MIN_TILT = 1.5f
        const val MAX_TILT = 20f
    }
}

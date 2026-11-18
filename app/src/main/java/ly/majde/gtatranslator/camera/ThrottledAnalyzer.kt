package ly.majde.gtatranslator.camera

import android.graphics.Matrix
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * Forwards at most one frame every [intervalMs] to [delegate] (e.g. the ML Kit analyzer),
 * which keeps OCR responsive while saving battery and heat during long sessions.
 */
class ThrottledAnalyzer(
    private val delegate: ImageAnalysis.Analyzer,
    private val intervalMs: Long,
) : ImageAnalysis.Analyzer {

    private var lastRun = 0L

    override fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRun < intervalMs) {
            image.close()
            return
        }
        lastRun = now
        delegate.analyze(image)
    }

    override fun getDefaultTargetResolution(): Size? = delegate.defaultTargetResolution

    override fun getTargetCoordinateSystem(): Int = delegate.targetCoordinateSystem

    override fun updateTransform(matrix: Matrix?) = delegate.updateTransform(matrix)
}

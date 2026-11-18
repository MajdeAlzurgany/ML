package ly.majde.gtatranslator.camera

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/** Downscales (keeping aspect ratio) and encodes to JPEG for upload. */
fun Bitmap.toJpegBytes(maxSide: Int = 1280, quality: Int = 82): ByteArray {
    val longest = maxOf(width, height)
    val scaled = if (longest > maxSide) {
        val scale = maxSide.toFloat() / longest
        Bitmap.createScaledBitmap(this, (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), true)
    } else {
        this
    }
    return ByteArrayOutputStream().use { out ->
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        out.toByteArray()
    }
}

package ani.dantotsu.media.manga

import android.graphics.Bitmap
import android.graphics.BitmapFactory

internal object KirokuImageDecoder {

    const val DISPLAY_MAX_PIXELS = 16_000_000L

    fun decode(bytes: ByteArray, maxPixels: Long = DISPLAY_MAX_PIXELS): Bitmap? {
        if (bytes.isEmpty()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxPixels)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inScaled = false
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    fun sampleSizeFor(width: Int, height: Int, maxPixels: Long): Int {
        if (maxPixels <= 0L) return 1
        var sample = 1
        while (true) {
            val pixels = (width / sample).toLong() * (height / sample).toLong()
            if (pixels <= maxPixels || sample >= 32) break
            sample *= 2
        }
        return sample
    }
}

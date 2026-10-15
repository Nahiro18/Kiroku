package okhttp3

import okio.BufferedSource
import okio.Source
import okio.source
import java.util.zip.GZIPInputStream

/**
 * Compatibility shim mirroring OkHttp 5's gzip codec.
 */
object Gzip : CompressionInterceptor.DecompressionAlgorithm {
    override val encoding: String get() = "gzip"

    override fun decompress(compressedSource: BufferedSource): Source =
        GZIPInputStream(compressedSource.inputStream()).source()
}

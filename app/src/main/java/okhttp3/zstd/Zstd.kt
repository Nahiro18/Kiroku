package okhttp3.zstd

import okhttp3.CompressionInterceptor
import okio.BufferedSource
import okio.Source

/**
 * Compatibility placeholder mirroring OkHttp 5's zstd codec.
 *
 * This app has no zstd decoder and never advertises `zstd`, so servers
 * never send it and this is never invoked. It only exists so extensions
 * built against the newer API can link and construct their client.
 */
object Zstd : CompressionInterceptor.DecompressionAlgorithm {
    override val encoding: String get() = "zstd"

    override fun decompress(compressedSource: BufferedSource): Source =
        throw UnsupportedOperationException("zstd not supported")
}

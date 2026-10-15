package okhttp3

import okio.BufferedSource
import okio.Source
import okio.buffer
import okio.source

/**
 * Compatibility shim mirroring OkHttp 5's transparent-decompression interceptor.
 *
 * Newer (keiyoushi) extensions build their client with
 * `CompressionInterceptor(Brotli, Gzip, Zstd)` and refuse to run when the
 * host app cannot provide it, so this app ships a minimal equivalent:
 * it transparently decodes `gzip` bodies that are still encoded when they
 * reach it (OkHttp's own bridge already handles the transparent case) and
 * leaves anything else untouched.
 */
class CompressionInterceptor(vararg val codecs: DecompressionAlgorithm) : Interceptor {

    constructor(brotli: okhttp3.brotli.Brotli, gzip: Gzip, zstd: okhttp3.zstd.Zstd) :
        this(brotli as DecompressionAlgorithm, gzip as DecompressionAlgorithm, zstd as DecompressionAlgorithm)

    interface DecompressionAlgorithm {
        val encoding: String
        fun decompress(compressedSource: BufferedSource): Source
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val encoding = response.header("Content-Encoding") ?: return response
        if (!encoding.contains("gzip", ignoreCase = true)) return response
        val body = response.body ?: return response
        if (body.contentLength() == 0L) return response
        return try {
            val decoded = Gzip.decompress(body.source()).buffer().readByteArray()
            val mediaType = body.contentType()
            response.newBuilder()
                .removeHeader("Content-Encoding")
                .removeHeader("Content-Length")
                .body(
                    object : ResponseBody() {
                        override fun contentType() = mediaType
                        override fun contentLength() = decoded.size.toLong()
                        override fun source(): BufferedSource =
                            decoded.inputStream().source().buffer()
                    }
                )
                .build()
        } catch (_: Exception) {
            response
        }
    }
}

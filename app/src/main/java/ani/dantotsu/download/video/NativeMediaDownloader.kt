package ani.dantotsu.download.video

import ani.dantotsu.parsers.VideoType
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.io.OutputStream
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Downloads an episode using plain HTTP, without the FFmpeg download add-on.
 *
 * - direct mp4/mkv/webm links are streamed straight into [output]
 * - HLS (`m3u8`) links are downloaded segment by segment, AES-128 segments are decrypted and the
 *   result is concatenated into a single transport stream file
 *
 * The most common anime links are HLS, which is why the old code (which handed everything to an
 * external FFmpeg service) could produce a "Failed" entry for every episode.
 */
object NativeMediaDownloader {

    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"

    private const val BUFFER_SIZE = 256 * 1024
    private const val PROGRESS_STEP = 512 * 1024L
    private const val SEGMENT_RETRIES = 4

    /**
     * Multi-threaded downloading: a single file is split into this many ranges that are fetched
     * over separate connections at the same time, which is where most of the speed-up comes from.
     * HLS segment fetches use the same number of connections.
     */
    private const val PARALLEL_CONNECTIONS = 4
    private const val MIN_SEGMENT_BYTES = 2L * 1024 * 1024

    private class RangeNotSupported(message: String) : IOException(message)

    data class Result(
        val extension: String,
        val bytes: Long
    )

    /**
     * Extension the file will most likely get, used to create the destination before downloading.
     */
    fun predictExtension(url: String, format: VideoType?): String {
        if (format == VideoType.M3U8 || url.contains(".m3u8", ignoreCase = true)) return "ts"
        return extensionFromUrl(url)
    }

    /**
     * Downloads [url] into [output].
     *
     * @param onProgress called with (percent, bytesWritten, totalBytes or null when unknown)
     * @return the container actually written and its size
     */
    suspend fun download(
        url: String,
        headers: Map<String, String>,
        output: OutputStream,
        videoType: VideoType? = null,
        tempDir: File? = null,
        onProgress: (percent: Int, bytes: Long, total: Long?) -> Unit = { _, _, _ -> }
    ): Result {
        val target = url.trim()
        if (target.isEmpty()) throw IOException("Episode has no video link")
        if (target.startsWith("magnet:") || target.endsWith(".torrent", ignoreCase = true)) {
            throw IOException("Torrent episodes cannot be downloaded directly")
        }

        val client = Injekt.get<NetworkHelper>().downloadClient
        val response = client.newCall(request(target, headers)).execute()

        response.use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("Server returned HTTP ${resp.code} ${resp.message}".trim())
            }
            val contentType = resp.header("Content-Type").orEmpty().lowercase()
            val playlistUrl = resp.request.url.toString()
            val looksLikeHls = videoType == VideoType.M3U8 ||
                    target.contains(".m3u8", ignoreCase = true) ||
                    contentType.contains("mpegurl")

            if (looksLikeHls) {
                val playlist = resp.body.string()
                return downloadHls(client, playlistUrl, playlist, headers, output, onProgress)
            }
            if (contentType.startsWith("text/") || contentType.contains("json")) {
                throw IOException("The server sent a web page instead of a video (HTTP ${resp.code})")
            }
            if (videoType == VideoType.DASH && !contentType.startsWith("video/")) {
                throw IOException("DASH streams are not supported for downloading, pick another server")
            }

            val total = resp.body.contentLength().takeIf { it > 0 }
            if (tempDir != null && total != null && total >= MIN_SEGMENT_BYTES) {
                // This connection only provided the metadata; the body is fetched in ranges below.
                resp.close()
                val fast = try {
                    downloadSegmented(client, target, headers, total, tempDir, output, onProgress)
                } catch (e: RangeNotSupported) {
                    Logger.log("Ranged requests unavailable, using one connection: ${e.message}")
                    null
                } catch (e: IOException) {
                    Logger.log("Multi-threaded attempt failed, using one connection: ${e.message}")
                    null
                }
                if (fast != null) {
                    onProgress(100, fast, total)
                    return Result(extensionFromUrl(target, contentType), fast)
                }
            }
            val buffer = ByteArray(BUFFER_SIZE)
            var written = 0L
            var lastReport = 0L
            val context = currentCoroutineContext()

            resp.body.byteStream().use { input ->
                while (true) {
                    context.ensureActive()
                    val read = input.read(buffer)
                    if (read == -1) break
                    if (read > 0) {
                        output.write(buffer, 0, read)
                        written += read
                        if (written - lastReport >= PROGRESS_STEP) {
                            lastReport = written
                            onProgress(percent(written, total), written, total)
                        }
                    }
                }
            }
            output.flush()

            if (written == 0L) throw IOException("The server sent an empty file")
            if (total != null && written < total * 0.95) {
                throw IOException("Download stopped early ($written of $total bytes)")
            }
            onProgress(100, written, total ?: written)
            return Result(extensionFromUrl(target, contentType), written)
        }
    }

    // ---------------------------------------------------------------- HLS

    private data class HlsKey(val url: String, val iv: ByteArray?)

    private data class HlsSegment(val url: String, val key: HlsKey?, val sequence: Long)

    private suspend fun downloadHls(
        client: OkHttpClient,
        playlistUrl: String,
        playlist: String,
        headers: Map<String, String>,
        output: OutputStream,
        onProgress: (Int, Long, Long?) -> Unit
    ): Result {
        var currentUrl = playlistUrl
        var currentPlaylist = playlist

        // A master playlist only lists variants; follow the highest bandwidth one.
        var guard = 0
        while (guard++ < 5) {
            val variant = highestBandwidthVariant(currentPlaylist, currentUrl) ?: break
            currentUrl = variant
            currentPlaylist = fetchText(client, variant, headers)
        }

        val initSegment = mutableListOf<String>()
        val segments = parseMediaPlaylist(currentPlaylist, currentUrl, initSegment)
        if (segments.isEmpty()) {
            throw IOException("The playlist has no video segments")
        }

        var written = 0L
        val keyCache = mutableMapOf<String, ByteArray>()

        for (init in initSegment) {
            written += writeAll(output, fetchBytes(client, init, headers))
        }

        // Segments are fetched over several connections at once and written in playlist order.
        var index = 0
        while (index < segments.size) {
            val end = minOf(index + PARALLEL_CONNECTIONS, segments.size)
            val batch = segments.subList(index, end)
            val datas = coroutineScope {
                batch.map { segment -> async(Dispatchers.IO) { fetchSegment(client, segment, headers, keyCache) } }
                    .awaitAll()
            }
            datas.forEach { data ->
                output.write(data)
                written += data.size
            }
            index = end
            val shown = ((index * 100) / segments.size).coerceIn(0, 99)
            onProgress(shown, written, null)
        }
        output.flush()
        onProgress(100, written, written)
        Logger.log("HLS download finished: ${segments.size} segments, $written bytes")
        return Result("ts", written)
    }

    private suspend fun fetchSegment(
        client: OkHttpClient,
        segment: HlsSegment,
        headers: Map<String, String>,
        keyCache: MutableMap<String, ByteArray>
    ): ByteArray {
        var last: Exception? = null
        for (attempt in 1..SEGMENT_RETRIES) {
            try {
                val data = fetchBytes(client, segment.url, headers)
                val key = segment.key ?: return data
                val keyBytes = keyCache.getOrPut(key.url) { fetchBytes(client, key.url, headers) }
                val iv = key.iv ?: sequenceIv(segment.sequence)
                return decrypt(data, keyBytes, iv)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                if (attempt < SEGMENT_RETRIES) delay(400L * attempt)
            }
        }
        throw IOException("Could not download a video segment: ${last?.message}", last)
    }

    /**
     * Splits [total] bytes into [PARALLEL_CONNECTIONS] ranges, downloads them at the same time into
     * temporary files and joins them into [output]. Returns null when the server refuses ranges.
     */
    private suspend fun downloadSegmented(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>,
        total: Long,
        tempDir: File,
        output: OutputStream,
        onProgress: (Int, Long, Long?) -> Unit
    ): Long? = coroutineScope {
        val partCount = minOf(PARALLEL_CONNECTIONS.toLong(), total / (MIN_SEGMENT_BYTES / 2))
            .toInt().coerceAtLeast(1)
        if (partCount < 2) return@coroutineScope null

        val partSize = (total + partCount - 1) / partCount
        val written = AtomicLong(0)
        val stamp = System.nanoTime()
        val parts = (0 until partCount).map { File(tempDir, "kiroku-part-$stamp-$it.tmp") }

        try {
            val jobs = parts.mapIndexed { i, part ->
                async(Dispatchers.IO) {
                    val start = i * partSize
                    if (start >= total) return@async true
                    val end = minOf(total - 1, start + partSize - 1)
                    fetchRange(client, url, headers, start, end, part) { delta ->
                        val now = written.addAndGet(delta)
                        onProgress(percent(now, total), now, total)
                    }
                }
            }
            jobs.awaitAll()
            var joined = 0L
            withContext(Dispatchers.IO) {
                parts.forEach { part ->
                    part.inputStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            joined += read
                        }
                    }
                }
            }
            output.flush()
            Logger.log("Multi-threaded download finished: $partCount connections, $joined bytes")
            joined
        } finally {
            parts.forEach { part -> runCatching { part.delete() } }
        }
    }

    private suspend fun fetchRange(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>,
        start: Long,
        end: Long,
        target: File,
        onDelta: (Long) -> Unit
    ): Boolean {
        val expected = end - start + 1
        var last: Exception? = null
        for (attempt in 1..SEGMENT_RETRIES) {
            val context = currentCoroutineContext()
            try {
                target.delete()
                val request = request(url, headers).newBuilder()
                    .header("Range", "bytes=$start-$end")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.code == 200) {
                        throw RangeNotSupported("The server ignored the Range header")
                    }
                    if (response.code != 206) {
                        throw IOException("Range request returned HTTP ${response.code}")
                    }
                    target.outputStream().use { out ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var received = 0L
                        response.body.byteStream().use { input ->
                            while (true) {
                                context.ensureActive()
                                val read = input.read(buffer)
                                if (read == -1) break
                                if (read > 0) {
                                    out.write(buffer, 0, read)
                                    received += read
                                    onDelta(read.toLong())
                                }
                            }
                        }
                        if (received != expected) {
                            throw IOException("Range $start-$end stopped at $received of $expected bytes")
                        }
                    }
                }
                return true
            } catch (e: RangeNotSupported) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                if (attempt < SEGMENT_RETRIES) delay(500L * attempt)
            }
        }
        throw IOException("Could not download bytes $start-$end: ${last?.message}", last)
    }

    private fun sequenceIv(sequence: Long): ByteArray {
        val iv = ByteArray(16)
        var value = sequence
        for (i in 15 downTo 8) {
            iv[i] = (value and 0xFF).toByte()
            value = value shr 8
        }
        return iv
    }

    private fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    private fun highestBandwidthVariant(playlist: String, baseUrl: String): String? {
        var best: String? = null
        var bestBandwidth = -1L
        var pendingBandwidth = -1L
        playlist.lineSequence().map { it.trim() }.forEach { line ->
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    val attrs = attributes(line.substringAfter(':'))
                    pendingBandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: -1L
                }

                line.isEmpty() || line.startsWith("#") -> Unit

                pendingBandwidth >= 0 -> {
                    if (pendingBandwidth > bestBandwidth) {
                        bestBandwidth = pendingBandwidth
                        best = resolve(baseUrl, line)
                    }
                    pendingBandwidth = -1L
                }
            }
        }
        return best
    }

    private fun parseMediaPlaylist(
        playlist: String,
        baseUrl: String,
        initSegments: MutableList<String>
    ): List<HlsSegment> {
        var key: HlsKey? = null
        var mediaSequence = 0L
        var index = 0L
        val segments = mutableListOf<HlsSegment>()

        playlist.lineSequence().map { it.trim() }.forEach { line ->
            when {
                line.isEmpty() -> Unit

                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                    mediaSequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L

                line.startsWith("#EXT-X-KEY:") -> {
                    val attrs = attributes(line.substringAfter(':'))
                    when (attrs["METHOD"]?.uppercase()) {
                        null, "NONE" -> key = null
                        "AES-128" -> {
                            val uri = attrs["URI"]
                            if (uri != null) {
                                val iv = attrs["IV"]
                                    ?.removePrefix("0x")
                                    ?.removePrefix("0X")
                                    ?.let { hexToBytes(it) }
                                key = HlsKey(resolve(baseUrl, uri), iv)
                            }
                        }

                        else -> throw IOException(
                            "This playlist uses unsupported encryption (${attrs["METHOD"]})"
                        )
                    }
                }

                line.startsWith("#EXT-X-MAP:") -> {
                    attributes(line.substringAfter(':'))["URI"]?.let {
                        initSegments.add(resolve(baseUrl, it))
                    }
                }

                line.startsWith("#") -> Unit

                else -> {
                    segments.add(HlsSegment(resolve(baseUrl, line), key, mediaSequence + index))
                    index++
                }
            }
        }
        return segments
    }

    // ---------------------------------------------------------------- HTTP helpers

    private fun request(url: String, headers: Map<String, String>): Request {
        val builder = Request.Builder().url(url)
        var hasUserAgent = false
        headers.forEach { (name, value) ->
            if (name.isBlank() || value.isBlank()) return@forEach
            if (name.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
            try {
                builder.header(name, value)
            } catch (e: IllegalArgumentException) {
                Logger.log("Skipping invalid header $name: ${e.message}")
            }
        }
        if (!hasUserAgent) builder.header("User-Agent", DEFAULT_USER_AGENT)
        return builder.build()
    }

    private suspend fun fetchBytes(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>
    ): ByteArray {
        client.newCall(request(url, headers)).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} for ${url.take(120)}")
            }
            return response.body.bytes()
        }
    }

    private suspend fun fetchText(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>
    ): String = fetchBytes(client, url, headers).toString(Charsets.UTF_8)

    private fun writeAll(output: OutputStream, data: ByteArray): Long {
        output.write(data)
        return data.size.toLong()
    }

    private fun percent(written: Long, total: Long?): Int {
        if (total == null || total <= 0) return 0
        return ((written * 100) / total).toInt().coerceIn(0, 99)
    }

    private fun extensionFromUrl(url: String, contentType: String = ""): String {
        val path = try {
            URI(url).path.orEmpty()
        } catch (e: Exception) {
            url
        }.lowercase()
        val known = listOf("mp4", "mkv", "webm", "ts", "mov", "flv")
        known.firstOrNull { path.endsWith(".$it") }?.let { return it }
        if (path.endsWith(".m3u8")) return "ts"
        return when {
            contentType.contains("matroska") -> "mkv"
            contentType.contains("webm") -> "webm"
            else -> "mp4"
        }
    }

    private fun resolve(baseUrl: String, reference: String): String {
        return try {
            URI(baseUrl).resolve(reference).toString()
        } catch (e: Exception) {
            reference
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val length = clean.length / 2
        val out = ByteArray(length)
        for (i in 0 until length) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    private fun attributes(input: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var i = 0
        while (i < input.length) {
            val equals = input.indexOf('=', i)
            if (equals < 0) break
            val name = input.substring(i, equals).trim()
            var cursor = equals + 1
            val value: String
            if (cursor < input.length && input[cursor] == '"') {
                val end = input.indexOf('"', cursor + 1).let { if (it < 0) input.length else it }
                value = input.substring(cursor + 1, end)
                cursor = end + 1
                while (cursor < input.length && input[cursor] != ',') cursor++
            } else {
                val end = input.indexOf(',', cursor).let { if (it < 0) input.length else it }
                value = input.substring(cursor, end).trim()
                cursor = end
            }
            if (name.isNotEmpty()) result[name.uppercase()] = value
            i = cursor + 1
        }
        return result
    }
}

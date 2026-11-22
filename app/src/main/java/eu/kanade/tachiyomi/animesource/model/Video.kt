package eu.kanade.tachiyomi.animesource.model

import eu.kanade.tachiyomi.network.ProgressListener
import okhttp3.Headers
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

/**
 * A sub/dub track.
 */
data class Track(val url: String, val lang: String) : Serializable

enum class ChapterType {
    Opening,
    Ending,
    Recap,
    MixedOp,
    Other,
}

/**
 * A class defining a timestamp. Displayed as video chapters in the app.
 */
data class TimeStamp(
    val start: Double,
    val end: Double,
    val name: String,
    val type: ChapterType = ChapterType.Other,
) : Serializable

/**
 * The instance that contains the data needed to watch a video.
 *
 * This mirrors the extensions-lib 16 `Video` data class exactly, because extensions built
 * against that library construct it by constructor/`$default` JVM signature.
 */
@Suppress("unused_parameter", "MemberVisibilityCanBePrivate")
data class Video(
    val videoUrl: String = "",
    val videoTitle: String = "",
    val resolution: Int? = null,
    val bitrate: Int? = null,
    @Transient var headers: Headers? = null,
    val preferred: Boolean = false,
    val subtitleTracks: List<Track> = emptyList(),
    val audioTracks: List<Track> = emptyList(),
    val timestamps: List<TimeStamp> = emptyList(),
    val mpvArgs: List<Pair<String, String>> = emptyList(),
    val ffmpegStreamArgs: List<Pair<String, String>> = emptyList(),
    val ffmpegVideoArgs: List<Pair<String, String>> = emptyList(),
    val internalData: String = "",
    val initialized: Boolean = false,
) : Serializable, ProgressListener {

    /** Compatibility accessors for the app's existing Dantotsu-era code. */
    val url: String
        get() = videoUrl

    val quality: String
        get() = videoTitle

    @Transient
    @Volatile
    var totalContentLength: Long = 0L

    @Transient
    @Volatile
    var bytesDownloaded: Long = 0L

    override fun update(bytesRead: Long, contentLength: Long, done: Boolean) {
        bytesDownloaded = bytesRead
        if (contentLength > totalContentLength) totalContentLength = contentLength
    }

    @Throws(IOException::class)
    private fun writeObject(out: ObjectOutputStream) {
        out.defaultWriteObject()
        val headersMap: Map<String, List<String>> = headers?.toMultimap() ?: emptyMap()
        out.writeObject(headersMap)
    }

    @Suppress("UNCHECKED_CAST")
    @Throws(IOException::class, ClassNotFoundException::class)
    private fun readObject(input: ObjectInputStream) {
        input.defaultReadObject()
        val headersMap = input.readObject() as? Map<String, List<String>>
        headers = headersMap?.let { map ->
            val builder = Headers.Builder()
            for ((key, values) in map) {
                for (value in values) {
                    builder.add(key, value)
                }
            }
            builder.build()
        }
    }
}

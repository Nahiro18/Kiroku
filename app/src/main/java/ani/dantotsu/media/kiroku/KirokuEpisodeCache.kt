package ani.dantotsu.media.kiroku

import ani.dantotsu.FileUrl
import ani.dantotsu.media.anime.Episode
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object KirokuEpisodeCache {

    private const val KIND = "episodes"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    @Serializable
    private data class Row(
        val number: String,
        val link: String? = null,
        val title: String? = null,
        val description: String? = null,
        val thumbUrl: String? = null,
        val thumbHeaders: Map<String, String>? = null,
        val filler: Boolean = false,
        val allStreams: Boolean = false,
        val watched: Long? = null,
        val maxLength: Long? = null,
        val extra: Map<String, String>? = null,
        val sourceName: String = "",
        val sourceUrl: String = "",
        val sourceNumber: Float = -1f,
        val sourceScanlator: String? = null,
        val sourceDate: Long = 0L,
    )

    @Serializable
    private data class Payload(
        val savedAt: Long = 0L,
        val rows: List<Row> = emptyList(),
    )

    fun load(sourceKey: String, link: String): MutableMap<String, Episode>? {
        if (sourceKey.isBlank() || link.isBlank()) return null
        val text = KirokuCacheFile.read(KIND, sourceKey, link) ?: return null
        return runCatching {
            val payload = json.decodeFromString<Payload>(text)
            if (payload.rows.isEmpty()) return null
            val episodes = mutableMapOf<String, Episode>()
            payload.rows.forEach { row ->
                episodes[row.number] = row.toEpisode()
            }
            episodes.takeIf { it.isNotEmpty() }
        }.onFailure {
            Logger.log("Kiroku cache episodes unreadable: ${it.message}")
        }.getOrNull()
    }

    fun save(sourceKey: String, link: String, episodes: Map<String, Episode>) {
        if (sourceKey.isBlank() || link.isBlank() || episodes.isEmpty()) return
        val payload = Payload(
            savedAt = System.currentTimeMillis(),
            rows = episodes.values.map { it.toRow() },
        )
        runCatching {
            KirokuCacheFile.write(KIND, sourceKey, link, json.encodeToString(payload))
        }.onFailure {
            Logger.log("Kiroku cache episodes not saved: ${it.message}")
        }
    }

    fun invalidate(sourceKey: String, link: String) {
        KirokuCacheFile.remove(KIND, sourceKey, link)
    }

    fun usedBytes(): Long = KirokuCacheFile.usedBytes(KIND)

    fun clear() = KirokuCacheFile.clear(KIND)

    private fun Episode.toRow(): Row {
        val source = sEpisode
        return Row(
            number = number,
            link = link,
            title = title,
            description = desc,
            thumbUrl = thumb?.url,
            thumbHeaders = thumb?.headers?.takeIf { it.isNotEmpty() },
            filler = filler,
            allStreams = allStreams,
            watched = watched,
            maxLength = maxLength,
            extra = extra?.takeIf { it.isNotEmpty() },
            sourceName = source?.name ?: "",
            sourceUrl = source?.url ?: link ?: "",
            sourceNumber = source?.episode_number ?: -1f,
            sourceScanlator = source?.scanlator,
            sourceDate = source?.date_upload ?: 0L,
        )
    }

    private fun Row.toEpisode(): Episode {
        val source = SEpisode.create().apply {
            name = sourceName
            url = sourceUrl
            episode_number = sourceNumber
            scanlator = sourceScanlator
            date_upload = sourceDate
        }
        return Episode(
            number = number,
            link = link,
            title = title,
            desc = description,
            thumb = FileUrl[thumbUrl, thumbHeaders ?: mapOf()],
            filler = filler,
            allStreams = allStreams,
            watched = watched,
            maxLength = maxLength,
            extra = extra,
            sEpisode = source,
        )
    }
}

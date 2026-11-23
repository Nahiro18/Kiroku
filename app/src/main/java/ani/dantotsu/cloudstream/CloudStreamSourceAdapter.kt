package ani.dantotsu.cloudstream

import ani.dantotsu.FileUrl
import ani.dantotsu.parsers.AnimeParser
import ani.dantotsu.parsers.Episode
import ani.dantotsu.parsers.ShowResponse
import ani.dantotsu.parsers.Subtitle
import ani.dantotsu.parsers.SubtitleType
import ani.dantotsu.parsers.Video
import ani.dantotsu.parsers.VideoContainer
import ani.dantotsu.parsers.VideoExtractor
import ani.dantotsu.parsers.VideoServer
import ani.dantotsu.parsers.VideoType
import ani.dantotsu.parsers.VideoServerPassthrough
import ani.dantotsu.util.Logger
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode as CloudEpisode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Headers
import java.util.Collections
import java.util.Locale

/**
 * Converts a CloudStream [MainAPI] provider into a Kiroku [AnimeParser], so CloudStream
 * extensions behave like every other anime source (search, details, episodes, servers).
 */
class CloudStreamSourceAdapter(
    private val provider: CloudStreamProvider,
) : AnimeParser() {
    private val api: MainAPI get() = provider.api
    private val extension: CloudStreamInstalled get() = provider.extension

    override val name: String = api.name
    override val saveName: String = "CloudStream: ${api.name}"
    override val hostUrl: String =
        api.mainUrl.takeIf { it.isNotBlank() && it != "NONE" } ?: "https://${api.name}"
    override val isNSFW: Boolean = extension.isNsfw
    override val language: String =
        runCatching { Locale(api.lang).displayLanguage }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: api.lang

    override val allowsPreloading: Boolean = false

    override suspend fun search(query: String): List<ShowResponse> {
        return try {
            val results = try {
                api.search(query)
            } catch (t: NotImplementedError) {
                // Newer providers only implement the paginated overload.
                api.search(query, 1)?.items
            } ?: emptyList()
            results.mapNotNull { it.toShowResponse() }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Logger.log("CloudStream ${api.name}: search failed: ${t.message}")
            emptyList()
        }
    }

    override suspend fun loadEpisodes(
        animeLink: String,
        extra: Map<String, String>?,
        sAnime: SAnime
    ): List<Episode> {
        val response = try {
            api.load(animeLink)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Logger.log("CloudStream ${api.name}: load failed: ${t.message}")
            null
        } ?: return emptyList()

        return when (response) {
            is TvSeriesLoadResponse -> response.episodes.toKirokuEpisodes(response.name)
            is AnimeLoadResponse -> {
                val list = selectEpisodes(response)
                list.toKirokuEpisodes(response.name)
            }
            is MovieLoadResponse -> {
                val data = response.dataUrl.ifBlank { response.url }
                if (data.isBlank()) {
                    emptyList()
                } else {
                    listOf(
                        Episode(
                            number = "1",
                            link = data,
                            title = response.name,
                            description = response.plot,
                        )
                    )
                }
            }
            else -> emptyList()
        }
    }

    private fun selectEpisodes(response: AnimeLoadResponse): List<CloudEpisode> {
        val byStatus = response.episodes
        val preferred = when {
            selectDub && !byStatus[DubStatus.Dubbed].isNullOrEmpty() -> byStatus[DubStatus.Dubbed]
            !byStatus[DubStatus.Subbed].isNullOrEmpty() -> byStatus[DubStatus.Subbed]
            !byStatus[DubStatus.Dubbed].isNullOrEmpty() -> byStatus[DubStatus.Dubbed]
            else -> byStatus.values.flatten()
        }.orEmpty()
        // Dubbed and subbed entries sometimes point at the same episode data.
        return preferred.distinctBy { it.data to it.episode }
    }

    private fun List<CloudEpisode>.toKirokuEpisodes(showName: String): List<Episode> {
        return mapIndexedNotNull { index, cloudEpisode ->
            val data = cloudEpisode.data
            if (data.isBlank()) return@mapIndexedNotNull null
            val number = cloudEpisode.episode?.toString() ?: (index + 1).toString()
            Episode(
                number = number,
                link = data,
                title = cloudEpisode.name?.takeIf { it.isNotBlank() }
                    ?: "$showName - Episode $number",
                thumbnail = cloudEpisode.posterUrl?.let { FileUrl(it) },
                description = cloudEpisode.description,
                extra = cloudEpisode.season?.let { mapOf("season" to it.toString()) },
            )
        }
    }

    override suspend fun loadVideoServers(
        episodeLink: String,
        extra: Map<String, String>?,
        sEpisode: SEpisode
    ): List<VideoServer> {
        val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val subtitles = Collections.synchronizedList(mutableListOf<SubtitleFile>())
        try {
            val timeout = api.loadLinksTimeoutMs ?: DEFAULT_LINK_TIMEOUT_MS
            val finished = withTimeoutOrNull(timeout) {
                api.loadLinks(
                    data = episodeLink,
                    isCasting = false,
                    subtitleCallback = { file -> subtitles.add(file) },
                    callback = { link -> links.add(link) },
                )
                true
            }
            if (finished == null) {
                Logger.log("CloudStream ${api.name}: link extraction timed out")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Logger.log("CloudStream ${api.name}: link extraction failed: ${t.message}")
        }

        val subtitleTracks = CloudStreamSubtitleAdapter.toTracks(subtitles.toList())
        return synchronized(links) {
            links.toList()
        }.mapNotNull { link -> link.toVideoServer(subtitleTracks) }
    }

    override suspend fun getVideoExtractor(server: VideoServer): VideoExtractor? =
        VideoServerPassthrough(server)

    private fun SearchResponse.toShowResponse(): ShowResponse? {
        if (name.isBlank() || url.isBlank()) return null
        val cover = posterUrl.orEmpty()
        return ShowResponse(
            name = name,
            link = url,
            coverUrl = FileUrl(cover, posterHeaders ?: emptyMap()),
        )
    }

    @OptIn(com.lagradost.cloudstream3.Prerelease::class)
    private fun ExtractorLink.toVideoServer(
        subtitles: List<Track>,
    ): VideoServer? {
        if (url.isBlank()) return null
        val qualityValue = quality.takeIf { it in 1..4320 }
        val headers = getAllHeaders()
        val aniHeaders = Headers.Builder().apply {
            headers.forEach { (key, value) -> add(key, value) }
        }.build()
        val audioTracks = audioTracks.mapIndexed { index, audio ->
            Track(audio.url, "Audio ${index + 1}")
        }
        val video = eu.kanade.tachiyomi.animesource.model.Video(
            videoUrl = url,
            videoTitle = name.ifBlank { source },
            resolution = qualityValue,
            headers = aniHeaders,
            subtitleTracks = subtitles,
            audioTracks = audioTracks,
        )
        val label = buildString {
            append(source.ifBlank { name.ifBlank { "CloudStream" } })
            if (name.isNotBlank() && name != source) append(" - ").append(name)
            if (qualityValue != null) append(" ").append(qualityValue).append('p')
        }
        return VideoServer(
            name = label,
            embed = FileUrl(url, headers),
            video = video,
        )
    }

    companion object {
        private const val DEFAULT_LINK_TIMEOUT_MS = 120_000L
    }
}

/** Converts CloudStream subtitle callbacks into Kiroku's subtitle/track model. */
object CloudStreamSubtitleAdapter {
    fun toTracks(files: List<SubtitleFile>): List<Track> =
        files.filter { it.url.isNotBlank() }
            .distinctBy { it.url }
            .map { Track(it.url, it.lang.ifBlank { "Unknown" }) }

    fun toSubtitles(files: List<SubtitleFile>): List<Subtitle> =
        files.filter { it.url.isNotBlank() }.map {
            Subtitle(
                language = it.lang.ifBlank { "Unknown" },
                file = FileUrl(it.url, it.headers ?: emptyMap()),
                type = subtitleType(it.url),
            )
        }

    private fun subtitleType(url: String): SubtitleType = when {
        url.contains(".vtt", true) -> SubtitleType.VTT
        url.contains(".ass", true) || url.contains(".ssa", true) -> SubtitleType.ASS
        url.contains(".srt", true) -> SubtitleType.SRT
        else -> SubtitleType.UNKNOWN
    }
}

/** Converts CloudStream extractor links into Kiroku playable videos. */
object CloudStreamExtractor {
    fun toVideo(link: ExtractorLink): Video {
        val format = when (link.type) {
            ExtractorLinkType.M3U8 -> VideoType.M3U8
            ExtractorLinkType.DASH -> VideoType.DASH
            else -> VideoType.CONTAINER
        }
        return Video(
            quality = link.quality.takeIf { it in 1..4320 },
            format = format,
            file = FileUrl(link.url, link.getAllHeaders()),
        )
    }

    fun toContainer(link: ExtractorLink, subtitles: List<SubtitleFile> = emptyList()): VideoContainer {
        return VideoContainer(
            videos = listOf(toVideo(link)),
            subtitles = CloudStreamSubtitleAdapter.toSubtitles(subtitles),
        )
    }
}

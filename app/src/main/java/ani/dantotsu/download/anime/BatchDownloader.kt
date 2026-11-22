package ani.dantotsu.download.anime

import android.app.Activity
import android.content.Intent
import ani.dantotsu.download.video.Helper
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsViewModel
import ani.dantotsu.media.anime.AnimeWatchFragment
import ani.dantotsu.media.anime.Episode
import ani.dantotsu.parsers.Video
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Downloads a whole season with one server choice.
 *
 * The user picks a server/quality once in the selector; every queued episode is then resolved with
 * that same extractor (falling back to the first working one for episodes where it is missing) and
 * handed to the download service, which runs several of them at a time.
 */
object BatchDownloader {
    /** How many episodes we resolve at once. The download service does its own throttling. */
    private const val RESOLVE_PARALLEL = 3
    private const val MAX_RESOLVE_WAIT_MS = 60_000L

    fun start(
        activity: Activity,
        model: MediaDetailsViewModel,
        media: Media,
        episodeNumbers: List<String>,
        extractorName: String?,
        videoIndex: Int,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        onFinished: (started: Int, skipped: Int) -> Unit = { _, _ -> }
    ) {
        val episodes = media.anime?.episodes ?: return
        val sourceIndex = media.selected?.sourceIndex ?: 0
        val title = media.mainName()
        val total = episodeNumbers.size
        val started = AtomicInteger(0)
        val skipped = AtomicInteger(0)

        CoroutineScope(Dispatchers.IO).launch {
            val semaphore = Semaphore(RESOLVE_PARALLEL)
            val jobs = episodeNumbers.map { number ->
                launch {
                    semaphore.withPermit {
                        try {
                            val ep = episodes[number]
                                ?: episodes.values.firstOrNull { it.number == number }
                            val video = ep?.let {
                                resolveVideo(model, it, sourceIndex, extractorName, videoIndex)
                            }
                            if (ep == null || video == null) {
                                Logger.log("BATCH: could not resolve episode $number, skipping")
                                skipped.incrementAndGet()
                            } else {
                                val resolvedEpisode = ep
                                val resolvedVideo = video
                                val episodeNumber: String = resolvedEpisode.number
                                withContext(Dispatchers.Main) {
                                    Helper.startAnimeDownloadService(
                                        activity,
                                        title,
                                        episodeNumber,
                                        resolvedVideo,
                                        emptyList(),
                                        emptyList(),
                                        media,
                                        resolvedEpisode.thumb?.url ?: media.banner ?: media.cover,
                                        silent = true
                                    )
                                    val broadcast = Intent(
                                        AnimeWatchFragment.ACTION_DOWNLOAD_STARTED
                                    )
                                    broadcast.putExtra(
                                        AnimeWatchFragment.EXTRA_EPISODE_NUMBER,
                                        episodeNumber
                                    )
                                    activity.sendBroadcast(broadcast)
                                }
                                started.incrementAndGet()
                            }
                        } catch (e: Exception) {
                            Logger.log("BATCH: episode $number failed: ${e.message}")
                            skipped.incrementAndGet()
                        }
                        onProgress(started.get() + skipped.get(), total)
                    }
                }
            }
            jobs.forEach { it.join() }
            withContext(Dispatchers.Main) {
                onFinished(started.get(), skipped.get())
            }
        }
    }

    private suspend fun resolveVideo(
        model: MediaDetailsViewModel,
        ep: Episode,
        sourceIndex: Int,
        extractorName: String?,
        videoIndex: Int
    ): Video? {
        if (ep.link == null) return null
        if (ep.extractors.isNullOrEmpty()) {
            try {
                model.loadEpisodeVideos(ep, sourceIndex, post = true, force = true)
            } catch (e: Exception) {
                Logger.log("BATCH: loading videos failed for ${ep.number}: ${e.message}")
            }
        }
        var waited = 0L
        while (model.isEpisodeLoading(ep) && waited < MAX_RESOLVE_WAIT_MS) {
            delay(300)
            waited += 300
        }

        val extractors = ep.extractors.orEmpty()
        val extractor = extractors.firstOrNull {
            it.videos.isNotEmpty() && it.server.name == extractorName
        } ?: extractors.firstOrNull { it.videos.isNotEmpty() } ?: return null

        val video = extractor.videos.getOrNull(videoIndex) ?: extractor.videos.firstOrNull()
            ?: return null
        // Torrent entries need the torrent add-on to be resolved first, which the batch cannot do
        // silently; skip them instead of queueing a download that would always fail.
        if (video.file.url.startsWith("magnet:") || video.file.url.endsWith(".torrent")) {
            return null
        }
        ep.selectedExtractor = extractor.server.name
        ep.selectedVideo = extractor.videos.indexOf(video)
        return video
    }
}

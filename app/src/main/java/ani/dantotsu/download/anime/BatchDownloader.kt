package ani.dantotsu.download.anime

import android.app.Activity
import android.content.Intent
import ani.dantotsu.download.DownloadQueueTracker
import ani.dantotsu.download.video.Helper
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsViewModel
import ani.dantotsu.media.anime.AnimeWatchFragment
import ani.dantotsu.media.anime.Episode
import ani.dantotsu.parsers.Video
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Downloads a whole season with one server choice.
 *
 * Every selected episode is resolved with that same server and pushed onto the download queue; the
 * download service then runs as many of them at a time as the "Episodes downloading at once" setting
 * allows. Episodes whose server never produced a video are retried once before being reported as
 * skipped, so nothing disappears from the queue without a visible reason.
 */
object BatchDownloader {
    /** How many episodes we resolve at once. The download service does its own throttling. */
    private const val RESOLVE_PARALLEL = 3
    private const val MAX_RESOLVE_WAIT_MS = 60_000L

    private sealed class Resolved {
        data class Ready(val video: Video, val number: String) : Resolved()
        data class Failed(val reason: String) : Resolved()
    }

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
        val sourceIndex = media.selected?.sourceIndex ?: 0
        val title = media.mainName()
        val total = episodeNumbers.size
        val started = AtomicInteger(0)
        val skipped = AtomicInteger(0)
        val unresolved = ConcurrentLinkedQueue<String>()
        val reasons = ConcurrentHashMap<String, String>()

        CoroutineScope(Dispatchers.IO).launch {
            val semaphore = Semaphore(RESOLVE_PARALLEL)
            coroutineScope {
                episodeNumbers.map { number ->
                    async {
                        semaphore.withPermit {
                            val queued = queueEpisode(
                                activity, model, media, number, sourceIndex,
                                extractorName, videoIndex, title, reasons
                            )
                            if (queued) {
                                started.incrementAndGet()
                            } else {
                                unresolved.add(number)
                            }
                            onProgress(started.get() + skipped.get(), total)
                        }
                    }
                }.awaitAll()
            }

            // One more try, one episode at a time: the first wave hits the source with three
            // requests at once, which is exactly when a server rate-limits and returns nothing.
            if (unresolved.isNotEmpty()) {
                delay(1500)
                for (number in unresolved) {
                    val queued = withContext(Dispatchers.IO) {
                        queueEpisode(
                            activity, model, media, number, sourceIndex,
                            extractorName, videoIndex, title, reasons
                        )
                    }
                    if (queued) {
                        started.incrementAndGet()
                    } else {
                        skipped.incrementAndGet()
                    }
                    onProgress(started.get() + skipped.get(), total)
                }
            }

            withContext(Dispatchers.Main) {
                onFinished(started.get(), skipped.get())
            }
        }
    }

    private suspend fun queueEpisode(
        activity: Activity,
        model: MediaDetailsViewModel,
        media: Media,
        number: String,
        sourceIndex: Int,
        extractorName: String?,
        videoIndex: Int,
        title: String,
        reasons: MutableMap<String, String>
    ): Boolean {
        val episodes = media.anime?.episodes ?: return false
        val ep = episodes[number] ?: episodes.values.firstOrNull { it.number == number }
        if (ep == null) {
            reasons[number] = "episode not found"
            reportSkipped(title, number, reasons[number]!!)
            return false
        }

        val resolved = resolveVideo(model, ep, sourceIndex, extractorName, videoIndex)
        if (resolved is Resolved.Failed) {
            Logger.log("BATCH: episode $number skipped: ${resolved.reason}")
            reasons[number] = resolved.reason
            reportSkipped(title, number, resolved.reason)
            return false
        }
        val ready = resolved as Resolved.Ready

        return withContext(Dispatchers.Main) {
            val queued = Helper.startAnimeDownloadService(
                activity,
                title,
                ready.number,
                ready.video,
                emptyList(),
                emptyList(),
                media,
                ep.thumb?.url ?: media.banner ?: media.cover,
                silent = true
            )
            if (queued) {
                val broadcast = Intent(AnimeWatchFragment.ACTION_DOWNLOAD_STARTED)
                broadcast.putExtra(AnimeWatchFragment.EXTRA_EPISODE_NUMBER, ready.number)
                activity.sendBroadcast(broadcast)
            } else {
                reasons[ready.number] = "already downloaded"
                reportSkipped(title, ready.number, "already downloaded")
            }
            queued
        }
    }

    private fun reportSkipped(title: String, episode: String, reason: String) {
        DownloadQueueTracker.upsert(title, episode, "Skipped: $reason", 0)
    }

    private suspend fun resolveVideo(
        model: MediaDetailsViewModel,
        ep: Episode,
        sourceIndex: Int,
        extractorName: String?,
        videoIndex: Int
    ): Resolved {
        if (ep.link == null) return Resolved.Failed("no episode link")
        try {
            model.loadEpisodeVideos(ep, sourceIndex, post = true, force = true)
        } catch (e: Exception) {
            Logger.log("BATCH: loading videos failed for ${ep.number}: ${e.message}")
        }
        var waited = 0L
        while (model.isEpisodeLoading(ep) && waited < MAX_RESOLVE_WAIT_MS) {
            delay(300)
            waited += 300
        }

        val extractors = ep.extractors.orEmpty().filter { it.videos.isNotEmpty() }
        if (extractors.isEmpty()) {
            return Resolved.Failed(if (model.isEpisodeLoading(ep)) "server timed out" else "no videos on this server")
        }
        val extractor = extractors.firstOrNull { it.server.name == extractorName } ?: extractors.first()
        val video = extractor.videos.getOrNull(videoIndex) ?: extractor.videos.first()
        // Torrent entries need the torrent add-on to be resolved first, which the batch cannot do
        // silently; skip them instead of queueing a download that would always fail.
        if (video.file.url.startsWith("magnet:") || video.file.url.endsWith(".torrent")) {
            return Resolved.Failed("torrent link")
        }
        ep.selectedExtractor = extractor.server.name
        ep.selectedVideo = extractor.videos.indexOf(video)
        return Resolved.Ready(video, ep.number)
    }
}

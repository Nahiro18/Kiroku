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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Downloads a whole season with one server choice.
 *
 * Every selected episode is resolved with that same server and pushed onto the download queue in
 * episode order: resolution runs several episodes at a time, but an episode only enters the queue
 * once every earlier episode of the batch is already in it. That keeps "download 1-7" starting with
 * episode 1 instead of whichever source answered first. The download service then runs as many of
 * them at a time as the "Episodes downloading at once" setting allows.
 *
 * Episodes whose server produced no video are retried twice (sequentially, with a pause, so the
 * source is not hit by a burst again) before being reported as skipped, so nothing disappears from
 * the queue without a visible reason.
 */
object BatchDownloader {
    /** How many episodes we resolve at once. The download service does its own throttling. */
    private const val RESOLVE_PARALLEL = 3
    private const val MAX_RESOLVE_WAIT_MS = 60_000L
    private val RETRY_DELAYS_MS = longArrayOf(2_000L, 6_000L)

    private sealed class Resolved {
        data class Ready(val episode: Episode, val video: Video, val number: String) : Resolved()
        data class Failed(val number: String, val reason: String) : Resolved()
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
        val order = episodeNumbers.toList()
        val total = order.size
        val started = AtomicInteger(0)
        val skipped = AtomicInteger(0)
        // Written only while the emit mutex is held, so a plain array is enough.
        val slots = arrayOfNulls<Resolved>(total)

        CoroutineScope(Dispatchers.IO).launch {
            val semaphore = Semaphore(RESOLVE_PARALLEL)
            val emitLock = Mutex()
            var emitted = 0

            // Hands resolved episodes to the download service strictly in the batch's own order.
            suspend fun emitInOrder() {
                while (emitted < total) {
                    val resolved = slots[emitted] ?: return
                    emitted++
                    if (deliver(activity, title, media, resolved)) {
                        started.incrementAndGet()
                    } else {
                        skipped.incrementAndGet()
                    }
                    onProgress(started.get() + skipped.get(), total)
                }
            }

            suspend fun resolve(number: String): Resolved = semaphore.withPermit {
                resolveEpisode(model, media, number, sourceIndex, extractorName, videoIndex)
            }

            coroutineScope {
                order.forEachIndexed { index, number ->
                    launch {
                        val resolved = resolve(number)
                        emitLock.withLock {
                            slots[index] = resolved
                            emitInOrder()
                        }
                    }
                }
            }

            // Episodes the source refused during the parallel wave get another chance one at a
            // time: three requests at once is exactly when a server rate-limits or challenges.
            for ((pass, retryDelay) in RETRY_DELAYS_MS.withIndex()) {
                val retryable = slots.indices.filter { slots[it] is Resolved.Failed }
                if (retryable.isEmpty()) break
                if (pass == 0) {
                    Logger.log("BATCH: retrying ${retryable.size} unresolved episode(s)")
                }
                delay(retryDelay)

                for (index in retryable) {
                    val previous = slots[index] as? Resolved.Failed ?: continue
                    val number = order[index]
                    Logger.log("BATCH: retrying episode $number (${previous.reason})")
                    val resolved = withContext(Dispatchers.IO) {
                        resolveEpisode(model, media, number, sourceIndex, extractorName, videoIndex)
                    }
                    if (resolved is Resolved.Failed) {
                        Logger.log(
                            "BATCH: episode $number still unresolved after retry: ${resolved.reason}"
                        )
                        slots[index] = resolved
                        continue
                    }
                    slots[index] = resolved
                    if (deliver(activity, title, media, resolved)) {
                        // It was counted as skipped when the first attempt failed.
                        skipped.decrementAndGet()
                        started.incrementAndGet()
                        DownloadQueueTracker.upsert(title, number, "Queued")
                    }
                    onProgress(started.get() + skipped.get(), total)
                    Logger.log("BATCH: episode $number queued on retry")
                }
            }

            // Anything still unresolved shows up as a skipped row with its reason.
            slots.forEachIndexed { index, resolved ->
                if (resolved is Resolved.Failed) {
                    reportSkipped(title, order[index], resolved.reason)
                }
            }

            withContext(Dispatchers.Main) {
                onFinished(started.get(), skipped.get())
            }
        }
    }

    /**
     * Puts one resolved episode on the download queue. Returns false when it could not be queued,
     * in which case the episode is reported as skipped.
     */
    private suspend fun deliver(
        activity: Activity,
        title: String,
        media: Media,
        resolved: Resolved
    ): Boolean = when (resolved) {
        is Resolved.Failed -> {
            Logger.log("BATCH: episode ${resolved.number} skipped: ${resolved.reason}")
            reportSkipped(title, resolved.number, resolved.reason)
            false
        }

        is Resolved.Ready -> withContext(Dispatchers.Main) {
            val queued = Helper.startAnimeDownloadService(
                activity,
                title,
                resolved.number,
                resolved.video,
                emptyList(),
                emptyList(),
                media,
                resolved.episode.thumb?.url ?: media.banner ?: media.cover,
                silent = true
            )
            if (queued) {
                val broadcast = Intent(AnimeWatchFragment.ACTION_DOWNLOAD_STARTED)
                broadcast.putExtra(AnimeWatchFragment.EXTRA_EPISODE_NUMBER, resolved.number)
                activity.sendBroadcast(broadcast)
            } else {
                reportSkipped(title, resolved.number, "already downloaded")
            }
            queued
        }
    }

    private fun reportSkipped(title: String, episode: String, reason: String) {
        DownloadQueueTracker.upsert(title, episode, "Skipped: $reason", 0)
    }

    private suspend fun resolveEpisode(
        model: MediaDetailsViewModel,
        media: Media,
        number: String,
        sourceIndex: Int,
        extractorName: String?,
        videoIndex: Int
    ): Resolved {
        val episodes = media.anime?.episodes ?: return Resolved.Failed(number, "no episode list")
        val ep = episodes[number] ?: episodes.values.firstOrNull { it.number == number }
            ?: return Resolved.Failed(number, "episode not found")
        if (ep.link == null) return Resolved.Failed(number, "no episode link")

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
            return Resolved.Failed(
                number,
                if (model.isEpisodeLoading(ep)) "server timed out" else "no videos on this server"
            )
        }
        val extractor = extractors.firstOrNull { it.server.name == extractorName } ?: extractors.first()
        val video = extractor.videos.getOrNull(videoIndex) ?: extractor.videos.first()
        // Torrent entries need the torrent add-on to be resolved first, which the batch cannot do
        // silently; skip them instead of queueing a download that would always fail.
        if (video.file.url.startsWith("magnet:") || video.file.url.endsWith(".torrent")) {
            return Resolved.Failed(number, "torrent link")
        }
        ep.selectedExtractor = extractor.server.name
        ep.selectedVideo = extractor.videos.indexOf(video)
        return Resolved.Ready(ep, video, ep.number)
    }
}

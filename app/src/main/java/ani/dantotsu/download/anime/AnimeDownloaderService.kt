package ani.dantotsu.download.anime

import android.Manifest
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.util.UnstableApi
import ani.dantotsu.FileUrl
import ani.dantotsu.R
import ani.dantotsu.connections.crashlytics.CrashlyticsInterface
import ani.dantotsu.defaultHeaders
import ani.dantotsu.download.DownloadQueueTracker
import ani.dantotsu.download.DownloadedType
import ani.dantotsu.download.DownloadsManager
import ani.dantotsu.download.DownloadsManager.Companion.getSubDirectory
import ani.dantotsu.download.anime.AnimeDownloaderService.AnimeDownloadTask.Companion.getTaskName
import ani.dantotsu.download.findValidName
import ani.dantotsu.download.video.NativeMediaDownloader
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaType
import ani.dantotsu.media.anime.AnimeWatchFragment
import ani.dantotsu.parsers.Video
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.toast
import ani.dantotsu.util.Logger
import com.anggrayudi.storage.file.forceDelete
import com.anggrayudi.storage.file.openOutputStream
import com.google.gson.GsonBuilder
import com.google.gson.InstanceCreator
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeImpl
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.SEpisodeImpl
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SChapterImpl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.HttpURLConnection
import java.net.URL
import java.util.Queue
import java.util.concurrent.ConcurrentLinkedQueue


class AnimeDownloaderService : Service() {

    private lateinit var notificationManager: NotificationManagerCompat
    private lateinit var builder: NotificationCompat.Builder
    private val downloadsManager: DownloadsManager = Injekt.get<DownloadsManager>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val downloadJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val mediaInfoMutex = Mutex()
    private var currentTasks: MutableList<AnimeDownloadTask> =
        java.util.concurrent.CopyOnWriteArrayList()

    @Volatile
    private var workerJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? {
        // This is only required for bound services.
        return null
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = NotificationManagerCompat.from(this)
        builder =
            NotificationCompat.Builder(this, Notifications.CHANNEL_DOWNLOADER_PROGRESS).apply {
                setContentTitle("Anime Download Progress")
                setSmallIcon(R.drawable.ic_download_24)
                priority = NotificationCompat.PRIORITY_DEFAULT
                setOnlyAlertOnce(true)
                setProgress(100, 0, false)
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                builder.build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, builder.build())
        }
        ContextCompat.registerReceiver(
            this,
            cancelReceiver,
            IntentFilter(ACTION_CANCEL_DOWNLOAD),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        // Pending tasks stay in the queue: the service is restarted when it is next needed.
        downloadJobs.clear()
        workerJob = null
        AnimeServiceDataSingleton.isServiceRunning = false
        try {
            unregisterReceiver(cancelReceiver)
        } catch (_: Exception) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startWorker()
        return START_NOT_STICKY
    }

    private fun startWorker() {
        if (workerJob?.isActive == true) return
        workerJob = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                processQueue()
            } finally {
                workerJob = null
                // A task can be queued in the moment between the loop ending and this finally;
                // pick it up instead of shutting the service down on it.
                if (AnimeServiceDataSingleton.downloadQueue.isNotEmpty()) {
                    startWorker()
                } else {
                    withContext(Dispatchers.Main) { stopSelf() }
                }
            }
        }
    }

    private fun parallelDownloads(): Int {
        return (PrefManager.getVal(PrefName.ParallelDownloads) as? Int ?: 3).coerceIn(1, 6)
    }

    /**
     * Runs up to [parallelDownloads] downloads at once; a new episode starts as soon as one slot
     * frees up instead of after the whole queue is drained one by one.
     */
    private suspend fun CoroutineScope.processQueue() {
        val running = mutableMapOf<String, Job>()
        while (true) {
            val maxParallel = parallelDownloads()
            while (running.size < maxParallel) {
                val task = AnimeServiceDataSingleton.downloadQueue.poll() ?: break
                val name = task.getTaskName()
                currentTasks.add(task)
                val job = launch {
                    try {
                        download(task)
                    } finally {
                        currentTasks.removeAll { it.getTaskName() == name }
                        downloadJobs.remove(name)
                    }
                }
                running[name] = job
                downloadJobs[name] = job
            }
            val finished = running.entries.firstOrNull { it.value.isCompleted }
            if (finished != null) {
                running.remove(finished.key)
                updateNotification()
                continue
            }
            if (running.isEmpty() && AnimeServiceDataSingleton.downloadQueue.isEmpty()) break
            delay(300)
        }
    }

    fun cancelDownload(taskName: String) {
        AnimeServiceDataSingleton.downloadQueue.removeAll { it.getTaskName() == taskName }
        currentTasks.removeAll { it.getTaskName() == taskName }
        downloadJobs.remove(taskName)?.cancel()
        DownloadQueueTracker.upsert(
            taskName.substringBefore("/"),
            taskName.substringAfter("/"),
            "Cancelled",
            0
        )
        updateNotification()
    }

    private fun updateNotification() {
        val pendingDownloads = AnimeServiceDataSingleton.downloadQueue.size
        val text = if (pendingDownloads > 0) {
            "Pending downloads: $pendingDownloads"
        } else {
            "All downloads completed"
        }
        builder.setContentText(text)
        postNotification()
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** Notification updates happen from IO workers, so always post them on the main thread. */
    private fun postNotification() {
        if (!canPostNotifications()) return
        val notification = try {
            builder.build()
        } catch (_: Exception) {
            return
        }
        mainHandler.post {
            try {
                notificationManager.notify(NOTIFICATION_ID, notification)
            } catch (_: Exception) {
            }
        }
    }

    private fun mimeFor(extension: String): String = when (extension.lowercase()) {
        "mkv" -> "video/x-matroska"
        "ts" -> "video/mp2t"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        else -> "video/mp4"
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    suspend fun download(task: AnimeDownloadTask) {
        val taskName = getTaskName(task.title, task.episode)
        withContext(Dispatchers.IO) {
            var createdFile: DocumentFile? = null
            try {
                builder.setContentText("Downloading $taskName")
                DownloadQueueTracker.upsert(task.title, task.episode, "Downloading", 0)
                postNotification()

                val baseOutputDir = getSubDirectory(
                    this@AnimeDownloaderService,
                    MediaType.ANIME,
                    false,
                    task.title
                ) ?: throw Exception("No downloads folder set. Pick one in Settings first.")
                val outputDir = getSubDirectory(
                    this@AnimeDownloaderService,
                    MediaType.ANIME,
                    true,
                    task.title,
                    task.episode
                ) ?: throw Exception("No downloads folder set. Pick one in Settings first.")

                val url = task.video.file.url
                if (url.startsWith("magnet:") || url.endsWith(".torrent", ignoreCase = true)) {
                    throw Exception("Torrent episodes cannot be downloaded directly")
                }

                val headers = task.video.file.headers.toMutableMap()
                if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                    headers["User-Agent"] = defaultHeaders["User-Agent"] ?: ""
                }

                val predicted = NativeMediaDownloader.predictExtension(url, task.video.format)
                val outputName = taskName.findValidName()
                outputDir.findFile("$outputName.$predicted")?.delete()
                var file = outputDir.createFile(mimeFor(predicted), "$outputName.$predicted")
                    ?: throw Exception("Failed to create the episode file")
                createdFile = file

                var lastNotified = 0L
                val result = file.openOutputStream(this@AnimeDownloaderService, false)?.use { stream ->
                    NativeMediaDownloader.download(
                        url = url,
                        headers = headers,
                        videoType = task.video.format,
                        output = stream
                    ) { percent, _, _ ->
                        val shown = percent.coerceIn(0, 99)
                        DownloadQueueTracker.upsert(
                            task.title,
                            task.episode,
                            "Downloading",
                            shown
                        )
                        broadcastDownloadProgress(task.episode, shown)
                        val now = System.currentTimeMillis()
                        if (now - lastNotified > 2000) {
                            lastNotified = now
                            builder.setProgress(100, shown, false)
                            postNotification()
                        }
                    }
                } ?: throw Exception("Could not open the episode file for writing")

                if (result.extension != predicted) {
                    // The server answered with another container than the link suggested.
                    if (file.renameTo("$outputName.${result.extension}")) {
                        file = outputDir.findFile("$outputName.${result.extension}") ?: file
                        createdFile = file
                    }
                }

                Logger.log("Downloaded $taskName (${result.bytes} bytes)")

                builder.setContentText("$taskName Download completed")
                builder.setProgress(0, 0, false)
                postNotification()
                snackString("$taskName Download completed")

                PrefManager.getAnimeDownloadPreferences().edit().putString(
                    task.getTaskName(),
                    task.video.file.url
                ).apply()
                downloadsManager.addDownload(
                    DownloadedType(
                        task.title,
                        task.episode,
                        MediaType.ANIME,
                    )
                )

                currentTasks.removeAll { it.getTaskName() == task.getTaskName() }
                DownloadQueueTracker.upsert(task.title, task.episode, "Completed", 100)
                broadcastDownloadFinished(task.episode)

                saveMediaInfo(task, baseOutputDir)
            } catch (e: CancellationException) {
                Logger.log("Download cancelled: $taskName")
                try {
                    createdFile?.delete()
                } catch (_: Exception) {
                }
                DownloadQueueTracker.removeByTaskName(taskName)
                throw e
            } catch (e: Exception) {
                Logger.log("Exception while downloading file: ${e.message}")
                snackString("Download failed: ${e.message}")
                e.printStackTrace()
                try {
                    createdFile?.delete()
                } catch (_: Exception) {
                }
                Injekt.get<CrashlyticsInterface>().logException(e)
                DownloadQueueTracker.upsert(
                    task.title,
                    task.episode,
                    "Failed: ${e.message ?: "unknown error"}",
                    0
                )
                broadcastDownloadFailed(task.episode)
            }
        }
    }

    private fun saveMediaInfo(task: AnimeDownloadTask, directory: DocumentFile) {
        CoroutineScope(Dispatchers.IO).launch {
            // Several episodes of the same anime can finish at once; they all rewrite the same
            // media.json, so serialise it or they delete each other's file mid-write.
            mediaInfoMutex.withLock {
                writeMediaInfo(task, directory)
            }
        }
    }

    private suspend fun writeMediaInfo(task: AnimeDownloadTask, directory: DocumentFile) {
        withContext(Dispatchers.IO) {
            directory.findFile("media.json")?.forceDelete(this@AnimeDownloaderService)
            val file = directory.createFile("application/json", "media.json")
                ?: throw Exception("File not created")
            val episodeDirectory =
                getSubDirectory(
                    this@AnimeDownloaderService,
                    MediaType.ANIME,
                    false,
                    task.title,
                    task.episode
                )
                    ?: throw Exception("Directory not found")

            val gson = GsonBuilder()
                .registerTypeAdapter(SChapter::class.java, InstanceCreator<SChapter> {
                    SChapterImpl() // Provide an instance of SChapterImpl
                })
                .registerTypeAdapter(SAnime::class.java, InstanceCreator<SAnime> {
                    SAnimeImpl() // Provide an instance of SAnimeImpl
                })
                .registerTypeAdapter(SEpisode::class.java, InstanceCreator<SEpisode> {
                    SEpisodeImpl() // Provide an instance of SEpisodeImpl
                })
                .create()
            val mediaJson = gson.toJson(task.sourceMedia)
            val media = gson.fromJson(mediaJson, Media::class.java)
            if (media != null) {
                media.cover = media.cover?.let { downloadImage(it, directory, "cover.jpg") }
                media.banner = media.banner?.let { downloadImage(it, directory, "banner.jpg") }
                if (task.episodeImage != null) {
                    media.anime?.episodes?.get(task.episode)?.let { episode ->
                        episode.thumb = downloadImage(
                            task.episodeImage,
                            episodeDirectory,
                            "episodeImage.jpg"
                        )?.let {
                            FileUrl(
                                it
                            )
                        }
                    }
                    downloadImage(task.episodeImage, episodeDirectory, "episodeImage.jpg")
                }

                val jsonString = gson.toJson(media)
                withContext(Dispatchers.Main) {
                    try {
                        file.openOutputStream(this@AnimeDownloaderService, false).use { output ->
                            if (output == null) throw Exception("Output stream is null")
                            output.write(jsonString.toByteArray())
                        }
                    } catch (e: android.system.ErrnoException) {
                        e.printStackTrace()
                        Toast.makeText(
                            this@AnimeDownloaderService,
                            "Error while saving: ${e.localizedMessage}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private suspend fun downloadImage(url: String, directory: DocumentFile, name: String): String? =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            println("Downloading url $url")
            try {
                connection = URL(url).openConnection() as HttpURLConnection
                connection.connect()
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception("Server returned HTTP ${connection.responseCode} ${connection.responseMessage}")
                }

                directory.findFile(name)?.forceDelete(this@AnimeDownloaderService)
                val file =
                    directory.createFile("image/jpeg", name) ?: throw Exception("File not created")
                file.openOutputStream(this@AnimeDownloaderService, false).use { output ->
                    if (output == null) throw Exception("Output stream is null")
                    connection.inputStream.use { input ->
                        input.copyTo(output)
                    }
                }
                return@withContext file.uri.toString()
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@AnimeDownloaderService,
                        "Exception while saving ${name}: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                null
            } finally {
                connection?.disconnect()
            }
        }

    private fun broadcastDownloadStarted(episodeNumber: String) {
        val intent = Intent(AnimeWatchFragment.ACTION_DOWNLOAD_STARTED).apply {
            putExtra(AnimeWatchFragment.EXTRA_EPISODE_NUMBER, episodeNumber)
        }
        sendBroadcast(intent)
    }

    private fun broadcastDownloadFinished(episodeNumber: String) {
        val intent = Intent(AnimeWatchFragment.ACTION_DOWNLOAD_FINISHED).apply {
            putExtra(AnimeWatchFragment.EXTRA_EPISODE_NUMBER, episodeNumber)
        }
        sendBroadcast(intent)
    }

    private fun broadcastDownloadFailed(episodeNumber: String) {
        val intent = Intent(AnimeWatchFragment.ACTION_DOWNLOAD_FAILED).apply {
            putExtra(AnimeWatchFragment.EXTRA_EPISODE_NUMBER, episodeNumber)
        }
        sendBroadcast(intent)
    }

    private fun broadcastDownloadProgress(episodeNumber: String, progress: Int) {
        val intent = Intent(AnimeWatchFragment.ACTION_DOWNLOAD_PROGRESS).apply {
            putExtra(AnimeWatchFragment.EXTRA_EPISODE_NUMBER, episodeNumber)
            putExtra("progress", progress)
        }
        sendBroadcast(intent)
    }

    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_CANCEL_DOWNLOAD) {
                val taskName = intent.getStringExtra(EXTRA_TASK_NAME)
                taskName?.let {
                    cancelDownload(it)
                }
            }
        }
    }


    data class AnimeDownloadTask(
        val title: String,
        val episode: String,
        val video: Video,
        val subtitle: List<Pair<String, String>> = emptyList(),
        val audio: List<Pair<String, String>> = emptyList(),
        val sourceMedia: Media? = null,
        val episodeImage: String? = null,
        val retries: Int = 2,
        val simultaneousDownloads: Int = 2,
        var sessionId: Long = -1
    ) {
        fun getTaskName(): String {
            return "${title.replace("/", "")}/${episode.replace("/", "")}"
        }

        companion object {
            fun getTaskName(title: String, episode: String): String {
                return "${title.replace("/", "")}/${episode.replace("/", "")}"
            }
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 1103
        const val ACTION_CANCEL_DOWNLOAD = "action_cancel_download"
        const val EXTRA_TASK_NAME = "extra_task_name"
    }
}

object AnimeServiceDataSingleton {
    var video: Video? = null
    var downloadQueue: Queue<AnimeDownloaderService.AnimeDownloadTask> = ConcurrentLinkedQueue()

    @Volatile
    var isServiceRunning: Boolean = false
}

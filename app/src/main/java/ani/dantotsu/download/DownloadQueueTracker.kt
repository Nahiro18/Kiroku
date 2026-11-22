package ani.dantotsu.download

/**
 * Live view of everything the in-app downloader is doing. The download service writes into it,
 * the Downloads screen reads from it. Kept in memory on purpose: a download that dies with the
 * process is not something the user can act on anyway.
 */
object DownloadQueueTracker {
    data class Entry(
        val taskName: String,
        val title: String,
        val episode: String,
        val status: String,
        val percent: Int
    )

    private val entries = LinkedHashMap<String, Entry>()
    private val lock = Any()

    /** Called on the thread that changed the entry. Listeners must dispatch to the UI themselves. */
    @Volatile
    var listener: (() -> Unit)? = null

    fun upsert(title: String, episode: String, status: String, percent: Int = 0) {
        val taskName = "${title.replace("/", "")}/${episode.replace("/", "")}"
        synchronized(lock) {
            val previous = entries[taskName]
            entries[taskName] = Entry(
                taskName = taskName,
                title = title,
                episode = episode,
                status = status,
                percent = if (status == "Completed") 100 else percent.coerceIn(0, 100)
            )
            if (previous == entries[taskName]) return
        }
        listener?.invoke()
    }

    fun remove(title: String, episode: String) {
        removeByTaskName("${title.replace("/", "")}/${episode.replace("/", "")}")
    }

    fun removeByTaskName(taskName: String) {
        synchronized(lock) {
            if (entries.remove(taskName) == null) return
        }
        listener?.invoke()
    }

    fun snapshot(): List<Entry> = synchronized(lock) { entries.values.toList() }

    fun activeCount(): Int = synchronized(lock) {
        entries.values.count { it.status == "Downloading" || it.status == "Queued" }
    }

    /** Drops finished entries. Called when the user clears the finished downloads from the screen. */
    fun clearFinished() {
        synchronized(lock) {
            entries.entries.removeAll { it.value.status != "Downloading" && it.value.status != "Queued" }
        }
        listener?.invoke()
    }
}

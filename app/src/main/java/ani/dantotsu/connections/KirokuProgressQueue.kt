package ani.dantotsu.connections

import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.currContext
import ani.dantotsu.toast
import ani.dantotsu.util.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class KirokuQueuedProgress(
    val mediaId: Int,
    val malId: Int? = null,
    val anime: Boolean = false,
    val progress: Int,
    val status: String = "CURRENT",
    val savedAt: Long = 0L,
)

object KirokuProgressQueue {

    private const val FILE_NAME = "kiroku_progress_queue.json"
    private const val MAX_ITEMS = 200

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val lock = Mutex()

    private fun file(): File? {
        val context = currContext() ?: return null
        return File(context.filesDir, FILE_NAME)
    }

    fun pendingCount(): Int = read().size

    suspend fun enqueue(update: KirokuQueuedProgress) {
        lock.withLock {
            val current = read().toMutableList()
            val existing = current.indexOfFirst {
                it.mediaId == update.mediaId && it.anime == update.anime
            }
            if (existing >= 0) {
                if (current[existing].progress > update.progress) return
                current.removeAt(existing)
            }
            current.add(update.copy(savedAt = System.currentTimeMillis()))
            write(current.takeLast(MAX_ITEMS))
        }
    }

    suspend fun sync() {
        if (Anilist.userid == null) return
        lock.withLock {
            val pending = read()
            if (pending.isEmpty()) return
            val remaining = pending.toMutableList()
            for (update in pending) {
                val ok = try {
                    Anilist.mutation.editList(
                        mediaID = update.mediaId,
                        progress = update.progress,
                        status = update.status,
                    )
                } catch (e: Exception) {
                    Logger.log("Kiroku progress sync failed: ${e.message}")
                    false
                }
                if (!ok) break
                remaining.remove(update)
            }
            write(remaining)
            if (remaining.size != pending.size) {
                val context = currContext() ?: return
                val done = pending.size - remaining.size
                toast(context.getString(R.string.progress_synced, done))
            }
        }
    }

    private fun read(): List<KirokuQueuedProgress> {
        val target = file() ?: return emptyList()
        if (!target.isFile) return emptyList()
        return runCatching {
            json.decodeFromString<List<KirokuQueuedProgress>>(target.readText())
        }.onFailure {
            Logger.log("Kiroku progress queue unreadable: ${it.message}")
        }.getOrDefault(emptyList())
    }

    private fun write(items: List<KirokuQueuedProgress>) {
        val target = file() ?: return
        runCatching {
            if (items.isEmpty()) {
                target.delete()
            } else {
                target.writeText(json.encodeToString(items))
            }
        }.onFailure {
            Logger.log("Kiroku progress queue not saved: ${it.message}")
        }
    }
}

package ani.dantotsu.ai

import android.content.Context
import ani.dantotsu.currContext
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

object PersonModelManager {

    private const val MODEL_FILE_NAME = "person_detect_v1.1_int8.tflite"
    private const val MODEL_URL =
        "https://github.com/Nahiro18/Kiroku/releases/download/ai-model-v1/kiroku_person_v1.1_int8.tflite"
    private const val PREF_MODEL_PATH = "ai_person_model_path"

    private val LEGACY_PREFS = listOf(
        "yolo_v11_path",
        "yolo_v8_path",
        "yolo_selected_model",
        "yolo_selected_delegate"
    )
    private val LEGACY_FILES = listOf("yolov11n_seg.tflite", "yolov8n_seg.tflite")

    private val lock = Mutex()
    private val isDownloading = AtomicBoolean(false)
    private var legacyPurged = false

    fun isCompatible(): Boolean =
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P

    fun isDownloaded(): Boolean {
        ensureCleanLegacy()
        val file = getModelFile()
        return file != null && file.length() > 0
    }

    fun getModelFile(): File? {
        val storedPath = PrefManager.getCustomVal(PREF_MODEL_PATH, "")
        if (storedPath.isNotBlank()) {
            val stored = File(storedPath)
            if (stored.exists() && stored.length() > 0) return stored
        }
        val context = currContext() ?: return null
        val fallback = File(modelsDir(context), MODEL_FILE_NAME)
        return if (fallback.exists() && fallback.length() > 0) fallback else null
    }

    fun isDownloadingNow(): Boolean = isDownloading.get()

    fun delete(): Boolean {
        val acquired = lock.tryLock()
        if (!acquired) return false
        return try {
            getModelFile()?.let { runCatching { it.delete() } }
            val context = currContext()
            if (context != null) {
                runCatching { File(modelsDir(context), MODEL_FILE_NAME).delete() }
            }
            PrefManager.removeCustomVal(PREF_MODEL_PATH)
            !isDownloaded()
        } finally {
            lock.unlock()
        }
    }

    suspend fun download(onProgress: ((Double) -> Unit)? = null): Boolean {
        return withContext(Dispatchers.IO) {
            val acquired = lock.tryLock()
            if (!acquired) return@withContext false
            try {
                if (isDownloaded()) return@withContext true
                if (isDownloading.get()) return@withContext false

                val context = currContext() ?: return@withContext false
                isDownloading.set(true)

                try {
                    val targetFile = File(modelsDir(context), MODEL_FILE_NAME)
                    var success = false

                    for (attempt in 1..3) {
                        try {
                            if (downloadFile(MODEL_URL, targetFile, onProgress)) {
                                success = true
                                break
                            }
                        } catch (e: Exception) {
                            Logger.log("Person model download attempt $attempt failed: ${e.message}")
                            if (attempt < 3) Thread.sleep(2000)
                        }
                    }

                    if (success) {
                        PrefManager.setCustomVal(PREF_MODEL_PATH, targetFile.absolutePath)
                        Logger.log("Person model downloaded: ${targetFile.length()} bytes")
                        true
                    } else {
                        targetFile.delete()
                        false
                    }
                } catch (e: Exception) {
                    Logger.log("Person model download failed: ${e.message}")
                    false
                } finally {
                    isDownloading.set(false)
                }
            } finally {
                lock.unlock()
            }
        }
    }

    private suspend fun downloadFile(
        url: String,
        targetFile: File,
        onProgress: ((Double) -> Unit)?
    ): Boolean {
        var currentUrl = url
        var redirectCount = 0

        while (redirectCount < 5) {
            val connection = URL(currentUrl).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 30000
                connection.readTimeout = 180000
                connection.requestMethod = "GET"
                connection.addRequestProperty("User-Agent", "Kiroku/1.0")
                connection.setInstanceFollowRedirects(false)

                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        val contentLength = connection.contentLengthLong
                        if (contentLength <= 0) return false

                        connection.inputStream.use { input ->
                            FileOutputStream(targetFile).use { output ->
                                val buffer = ByteArray(16384)
                                var total = 0L
                                var len: Int
                                while (input.read(buffer).also { len = it } != -1) {
                                    output.write(buffer, 0, len)
                                    total += len
                                    val progress = (total.toDouble() / contentLength) * 100
                                    withContext(Dispatchers.Main) { onProgress?.invoke(progress) }
                                }
                            }
                        }

                        return targetFile.exists() && targetFile.length() > 0
                    }

                    in 300..399 -> {
                        val redirectUrl = connection.getHeaderField("Location") ?: return false
                        currentUrl = redirectUrl
                        redirectCount++
                    }

                    else -> return false
                }
            } catch (e: Exception) {
                Logger.log("Person model download error: ${e.message}")
                return false
            } finally {
                connection.disconnect()
            }
        }
        return false
    }

    private fun modelsDir(context: Context): File =
        File(context.filesDir, "models").apply { if (!exists()) mkdirs() }

    private fun ensureCleanLegacy() {
        if (legacyPurged) return
        legacyPurged = true
        val context = currContext() ?: return
        val dir = modelsDir(context)
        LEGACY_FILES.forEach { name -> runCatching { File(dir, name).delete() } }
        LEGACY_PREFS.forEach { key -> runCatching { PrefManager.removeCustomVal(key) } }
    }
}

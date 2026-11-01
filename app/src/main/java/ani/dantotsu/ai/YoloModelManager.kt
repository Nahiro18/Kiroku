package ani.dantotsu.ai

import android.content.Context
import android.util.Log
import ani.dantotsu.currContext
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

object YoloModelManager {

    private const val MODEL_NAME = "yolov8n_seg.tflite"
    private const val MODEL_URL = "https://huggingface.co/STMicroelectronics/yolov8n_seg/resolve/main/yolov8n_seg.tflite"
    private const val MODEL_SIZE_MB = 6.2
    private const val PREF_KEY_DOWNLOADED = "yolo_model_downloaded"
    private const val PREF_KEY_PATH = "yolo_model_path"

    private val lock = Mutex()
    private val isDownloading = AtomicBoolean(false)
    private var modelFile: File? = null

    fun isDownloaded(): Boolean {
        val path = PrefManager.getCustomVal(PREF_KEY_PATH, "")
        if (path.isBlank()) return false
        val file = File(path)
        return file.exists() && file.length() > 0
    }

    fun getModelPath(): String? {
        return if (isDownloaded()) PrefManager.getCustomVal(PREF_KEY_PATH, "") else null
    }

    fun getModelSizeMB(): Double = MODEL_SIZE_MB

    fun getModelFile(): File? {
        if (modelFile != null && modelFile!!.exists()) return modelFile
        val path = getModelPath() ?: return null
        val file = File(path)
        if (file.exists()) {
            modelFile = file
            return file
        }
        return null
    }

    fun isCompatible(): Boolean {
        return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P
    }

    suspend fun downloadModel(onProgress: ((Double) -> Unit)? = null): Boolean {
        return withContext(Dispatchers.IO) {
            val acquired = lock.tryLock()
            if (!acquired) return@withContext false
            try {
                if (isDownloaded()) return@withContext true
                if (isDownloading.get()) return@withContext false

                isDownloading.set(true)
                val context = currContext() ?: return@withContext false

                return@withContext try {
                    val targetDir = File(context.filesDir, "models")
                    if (!targetDir.exists()) targetDir.mkdirs()

                    val targetFile = File(targetDir, MODEL_NAME)
                    val url = URL(MODEL_URL)
                    val connection = url.openConnection()
                    connection.connectTimeout = 30000
                    connection.readTimeout = 60000
                    val contentLength = connection.contentLength.toLong()

                    connection.inputStream.use { input ->
                        FileOutputStream(targetFile).use { output ->
                            val buffer = ByteArray(8192)
                            var total = 0L
                            var len: Int
                            while (input.read(buffer).also { len = it } != -1) {
                                output.write(buffer, 0, len)
                                total += len
                                if (contentLength > 0) {
                                    onProgress?.invoke((total.toDouble() / contentLength) * 100)
                                }
                            }
                        }
                    }

                    if (targetFile.exists() && targetFile.length() > 0) {
                        PrefManager.setCustomVal(PREF_KEY_PATH, targetFile.absolutePath)
                        PrefManager.setCustomVal(PREF_KEY_DOWNLOADED, true)
                        modelFile = targetFile
                        Logger.log("YOLO model downloaded successfully: ${targetFile.length()} bytes")
                        true
                    } else {
                        targetFile.delete()
                        false
                    }
                } catch (e: Exception) {
                    Logger.log("YOLO model download failed: ${e.message}")
                    false
                } finally {
                    isDownloading.set(false)
                }
            } finally {
                lock.unlock()
            }
        }
    }

    fun deleteModel(): Boolean {
        val acquired = lock.tryLock()
        if (!acquired) return false
        try {
            val path = PrefManager.getCustomVal(PREF_KEY_PATH, "")
            if (path.isNotBlank()) {
                File(path).delete()
            }
            PrefManager.removeCustomVal(PREF_KEY_PATH)
            PrefManager.removeCustomVal(PREF_KEY_DOWNLOADED)
            modelFile = null
            true
        } finally {
            lock.unlock()
        }
    }

    fun isDownloadingNow(): Boolean = isDownloading.get()
}
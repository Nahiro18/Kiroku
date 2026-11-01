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
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

object YoloModelManager {

    private const val V11_MODEL_NAME = "yolov11n_seg.tflite"
    private const val V8_MODEL_NAME = "yolov8n_seg.tflite"
    private const val V11_URL = "https://huggingface.co/EdgeFirst/yolo11-seg/resolve/main/tflite/yolo11n-seg-int8.tflite"
    private const val V8_URL = "https://huggingface.co/EdgeFirst/yolov8-seg/resolve/main/tflite/yolov8n-seg-int8.tflite"
    private const val V11_SIZE_MB = 3.2
    private const val V8_SIZE_MB = 3.7

    private const val PREF_V11_PATH = "yolo_v11_path"
    private const val PREF_V8_PATH = "yolo_v8_path"
    private const val PREF_SELECTED_MODEL = "yolo_selected_model"
    private const val PREF_SELECTED_DELEGATE = "yolo_selected_delegate"

    enum class Model { V11, V8 }
    enum class Delegate { GPU, CPU }

    private val lock = Mutex()
    private val isDownloading = AtomicBoolean(false)

    fun getSelectedModel(): Model {
        return PrefManager.getCustomVal(PREF_SELECTED_MODEL, "V11").let {
            Model.valueOf(it)
        }
    }

    fun setSelectedModel(model: Model) {
        PrefManager.setCustomVal(PREF_SELECTED_MODEL, model.name)
    }

    fun getSelectedDelegate(): Delegate {
        return PrefManager.getCustomVal(PREF_SELECTED_DELEGATE, "GPU").let {
            Delegate.valueOf(it)
        }
    }

    fun setSelectedDelegate(delegate: Delegate) {
        PrefManager.setCustomVal(PREF_SELECTED_DELEGATE, delegate.name)
    }

    fun isModelDownloaded(model: Model): Boolean {
        val path = getModelPath(model)
        return path != null && File(path).exists() && File(path).length() > 0
    }

    fun getModelPath(model: Model): String? {
        val pref = if (model == Model.V11) PREF_V11_PATH else PREF_V8_PATH
        val path = PrefManager.getCustomVal(pref, "")
        return if (path.isNotBlank() && File(path).exists()) path else null
    }

    fun getModelSizeMB(model: Model): Double = if (model == Model.V11) V11_SIZE_MB else V8_SIZE_MB

    fun getModelFile(model: Model): File? {
        val path = getModelPath(model) ?: return null
        val file = File(path)
        return if (file.exists()) file else null
    }

    fun isCompatible(): Boolean {
        return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P
    }

    fun deleteModel(model: Model): Boolean {
        val acquired = lock.tryLock()
        if (!acquired) return false
        return try {
            val pref = if (model == Model.V11) PREF_V11_PATH else PREF_V8_PATH
            val path = PrefManager.getCustomVal(pref, "")
            if (path.isNotBlank()) {
                File(path).delete()
            }
            PrefManager.removeCustomVal(pref)
            true
        } finally {
            lock.unlock()
        }
    }

    fun deleteAllModels(): Boolean {
        val acquired = lock.tryLock()
        if (!acquired) return false
        return try {
            deleteModel(Model.V11)
            deleteModel(Model.V8)
            true
        } finally {
            lock.unlock()
        }
    }

    private data class ModelInfo(
        val fileName: String,
        val url: String,
        val sizeMB: Double,
        val prefKey: String
    )

    private fun getModelInfo(model: Model): ModelInfo {
        return if (model == Model.V11) {
            ModelInfo(V11_MODEL_NAME, V11_URL, V11_SIZE_MB, PREF_V11_PATH)
        } else {
            ModelInfo(V8_MODEL_NAME, V8_URL, V8_SIZE_MB, PREF_V8_PATH)
        }
    }

    suspend fun downloadModel(model: Model, onProgress: ((Double) -> Unit)? = null): Boolean {
        return withContext(Dispatchers.IO) {
            val acquired = lock.tryLock()
            if (!acquired) return@withContext false
            try {
                if (isModelDownloaded(model)) return@withContext true
                if (isDownloading.get()) return@withContext false

                isDownloading.set(true)
                val context = currContext() ?: return@withContext false

                return@withContext try {
                    val info = getModelInfo(model)
                    val targetDir = File(context.filesDir, "models")
                    if (!targetDir.exists()) targetDir.mkdirs()

                    val targetFile = File(targetDir, info.fileName)
                    var success = false

                    for (attempt in 1..3) {
                        try {
                            val downloaded = downloadFile(info.url, targetFile, info.sizeMB, onProgress)
                            if (downloaded) {
                                success = true
                                break
                            }
                        } catch (e: Exception) {
                            Logger.log("YOLO ${model.name} download attempt $attempt failed: ${e.message}")
                            if (attempt < 3) Thread.sleep(2000)
                        }
                    }

                    if (success) {
                        PrefManager.setCustomVal(info.prefKey, targetFile.absolutePath)
                        Logger.log("YOLO ${model.name} downloaded: ${targetFile.length()} bytes")
                        true
                    } else {
                        targetFile.delete()
                        false
                    }
                } catch (e: Exception) {
                    Logger.log("YOLO ${model.name} download failed: ${e.message}")
                    false
                } finally {
                    isDownloading.set(false)
                }
            } finally {
                lock.unlock()
            }
        }
    }

    private fun downloadFile(url: String, targetFile: File, expectedSizeMB: Double, onProgress: ((Double) -> Unit)?): Boolean {
        var currentUrl = url
        var redirectCount = 0
        var success = false

        while (redirectCount < 5 && !success) {
            val connection = URL(currentUrl).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 30000
                connection.readTimeout = 180000
                connection.requestMethod = "GET"
                connection.addRequestProperty("User-Agent", "Kiroku/1.0")
                connection.setFollowRedirects(false)

                val responseCode = connection.responseCode
                if (responseCode == 200) {
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
                                if (contentLength > 0) {
                                    onProgress?.invoke((total.toDouble() / connection.contentLengthLong) * 100)
                                }
                            }
                        }
                    }

                    success = targetFile.exists() && targetFile.length() > 0
                    return success
                } else if (responseCode == 301 || responseCode == 302 || responseCode == 303 || responseCode == 307 || responseCode == 308) {
                    val redirectUrl = connection.getHeaderField("Location")
                    if (redirectUrl != null && redirectCount < 5) {
                        currentUrl = redirectUrl
                        redirectCount++
                        connection.disconnect()
                        continue
                    }
                }
                return false
            } catch (e: Exception) {
                Logger.log("Download error: ${e.message}")
                return false
            } finally {
                connection?.disconnect()
            }
        }
        return false
    }

    fun isDownloadingNow(): Boolean = isDownloading.get()
}
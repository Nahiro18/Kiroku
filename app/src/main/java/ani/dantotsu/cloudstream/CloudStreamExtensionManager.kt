package ani.dantotsu.cloudstream

import android.content.Context
import ani.dantotsu.App
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Downloads, validates, stores and removes CloudStream `.cs3` plugins.
 *
 * A `.cs3` file is a zip archive that contains `manifest.json` and `classes.dex`.
 * Plugins are kept in the app's private storage so they survive restarts and do not
 * require an APK install, exactly like CloudStream's own plugin manager.
 */
object CloudStreamExtensionManager {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val networkHelper: NetworkHelper by lazy { Injekt.get() }
    private val mutex = Mutex()

    private val _installed = MutableStateFlow<List<CloudStreamInstalled>>(emptyList())
    val installed = _installed.asStateFlow()

    private val _installing = MutableStateFlow<Set<String>>(emptySet())
    val installing = _installing.asStateFlow()

    val context: Context
        get() = App.instance ?: App.context
            ?: error("CloudStream extensions require an application context")

    val pluginsDir: File
        get() = File(context.filesDir, "cloudstream/plugins").apply { mkdirs() }

    fun initialize() {
        _installed.value = readInstalledFromDisk()
        CloudStreamRepositoryManager.updateInstalledCount(_installed.value.size)
    }

    private fun safeFileName(internalName: String): String {
        val cleaned = internalName.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_')
        return (cleaned.ifBlank { "plugin" }) + "_" +
            internalName.hashCode().toUInt().toString(16)
    }

    private fun metadataFile(fileName: String): File =
        File(pluginsDir, fileName.removeSuffix(".cs3") + ".json")

    private fun readInstalledFromDisk(): List<CloudStreamInstalled> {
        val dir = pluginsDir
        val items = mutableListOf<CloudStreamInstalled>()
        dir.listFiles { file -> file.extension == "json" }?.forEach { meta ->
            try {
                val installed = json.decodeFromString<CloudStreamInstalled>(meta.readText())
                val pluginFile = File(dir, installed.fileName)
                if (pluginFile.isFile && pluginFile.length() > 0) {
                    items.add(installed)
                } else {
                    meta.delete()
                }
            } catch (e: Exception) {
                Logger.log("CloudStream: unreadable plugin metadata ${meta.name}: ${e.message}")
            }
        }
        return items.sortedBy { it.displayName.lowercase() }
    }

    fun installedFor(internalName: String): CloudStreamInstalled? =
        _installed.value.firstOrNull { it.internalName.equals(internalName, true) }

    fun hasUpdate(installed: CloudStreamInstalled, available: CloudStreamPlugin): Boolean =
        available.version > installed.version && available.url.isNotBlank()

    private fun verifyHash(bytes: ByteArray, expected: String?): Boolean {
        if (expected.isNullOrBlank()) return true
        val wanted = expected
            .removePrefix("sha256-")
            .removePrefix("sha256:")
            .removePrefix("SHA-256:")
            .trim()
            .lowercase()
        if (wanted.length != 64) return true
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        return actual == wanted
    }

    private fun readManifest(bytes: ByteArray): CloudStreamPluginManifest? {
        var hasDex = false
        var manifest: CloudStreamPluginManifest? = null
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                when (entry.name) {
                    "classes.dex" -> hasDex = true
                    "manifest.json" -> {
                        val text = zip.readBytes().toString(Charsets.UTF_8)
                        manifest = runCatching {
                            json.decodeFromString<CloudStreamPluginManifest>(text)
                        }.getOrNull()
                    }
                }
                entry = zip.nextEntry
            }
        }
        if (!hasDex) return null
        if (manifest?.pluginClassName.isNullOrBlank()) return null
        return manifest
    }

    suspend fun install(plugin: CloudStreamPlugin): Result<CloudStreamInstalled> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (plugin.url.isBlank()) {
                    return@withLock Result.failure(IllegalArgumentException("Missing plugin URL"))
                }
                _installing.value = _installing.value + plugin.internalName
                try {
                    val request = Request.Builder()
                        .url(plugin.url)
                        .header("User-Agent", USER_AGENT)
                        .build()
                    val bytes = networkHelper.client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            return@withLock Result.failure(
                                IllegalStateException("Download failed: HTTP ${response.code}")
                            )
                        }
                        response.body.bytes()
                    }
                    if (bytes.isEmpty()) {
                        return@withLock Result.failure(IllegalStateException("Downloaded file is empty"))
                    }
                    if (!verifyHash(bytes, plugin.fileHash)) {
                        return@withLock Result.failure(
                            IllegalStateException("Checksum mismatch, the download was corrupted")
                        )
                    }
                    val manifest = readManifest(bytes)
                        ?: return@withLock Result.failure(
                            IllegalStateException("Not a valid CloudStream plugin (no classes.dex/manifest)")
                        )

                    val fileName = safeFileName(plugin.internalName) + ".cs3"
                    val target = File(pluginsDir, fileName)
                    val temp = File(pluginsDir, "$fileName.tmp")
                    temp.writeBytes(bytes)
                    if (target.exists()) target.delete()
                    if (!temp.renameTo(target)) {
                        temp.copyTo(target, overwrite = true)
                        temp.delete()
                    }

                    val installed = CloudStreamInstalled(
                        internalName = plugin.internalName,
                        name = plugin.name.ifBlank { manifest.name ?: plugin.internalName },
                        version = plugin.version,
                        language = plugin.language,
                        description = plugin.description,
                        authors = plugin.authors,
                        tvTypes = plugin.tvTypes,
                        iconUrl = plugin.iconUrl,
                        pluginUrl = plugin.url,
                        repositoryUrl = plugin.repositoryUrl,
                        repository = plugin.repository,
                        fileHash = plugin.fileHash,
                        fileName = fileName,
                        apiVersion = plugin.apiVersion,
                        isNsfw = plugin.isNsfw,
                        installedAt = System.currentTimeMillis(),
                    )
                    metadataFile(fileName).writeText(json.encodeToString(installed))
                    _installed.value = readInstalledFromDisk()
                    CloudStreamRepositoryManager.updateInstalledCount(_installed.value.size)
                    CloudStreamRuntime.reload()
                    Result.success(installed)
                } catch (e: Exception) {
                    Logger.log("CloudStream: install failed for ${plugin.internalName}: ${e.message}")
                    Result.failure(e)
                } finally {
                    _installing.value = _installing.value - plugin.internalName
                }
            }
        }

    suspend fun uninstall(internalName: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val installed = installedFor(internalName) ?: return@withLock false
            val pluginFile = File(pluginsDir, installed.fileName)
            val meta = metadataFile(installed.fileName)
            val removed = pluginFile.delete() or !pluginFile.exists()
            meta.delete()
            _installed.value = readInstalledFromDisk()
            CloudStreamRepositoryManager.updateInstalledCount(_installed.value.size)
            CloudStreamRuntime.reload()
            removed
        }
    }

    /** Re-scans the plugin folder and reloads the runtime. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _installed.value = readInstalledFromDisk()
            CloudStreamRepositoryManager.updateInstalledCount(_installed.value.size)
            CloudStreamRuntime.reload()
        }
    }

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36"
}

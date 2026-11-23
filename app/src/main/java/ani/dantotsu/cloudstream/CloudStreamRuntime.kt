package ani.dantotsu.cloudstream

import android.content.Context
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.BasePlugin
import ani.dantotsu.util.Logger
import dalvik.system.PathClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File

data class CloudStreamProvider(
    val api: MainAPI,
    val extension: CloudStreamInstalled,
) {
    val name: String get() = api.name
    val language: String get() = api.lang
    val mainUrl: String get() = api.mainUrl
}

/**
 * Loads CloudStream `.cs3` plugins and registers their [MainAPI] providers.
 *
 * The plugin archive contains a `classes.dex` compiled against the real CloudStream
 * library (`com.lagradost.cloudstream3`). That library is a dependency of Kiroku, so a
 * [PathClassLoader] whose parent is the app class loader can resolve the superclass and
 * all API models. `manifest.json` inside the archive names the [BasePlugin] subclass.
 */
object CloudStreamRuntime {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    private val _providers = MutableStateFlow<List<CloudStreamProvider>>(emptyList())
    val providers = _providers.asStateFlow()

    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors = _errors.asStateFlow()

    @Volatile
    private var loaded = false

    @Volatile
    var onProvidersChanged: (() -> Unit)? = null

    val isLoaded: Boolean get() = loaded

    suspend fun ensureLoaded() {
        if (loaded) return
        reload()
    }

    suspend fun reload() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val context: Context = try {
                CloudStreamExtensionManager.context
            } catch (e: Exception) {
                Logger.log("CloudStream: runtime has no context: ${e.message}")
                return@withLock
            }
            CloudStreamApp.context = context
            val installed = CloudStreamExtensionManager.installed.value
            APIHolder.allProviders.clear()
            val providers = mutableListOf<CloudStreamProvider>()
            val errors = mutableMapOf<String, String>()

            for (plugin in installed) {
                try {
                    loadPlugin(context, plugin, providers)
                } catch (t: Throwable) {
                    val message = t.message?.takeIf { it.isNotBlank() }
                        ?: t::class.java.simpleName
                    errors[plugin.internalName] = message
                    Logger.log("CloudStream: failed to load ${plugin.displayName}: $message")
                    Logger.log(t)
                }
            }

            _providers.value = providers
            _errors.value = errors
            loaded = true
            Logger.log(
                "CloudStream: ${providers.size} providers loaded from ${installed.size} plugins"
            )
            onProvidersChanged?.invoke()
        }
    }

    private fun loadPlugin(
        context: Context,
        installed: CloudStreamInstalled,
        out: MutableList<CloudStreamProvider>,
    ) {
        val file = File(CloudStreamExtensionManager.pluginsDir, installed.fileName)
        if (!file.isFile) {
            throw IllegalStateException("Plugin file is missing")
        }
        val loader = PathClassLoader(file.absolutePath, context.classLoader)
        val manifest = loader.getResourceAsStream("manifest.json")?.use { stream ->
            runCatching {
                json.decodeFromString<CloudStreamPluginManifest>(
                    stream.readBytes().toString(Charsets.UTF_8)
                )
            }.getOrNull()
        } ?: throw IllegalStateException("Plugin has no valid manifest.json")

        val className = manifest.pluginClassName
            ?: throw IllegalStateException("Plugin manifest has no pluginClassName")

        val before: Set<MainAPI> = APIHolder.allProviders.toSet()
        val pluginClass = loader.loadClass(className)
        val instance = pluginClass.getDeclaredConstructor().newInstance() as? BasePlugin
            ?: throw IllegalStateException("$className does not extend BasePlugin")
        instance.filename = file.absolutePath
        instance.load()
        val added = (APIHolder.allProviders.toSet() - before).toMutableSet()

        if (added.isEmpty()) {
            // Some plugins register through APIHolder directly without the BasePlugin helper.
            val registered = APIHolder.allProviders.filter {
                it.sourcePlugin == file.absolutePath
            }
            added.addAll(registered)
        }

        added.forEach { api ->
            if (api.name.isBlank() || api.name == "NONE") return@forEach
            out.add(CloudStreamProvider(api, installed))
        }
    }
}

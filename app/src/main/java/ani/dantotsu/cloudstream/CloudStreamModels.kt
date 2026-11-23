package ani.dantotsu.cloudstream

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One provider/build entry of a CloudStream repository index (plugins.json). */
@Serializable
data class CloudStreamPlugin(
    val name: String = "",
    val internalName: String = "",
    val version: Long = 0L,
    val language: String = "en",
    val description: String = "",
    val authors: List<String> = emptyList(),
    val tvTypes: List<String> = emptyList(),
    val iconUrl: String? = null,
    val url: String = "",
    val repositoryUrl: String = "",
    /** The index URL this entry was read from. */
    val repository: String = "",
    val apiVersion: Int = 1,
    val fileHash: String? = null,
    val fileSize: Long = 0L,
    val status: Int = 1,
    /** CloudStream marks adult providers with the "NSFW" / "18+" tag. */
    val isNsfw: Boolean = false,
) {
    val displayName: String get() = name.ifBlank { internalName }
}

/** Metadata of a downloaded .cs3 plugin, stored next to the plugin file. */
@Serializable
data class CloudStreamInstalled(
    val internalName: String,
    val name: String,
    val version: Long = 0L,
    val language: String = "en",
    val description: String = "",
    val authors: List<String> = emptyList(),
    val tvTypes: List<String> = emptyList(),
    val iconUrl: String? = null,
    val pluginUrl: String = "",
    val repositoryUrl: String = "",
    val repository: String = "",
    val fileHash: String? = null,
    val fileName: String,
    val apiVersion: Int = 1,
    val isNsfw: Boolean = false,
    val installedAt: Long = System.currentTimeMillis(),
) {
    val displayName: String get() = name.ifBlank { internalName }
}

/** The manifest.json inside a .cs3 archive. */
@Serializable
data class CloudStreamPluginManifest(
    val pluginClassName: String? = null,
    val name: String? = null,
    val version: Long? = null,
    val requiresResources: Boolean = false,
)

internal object CloudStreamIndexParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowTrailingComma = true
    }

    /**
     * CloudStream indexes are usually a JSON array, but some repositories wrap the list in
     * an object ({"plugins": [...]}, {"data": [...]}, {"pluginList": [...]}).
     * Malformed entries are skipped instead of failing the whole repository.
     */
    fun parse(text: String, repository: String): List<CloudStreamPlugin> {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> {
                sequenceOf("plugins", "pluginList", "data", "extensions")
                    .mapNotNull { root[it] as? JsonArray }
                    .firstOrNull()
                    ?: return emptyList()
            }
            else -> return emptyList()
        }
        return array.mapNotNull { element ->
            (element as? JsonObject)?.let { parseEntry(it, repository) }
        }
    }

    private fun parseEntry(obj: JsonObject, repository: String): CloudStreamPlugin? {
        val internalName = obj.string("internalName") ?: obj.string("name") ?: return null
        val url = obj.string("url") ?: obj.string("apkUrl") ?: return null
        val tvTypes = obj.stringList("tvTypes")
        val status = obj.long("status")?.toInt() ?: 1
        val description = obj.string("description").orEmpty()
        return CloudStreamPlugin(
            name = obj.string("name") ?: internalName,
            internalName = internalName,
            version = obj.long("version") ?: 0L,
            language = obj.string("language") ?: "en",
            description = description,
            authors = obj.stringList("authors"),
            tvTypes = tvTypes,
            iconUrl = obj.string("iconUrl") ?: obj.string("icon"),
            url = url,
            repositoryUrl = obj.string("repositoryUrl").orEmpty(),
            repository = repository,
            apiVersion = (obj.long("apiVersion") ?: 1L).toInt(),
            fileHash = obj.string("fileHash"),
            fileSize = obj.long("fileSize") ?: 0L,
            status = status,
            isNsfw = obj.stringList("tvTypes").any { it.equals("NSFW", true) || it.contains("18+") } ||
                description.contains("nsfw", true) || description.contains("18+"),
        )
    }

    private fun JsonObject.raw(name: String): JsonElement? = this[name]

    private fun JsonObject.string(name: String): String? =
        (raw(name) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.long(name: String): Long? {
        val primitive = raw(name) as? JsonPrimitive ?: return null
        return primitive.content.toLongOrNull()
            ?: primitive.content.substringBefore('.').toLongOrNull()
    }

    private fun JsonObject.stringList(name: String): List<String> {
        val element = raw(name) ?: return emptyList()
        return when (element) {
            is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.content }
            is JsonPrimitive -> element.content
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            else -> emptyList()
        }
    }
}

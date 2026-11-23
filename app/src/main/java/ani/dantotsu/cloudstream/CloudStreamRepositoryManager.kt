package ani.dantotsu.cloudstream

import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Manages CloudStream extension repositories independently from the Aniyomi/Manga/Novel
 * repository lists, so the two providers can never leak into each other.
 */
object CloudStreamRepositoryManager {
    private val networkHelper: NetworkHelper by lazy { Injekt.get() }

    private val _available = MutableStateFlow<List<CloudStreamPlugin>>(emptyList())
    val available = _available.asStateFlow()

    private val _installedCount = MutableStateFlow(0)
    val installedCount = _installedCount.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError = _lastError.asStateFlow()

    val repositories: Set<String>
        get() = PrefManager.getVal<Set<String>>(PrefName.CloudStreamExtensionRepos)

    /**
     * Accepts a full index URL, a GitHub blob URL, a repository page or the
     * `owner/repo[/branch]` shorthand CloudStream itself accepts.
     */
    fun normalizeRepositoryUrl(input: String): String {
        var value = input.trim()
        if (value.isEmpty()) return value
        value = value.replace("https://github.com/", "https://raw.githubusercontent.com/")
            .replace("/blob/", "/")
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            val parts = value.split("/").filter { it.isNotBlank() }
            val owner = parts.getOrNull(0) ?: return value
            val repo = parts.getOrNull(1) ?: return value
            val branch = parts.getOrNull(2) ?: "builds"
            value = "https://raw.githubusercontent.com/$owner/$repo/$branch/plugins.json"
        }
        value = value.trimEnd('/')
        if (!value.endsWith(".json")) {
            value = when {
                value.endsWith("/builds") -> "$value/plugins.json"
                value.contains("raw.githubusercontent.com") -> {
                    val parts = value.removePrefix("https://raw.githubusercontent.com/")
                        .split("/")
                    if (parts.size >= 3 && parts[2] != "builds") {
                        // owner/repo/<branch or path>: the index lives in the builds branch
                        value
                    } else {
                        "$value/plugins.json"
                    }
                }
                else -> "$value/plugins.json"
            }
        }
        return value
    }

    private fun candidateUrls(indexUrl: String): List<String> {
        val result = mutableListOf(indexUrl)
        if (indexUrl.endsWith("/plugins.json")) {
            result.add(indexUrl.removeSuffix("plugins.json") + "plugins.min.json")
        }
        val directory = indexUrl.substringBeforeLast('/', "")
        if (directory.isNotEmpty()) {
            result.add("$directory/repo.json")
        }
        return result.distinct()
    }

    private suspend fun fetchIndex(url: String): List<CloudStreamPlugin> {
        for (candidate in candidateUrls(url)) {
            try {
                val request = Request.Builder()
                    .url(candidate)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .build()
                networkHelper.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body.string()
                    if (body.isBlank()) return@use
                    val parsed = CloudStreamIndexParser.parse(body, url)
                    if (parsed.isNotEmpty()) return parsed
                }
            } catch (e: Exception) {
                Logger.log("CloudStream: failed to read $candidate: ${e.message}")
            }
        }
        return emptyList()
    }

    suspend fun refresh(): List<CloudStreamPlugin> = withContext(Dispatchers.IO) {
        _refreshing.value = true
        try {
            val repos = repositories.toList()
            val all = mutableListOf<CloudStreamPlugin>()
            var lastFailure: String? = null
            for (repo in repos) {
                val index = normalizeRepositoryUrl(repo)
                val entries = try {
                    fetchIndex(index)
                } catch (e: Exception) {
                    lastFailure = e.message ?: "Unknown error"
                    emptyList()
                }
                if (entries.isEmpty()) {
                    lastFailure = lastFailure ?: "No extensions found at $index"
                }
                all.addAll(entries)
            }
            // One extension may exist in several repositories; keep the newest build.
            val deduplicated = all
                .groupBy { it.internalName.lowercase() }
                .mapNotNull { (_, plugins) ->
                    plugins.filter { it.url.isNotBlank() }.maxByOrNull { it.version }
                }
                .sortedBy { it.displayName.lowercase() }
            _available.value = deduplicated
            _lastError.value = if (deduplicated.isEmpty() && repos.isNotEmpty()) lastFailure else null
            deduplicated
        } finally {
            _refreshing.value = false
        }
    }

    suspend fun addRepository(input: String) {
        val url = normalizeRepositoryUrl(input)
        if (url.isBlank()) return
        val current = repositories
        if (current.contains(url)) {
            refresh()
            return
        }
        PrefManager.setVal(PrefName.CloudStreamExtensionRepos, current + url)
        refresh()
    }

    suspend fun removeRepository(url: String) {
        PrefManager.setVal(PrefName.CloudStreamExtensionRepos, repositories - url)
        refresh()
    }

    fun updateInstalledCount(count: Int) {
        _installedCount.value = count
    }

    fun clearError() {
        _lastError.value = null
    }

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36"
}

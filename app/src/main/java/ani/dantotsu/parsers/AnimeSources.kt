package ani.dantotsu.parsers

import ani.dantotsu.Lazier
import ani.dantotsu.cloudstream.CloudStreamRuntime
import ani.dantotsu.cloudstream.CloudStreamSourceAdapter
import ani.dantotsu.lazyList
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

object AnimeSources : WatchSources() {
    override var list: List<Lazier<BaseParser>> = emptyList()
    var pinnedAnimeSources: List<String> = emptyList()

    @Volatile
    var isInitialized = false

    @Volatile
    private var installedExtensions: List<AnimeExtension.Installed> = emptyList()

    suspend fun init(fromExtensions: StateFlow<List<AnimeExtension.Installed>>) {
        pinnedAnimeSources =
            PrefManager.getNullableVal<List<String>>(PrefName.AnimeSourcesOrder, null)
                ?: emptyList()

        // Initialize with the first value from StateFlow
        val initialExtensions = fromExtensions.first()
        installedExtensions = initialExtensions
        list = buildSourceList(initialExtensions)
        isInitialized = true

        // Update as StateFlow emits new values
        fromExtensions.collect { extensions ->
            installedExtensions = extensions
            list = buildSourceList(extensions)
        }
    }

    /**
     * Rebuilds the source list, for example after a CloudStream plugin was installed,
     * updated or removed. Aniyomi extensions are preserved as they are.
     */
    fun refresh() {
        if (!isInitialized) return
        list = buildSourceList(installedExtensions)
    }

    private fun buildSourceList(extensions: List<AnimeExtension.Installed>): List<Lazier<BaseParser>> {
        return createParsersFromExtensions(extensions) +
            createParsersFromCloudStream() +
            Lazier({ OfflineAnimeParser() }, "Downloaded")
    }

    fun performReorderAnimeSources() {
        // Remove the downloaded source from the list to avoid duplicates
        list = list.filter { it.name != "Downloaded" }
        list = sortPinnedAnimeSources(list, pinnedAnimeSources) + Lazier(
            { OfflineAnimeParser() },
            "Downloaded"
        )
    }

    private fun createParsersFromExtensions(extensions: List<AnimeExtension.Installed>): List<Lazier<BaseParser>> {
        return extensions.map { extension ->
            val name = extension.name
            Lazier({ DynamicAnimeParser(extension) }, name)
        }
    }

    private fun createParsersFromCloudStream(): List<Lazier<BaseParser>> {
        return CloudStreamRuntime.providers.value.map { provider ->
            Lazier({ CloudStreamSourceAdapter(provider) }, provider.name)
        }
    }

    private fun sortPinnedAnimeSources(
        sources: List<Lazier<BaseParser>>,
        pinnedAnimeSources: List<String>
    ): List<Lazier<BaseParser>> {
        val pinnedSourcesMap = sources.filter { pinnedAnimeSources.contains(it.name) }
            .associateBy { it.name }
        val orderedPinnedSources = pinnedAnimeSources.mapNotNull { name ->
            pinnedSourcesMap[name]
        }
        val unpinnedSources = sources.filterNot { pinnedAnimeSources.contains(it.name) }
        return orderedPinnedSources + unpinnedSources
    }
}


object HAnimeSources : WatchSources() {
    private val aList: List<Lazier<BaseParser>> = lazyList(
    )

    override val list = listOf(aList, AnimeSources.list).flatten()
}

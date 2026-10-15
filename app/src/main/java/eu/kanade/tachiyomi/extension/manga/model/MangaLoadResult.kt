package eu.kanade.tachiyomi.extension.manga.model

sealed class MangaLoadResult {
    class Success(val extension: MangaExtension.Installed) : MangaLoadResult()
    class Untrusted(val extension: MangaExtension.Untrusted) : MangaLoadResult()
    class Incompatible(val pkgName: String, val label: String, val reason: String) : MangaLoadResult()
    data object Error : MangaLoadResult()
}

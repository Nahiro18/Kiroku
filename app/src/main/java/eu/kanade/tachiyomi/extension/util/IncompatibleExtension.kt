package eu.kanade.tachiyomi.extension.util

/**
 * An installed extension package that cannot run on this app version,
 * e.g. built for a newer extension API (lib version out of range or
 * generated sources the app cannot instantiate).
 */
data class IncompatibleExtension(
    val pkgName: String,
    val label: String,
    val reason: String,
)

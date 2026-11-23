package eu.kanade.tachiyomi.animesource.model

/**
 * Define the update strategy for a single SAnime.
 * The strategy used will only take effect on the library update.
 *
 * Mirrors extensions-lib 14+ so extensions can set it on the SAnime they create.
 */
enum class AnimeUpdateStrategy {
    ALWAYS_UPDATE,
    ONLY_FETCH_ONCE,
}

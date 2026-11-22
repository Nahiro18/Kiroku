package eu.kanade.tachiyomi.animesource.model

/**
 * Define what type of content the anime should fetch.
 *
 * Mirrors extensions-lib 16 so extensions can set it on the SAnime they create.
 */
enum class FetchType {
    /** SAnime will only call `getSeasonList`. */
    Seasons,

    /** SAnime will only call `getEpisodeList`. */
    Episodes,
}

package eu.kanade.tachiyomi.animesource.model

/**
 * A representation of a group of videos coming from the same source, e.g. a site which has
 * multiple servers, each with a list of videos.
 *
 * @param hosterUrl The url of the hoster.
 * @param hosterName The display name of the hoster.
 * @param videoList Videos that can be built without extra network requests, if any.
 * @param internalData Internal data used by the source.
 * @param lazy Whether the hoster should only load once the user selects it.
 */
class Hoster(
    val hosterUrl: String = "",
    val hosterName: String = "",
    val videoList: List<Video>? = null,
    val internalData: String = "",
    val lazy: Boolean = false,
) {
    companion object {
        const val NO_HOSTER_LIST = "no_hoster_list"

        fun List<Video>.toHosterList(): List<Hoster> {
            return listOf(
                Hoster(
                    hosterUrl = "",
                    hosterName = NO_HOSTER_LIST,
                    videoList = this,
                ),
            )
        }
    }
}

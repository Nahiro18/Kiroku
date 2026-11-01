package ani.dantotsu.connections.subtitles

import ani.dantotsu.Mapper
import ani.dantotsu.okHttpClient
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Request
import ani.dantotsu.media.Media

object SubtitleProviders {
    private const val WYZIE_BASE = "https://sub.wyzie.io/search"
    private const val DEFAULT_WYZIE_KEY = "wyzie-g3qzu8pm817cto70wrsm4atoze6oj8bv"
    private const val SUBSOURCE_BASE = "https://api.subsource.net/api/v1"
    private const val DEFAULT_SUBSOURCE_KEY = "sk_559a7155a0ffa592d732eb89dc0307e967934cfb92ecb6e097a2f2d850d00490"

    suspend fun searchAll(media: Media, season: Int, episode: Int): List<UnifiedSubtitle> {
        val enabled = PrefManager.getVal<Boolean>(PrefName.OnlineSubtitlesEnabled)
        if (!enabled) return emptyList()

        val providers = PrefManager.getVal<Set<String>>(PrefName.OnlineSubtitleProviders)
        val languages = PrefManager.getVal<Set<String>>(PrefName.OnlineSubtitleLanguages)

        val imdbId = media.idIMDB ?: return emptyList()
        val allSubs = mutableListOf<UnifiedSubtitle>()

        return withContext(Dispatchers.IO) {
            if (providers.contains("Wyzie")) {
                try {
                    val apiKey = try {
                        PrefManager.getNullableCustomVal("pref_wyzie_api_key", "", String::class.java).orEmpty().ifBlank { DEFAULT_WYZIE_KEY }
                    } catch (_: Exception) { DEFAULT_WYZIE_KEY }

                    val langParam = languages.joinToString(",")
                    val url = "$WYZIE_BASE?id=$imdbId&season=$season&episode=$episode&language=$langParam"
                    val keyParam = if (apiKey.isNotBlank()) "&key=$apiKey" else ""

                    val req = Request.Builder().url(url + keyParam).build()
                    okHttpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val text = resp.body.string()
                            if (!text.trim().startsWith("<") && text.trim().startsWith("[")) {
                                val subs = Mapper.json.decodeFromString<List<WyzieSub>>(text)
                                subs.forEach { s ->
                                    val lang = s.language.lowercase().take(2)
                                    if (languages.contains(lang)) {
                                        allSubs.add(UnifiedSubtitle(
                                            id = s.id,
                                            url = s.url,
                                            lang = s.displayLabel,
                                            provider = "Wyzie",
                                            format = s.format
                                        ))
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Logger.log("Wyzie error: ${e.message}")
                }
            }

            if (providers.contains("SubSource")) {
                try {
                    val apiKey = try {
                        PrefManager.getNullableCustomVal("pref_subsource_api_key", "", String::class.java).orEmpty().ifBlank { DEFAULT_SUBSOURCE_KEY }
                    } catch (_: Exception) { DEFAULT_SUBSOURCE_KEY }

                    val searchUrl = if (season > 0) {
                        "$SUBSOURCE_BASE/movies/search?searchType=imdb&imdb=$imdbId&season=$season"
                    } else {
                        "$SUBSOURCE_BASE/movies/search?searchType=imdb&imdb=$imdbId"
                    }

                    val reqBuilder = Request.Builder().url(searchUrl)
                    if (apiKey.isNotBlank()) reqBuilder.addHeader("X-API-Key", apiKey)

                    val json = okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                        if (!resp.isSuccessful) return@use ""
                        resp.body.string()
                    }
                    if (json.isNotBlank()) {
                        val searchResult = Mapper.json.decodeFromString<SubSourceSearchResponseV1>(json)
                        val movie = searchResult.data.firstOrNull() ?: return@withContext allSubs
                        val movieId = movie.movieId

                        val userLangs = languages.joinToString(",") { it.lowercase() }
                        val subUrl = if (userLangs.isNotBlank()) {
                            "$SUBSOURCE_BASE/subtitles?movieId=$movieId&language=$userLangs&limit=100&sort=rating"
                        } else {
                            "$SUBSOURCE_BASE/subtitles?movieId=$movieId&limit=100&sort=rating"
                        }

                        val subReqBuilder = Request.Builder().url(subUrl)
                        if (apiKey.isNotBlank()) subReqBuilder.addHeader("X-API-Key", apiKey)

                        val subJson = okHttpClient.newCall(subReqBuilder.build()).execute().use { subResp ->
                            if (!subResp.isSuccessful) return@use ""
                            subResp.body.string()
                        }
                        if (subJson.isNotBlank()) {
                            val subResult = Mapper.json.decodeFromString<SubSourceListResponseV1>(subJson)
                            val epStr = episode.toString()
                            val epPad = epStr.padStart(2, '0')

                            subResult.data.filter { item ->
                                val relText = (item.releaseInfo.joinToString(" ") + " " + (item.commentary ?: "")).trim()
                                if (relText.isBlank()) return@filter true
                                relText.contains("E$epPad", ignoreCase = true) ||
                                relText.contains("E$epStr", ignoreCase = true) ||
                                relText.contains(" $epStr ", ignoreCase = true) ||
                                relText.contains(" - $epStr", ignoreCase = true) ||
                                relText.contains("Episode $epStr", ignoreCase = true) ||
                                relText.contains("Ep $epStr", ignoreCase = true) ||
                                relText.contains("Ep. $epStr", ignoreCase = true) ||
                                relText.contains(" $epPad ", ignoreCase = true)
                            }.forEach { item ->
                                allSubs.add(UnifiedSubtitle(
                                    id = item.subtitleId.toString(),
                                    url = "$SUBSOURCE_BASE/subtitles/${item.subtitleId}/download",
                                    lang = item.language.replaceFirstChar { it.uppercase() },
                                    provider = "SubSource",
                                    format = "SRT"
                                ))
                            }
                        }
                    }
                } catch (e: Exception) {
                    Logger.log("SubSource error: ${e.message}")
                }
            }

            if (providers.contains("OpenSubtitles")) {
                allSubs.addAll(OpenSubtitlesRestApi.search(imdbId, episode, season)
                    .filter { it.language.lowercase().take(2) in languages }
                    .map { UnifiedSubtitle(
                        id = it.fileId.toString(),
                        url = it.fileName,
                        lang = it.language,
                        provider = "OpenSubtitles",
                        format = "SRT"
                    ) })
            }

            allSubs
        }
    }
}

@Serializable
data class UnifiedSubtitle(
    val id: String,
    val url: String,
    val lang: String,
    val provider: String,
    val format: String
)

@Serializable
data class WyzieSub(
    @SerialName("id") val id: String,
    @SerialName("url") val url: String,
    @SerialName("display") val display: String?,
    @SerialName("language") val language: String,
    @SerialName("format") val format: String
) {
    val displayLabel: String get() = display ?: language
}

@Serializable
data class SubSourceSearchResponseV1(val data: List<SubSourceMovie> = emptyList())
@Serializable
data class SubSourceMovie(val movieId: Int, val title: String? = null)
@Serializable
data class SubSourceListResponseV1(val data: List<SubSourceItem> = emptyList())
@Serializable
data class SubSourceItem(
    val subtitleId: Int,
    val releaseInfo: List<String> = emptyList(),
    val language: String,
    val hearingImpaired: Boolean? = null,
    val commentary: String? = null,
)
package ani.dantotsu.media.anime

import android.content.Context
import android.net.Uri
import androidx.media3.common.MimeTypes
import androidx.media3.common.MediaItem
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.text.SubtitleParser
import androidx.media3.exoplayer.text.SubtitleParserFactory
import androidx.media3.exoplayer.text.SubtitleDecoderFactory
import ani.dantotsu.connections.subtitles.OpenSubtitlesRestApi
import ani.dantotsu.connections.subtitles.StremioSubtitles
import ani.dantotsu.connections.subtitles.SubSourceSubtitles
import ani.dantotsu.connections.subtitles.WyzieSubtitles
import ani.dantotsu.media.Media
import ani.dantotsu.media.Episode
import ani.dantotsu.parsers.Subtitle
import ani.dantotsu.parsers.FileUrl
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import okhttp3.OkHttpClient
import okhttp3.Request

object KirokuOnlineSubtitleManager {

    private val httpClient = OkHttpClient()

    fun fetchAndApplyOnlineSubtitles(
        context: Context,
        media: Media,
        episode: Episode,
        player: ExoPlayer,
        factory: DefaultMediaSourceFactory
    ) {
        val enabled = PrefManager.getVal<Boolean>(PrefName.OnlineSubtitlesEnabled)
        if (!enabled) return

        val imdbId = media.idIMDB ?: return
        val season = episode.season ?: return
        val episodeNum = episode.number.toIntOrNull() ?: return

        CoroutineScope(Dispatchers.IO).launch {
            val subs = StremioSubtitles.getSubtitles(media, season, episodeNum)
            if (subs.isEmpty()) return@launch

            val selectedProviders = PrefManager.getVal<Set<String>>(PrefName.OnlineSubtitleProviders)
            val selectedLanguages = PrefManager.getVal<Set<String>>(PrefName.OnlineSubtitleLanguages)

            val filtered = subs.filter { s ->
                (selectedProviders.contains("Wyzie") || selectedProviders.contains("Stremio")) &&
                selectedLanguages.contains(s.lang.lowercase().take(2))
            }
            if (filtered.isEmpty()) {
                filtered.addAll(subs.take(5))
            }

            for (sub in filtered.take(3)) {
                val file = fetchAndSaveSubtitle(context, sub)
                file?.let { f ->
                    applySubtitleToPlayer(player, f, sub.lang, factory)
                }
            }
        }
    }

    private fun fetchAndSaveSubtitle(context: Context, sub: StremioSubtitles.StremioSub): File? {
        return try {
            val url = sub.url
            val extension = if (url.contains(".vtt")) ".vtt" else if (url.contains(".ass")) ".ass" else ".srt"
            val file = File(context.cacheDir, "online_subtitle_${sub.id.hashCode()}.$extension")

            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val bytes = response.body?.bytes() ?: return@use null
                file.writeBytes(bytes)
            }
            file
        } catch (e: Exception) {
            Logger.log("Online subtitle fetch failed: ${e.message}")
            null
        }
    }

    private fun applySubtitleToPlayer(
        player: ExoPlayer,
        file: File,
        language: String,
        factory: DefaultMediaSourceFactory
    ) {
        val uri = Uri.fromFile(file)
        val subtitleItem = MediaItem.Builder()
            .setUri(uri)
            .setMimeType(MimeTypes.TEXT_VTT)
            .build()

        // This is simplified - in reality we'd need to merge with current media source
        Logger.log("Applying online subtitle: $file for language: $language")
    }

    fun searchOpenSubtitles(imdbId: String, episode: Int, season: Int? = null) =
        OpenSubtitlesRestApi.search(imdbId, episode, season)

    fun getOpenSubtitlesDownloadUrl(fileId: Int) =
        OpenSubtitlesRestApi.getDownloadUrl(fileId)

    fun searchWyzie(imdbId: String, season: Int, episode: Int) =
        WyzieSubtitles.getWyzieSubtitles(imdbId, season, episode)

    fun searchSubSource(imdbId: String, episode: Int, season: Int? = null) =
        SubSourceSubtitles.getSubtitles(imdbId, episode, season)

    fun downloadSubSourceContent(subtitleId: String) =
        SubSourceSubtitles.downloadSubtitleContent(subtitleId)
}
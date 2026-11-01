package ani.dantotsu.media.kiroku

import ani.dantotsu.media.manga.MangaChapter
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

object KirokuChapterCache {

    private const val KIND = "chapters"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    @Serializable
    private data class Row(
        val number: String,
        val link: String,
        val title: String? = null,
        val description: String? = null,
        val scanlator: String? = null,
        val date: Long? = null,
        val progress: String? = "",
        val sourceName: String = "",
        val sourceUrl: String = "",
        val sourceNumber: Float = -1f,
        val sourceScanlator: String? = null,
        val sourceDate: Long = 0L,
        val sourceMemo: String? = null,
    )

    @Serializable
    private data class Payload(
        val savedAt: Long = 0L,
        val rows: List<Row> = emptyList(),
    )

    fun load(sourceKey: String, link: String): MutableMap<String, MangaChapter>? {
        if (sourceKey.isBlank() || link.isBlank()) return null
        val text = KirokuCacheFile.read(KIND, sourceKey, link) ?: return null
        return runCatching {
            val payload = json.decodeFromString<Payload>(text)
            if (payload.rows.isEmpty()) return null
            val chapters = mutableMapOf<String, MangaChapter>()
            payload.rows.forEach { row ->
                val chapter = row.toChapter() ?: return@forEach
                chapters["${chapter.number}-${chapter.scanlator}"] = chapter
            }
            chapters.takeIf { it.isNotEmpty() }
        }.onFailure {
            Logger.log("Kiroku cache chapters unreadable: ${it.message}")
        }.getOrNull()
    }

    fun save(sourceKey: String, link: String, chapters: Map<String, MangaChapter>) {
        if (sourceKey.isBlank() || link.isBlank() || chapters.isEmpty()) return
        val payload = Payload(
            savedAt = System.currentTimeMillis(),
            rows = chapters.values.map { it.toRow() },
        )
        runCatching {
            KirokuCacheFile.write(KIND, sourceKey, link, json.encodeToString(payload))
        }.onFailure {
            Logger.log("Kiroku cache chapters not saved: ${it.message}")
        }
    }

    fun invalidate(sourceKey: String, link: String) {
        KirokuCacheFile.remove(KIND, sourceKey, link)
    }

    fun usedBytes(): Long = KirokuCacheFile.usedBytes(KIND)

    fun clear() = KirokuCacheFile.clear(KIND)

    private fun MangaChapter.toRow(): Row {
        val source = sChapter
        return Row(
            number = number,
            link = link,
            title = title,
            description = description,
            scanlator = scanlator,
            date = date,
            progress = progress,
            sourceName = source.name,
            sourceUrl = source.url,
            sourceNumber = source.chapter_number,
            sourceScanlator = source.scanlator,
            sourceDate = source.date_upload,
            sourceMemo = source.memo.toString(),
        )
    }

    private fun Row.toChapter(): MangaChapter? {
        if (link.isBlank()) return null
        val source = SChapter.create().apply {
            name = sourceName
            url = sourceUrl.ifBlank { link }
            chapter_number = sourceNumber
            scanlator = sourceScanlator
            date_upload = sourceDate
        }
        sourceMemo?.let { text ->
            runCatching { json.parseToJsonElement(text).jsonObject }
                .onSuccess { source.memo = it }
        }
        return MangaChapter(
            number = number,
            link = link,
            title = title,
            description = description,
            sChapter = source,
            scanlator = scanlator,
            date = date,
            progress = progress,
        )
    }
}

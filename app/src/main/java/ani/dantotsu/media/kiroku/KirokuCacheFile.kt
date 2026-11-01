package ani.dantotsu.media.kiroku

import ani.dantotsu.currContext
import ani.dantotsu.util.Logger
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal object KirokuCacheFile {

    private const val ROOT_DIR = "kiroku_cache"
    private const val MAX_FILES = 250
    private const val KEEP_FILES = 180

    private fun folder(kind: String): File? {
        val context = currContext() ?: return null
        val dir = File(File(context.filesDir, ROOT_DIR), kind)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        return dir.takeIf { it.isDirectory }
    }

    private fun fileFor(kind: String, sourceKey: String, link: String): File? {
        val dir = folder(kind) ?: return null
        val raw = "v1|$sourceKey|$link"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        val name = buildString {
            for (byte in digest.take(20)) append("%02x".format(byte))
        }
        return File(dir, name)
    }

    fun read(kind: String, sourceKey: String, link: String): String? {
        val file = fileFor(kind, sourceKey, link) ?: return null
        if (!file.isFile) return null
        return runCatching {
            GZIPInputStream(file.inputStream()).bufferedReader().use { it.readText() }
        }.onFailure {
            Logger.log("Kiroku cache read failed: ${it.message}")
        }.getOrNull()
    }

    fun write(kind: String, sourceKey: String, link: String, text: String) {
        val file = fileFor(kind, sourceKey, link) ?: return
        runCatching {
            GZIPOutputStream(file.outputStream()).bufferedWriter().use { it.write(text) }
            prune(kind)
        }.onFailure {
            Logger.log("Kiroku cache write failed: ${it.message}")
        }
    }

    fun remove(kind: String, sourceKey: String, link: String) {
        fileFor(kind, sourceKey, link)?.delete()
    }

    fun usedBytes(kind: String? = null): Long {
        val context = currContext() ?: return 0L
        val root = File(context.filesDir, ROOT_DIR)
        val dir = if (kind == null) root else File(root, kind)
        return runCatching {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
    }

    fun clear(kind: String? = null) {
        val context = currContext() ?: return
        val root = File(context.filesDir, ROOT_DIR)
        val dir = if (kind == null) root else File(root, kind)
        runCatching { dir.deleteRecursively() }
    }

    private fun prune(kind: String) {
        val dir = folder(kind) ?: return
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - KEEP_FILES)
            .forEach { it.delete() }
    }
}

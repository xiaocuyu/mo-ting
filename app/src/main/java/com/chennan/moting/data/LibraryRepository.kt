package com.chennan.moting.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class BookRecord(
    val id: String,
    val filePath: String,
    var title: String,
    var author: String,
    var coverPath: String?,
    val sizeBytes: Long,
    val addedAt: Long,
    var lastOpenedAt: Long = 0L,
    var chapterIndex: Int = 0,
    var charOffset: Int = 0,
    var chapterCount: Int = 0,
    var languageTag: String? = null,
    var progress: Float = 0f,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("filePath", filePath)
        put("title", title)
        put("author", author)
        put("coverPath", coverPath ?: "")
        put("sizeBytes", sizeBytes)
        put("addedAt", addedAt)
        put("lastOpenedAt", lastOpenedAt)
        put("chapterIndex", chapterIndex)
        put("charOffset", charOffset)
        put("chapterCount", chapterCount)
        put("languageTag", languageTag ?: "")
        put("progress", progress.toDouble())
    }

    companion object {
        fun fromJson(json: JSONObject): BookRecord = BookRecord(
            id = json.optString("id"),
            filePath = json.optString("filePath"),
            title = json.optString("title", "未命名"),
            author = json.optString("author", ""),
            coverPath = json.optString("coverPath").takeIf { it.isNotBlank() },
            sizeBytes = json.optLong("sizeBytes", 0L),
            addedAt = json.optLong("addedAt", 0L),
            lastOpenedAt = json.optLong("lastOpenedAt", 0L),
            chapterIndex = json.optInt("chapterIndex", 0),
            charOffset = json.optInt("charOffset", 0),
            chapterCount = json.optInt("chapterCount", 0),
            languageTag = json.optString("languageTag").takeIf { it.isNotBlank() },
            progress = json.optDouble("progress", 0.0).toFloat(),
        )
    }
}

/** 书架持久化：一个 JSON 文件即可，书籍数量不会很大。 */
object LibraryRepository {

    private const val FILE_NAME = "library.json"

    private lateinit var appContext: Context
    private val books = mutableListOf<BookRecord>()
    private val lock = Any()

    fun init(context: Context) {
        synchronized(lock) {
            if (::appContext.isInitialized) return
            appContext = context.applicationContext
            load()
        }
    }

    fun books(): List<BookRecord> = synchronized(lock) { books.toList() }

    fun find(id: String): BookRecord? = synchronized(lock) { books.firstOrNull { it.id == id } }

    fun mostRecent(): BookRecord? = synchronized(lock) {
        books.filter { it.lastOpenedAt > 0L }.maxByOrNull { it.lastOpenedAt }
    }

    fun upsert(record: BookRecord) {
        synchronized(lock) {
            val index = books.indexOfFirst { it.id == record.id }
            if (index >= 0) books[index] = record else books.add(0, record)
            save()
        }
    }

    fun remove(id: String) {
        val record = synchronized(lock) {
            val existing = books.firstOrNull { it.id == id } ?: return
            books.remove(existing)
            save()
            existing
        }
        runCatching { fileFor(record).delete() }
        runCatching { record.coverPath?.let { File(appContext.filesDir, it).delete() } }
    }

    fun fileFor(record: BookRecord): File = File(appContext.filesDir, record.filePath)

    fun coverFile(record: BookRecord): File? =
        record.coverPath?.let { File(appContext.filesDir, it).takeIf { file -> file.exists() } }

    fun import(context: Context, uri: Uri): BookRecord = BookImporter.import(context, uri)

    private fun load() {
        books.clear()
        val file = File(appContext.filesDir, FILE_NAME)
        if (!file.exists()) return
        runCatching {
            val array = JSONArray(file.readText(Charsets.UTF_8))
            for (i in 0 until array.length()) {
                val record = BookRecord.fromJson(array.getJSONObject(i))
                if (record.id.isNotBlank() && fileFor(record).exists()) {
                    books.add(record)
                }
            }
        }
    }

    private fun save() {
        val array = JSONArray()
        books.forEach { array.put(it.toJson()) }
        val tmp = File(appContext.filesDir, "$FILE_NAME.tmp")
        runCatching {
            tmp.writeText(array.toString(), Charsets.UTF_8)
            val target = File(appContext.filesDir, FILE_NAME)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
    }
}

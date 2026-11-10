package com.chennan.moting.mobi

import java.nio.charset.Charset

/** 书籍中的一个章节，记录正文在解压后字节流中的区间。 */
data class Chapter(
    val title: String,
    val level: Int,
    internal val startByte: Int,
    internal val endByte: Int,
)

/** 仅含元数据的书籍信息（导入书架时使用，无需解压正文）。 */
data class MobiInfo(
    val title: String,
    val author: String,
    val publisher: String?,
    val languageTag: String?,
    val charset: Charset,
    val chapterCount: Int,
    val tocTitles: List<String>,
    val cover: ByteArray?,
    val compression: Int,
)

/**
 * 解析完成的书籍：保存解压后的原始标记文本，按需解码章节，避免一次性构建全部字符串。
 */
class MobiBook internal constructor(
    val title: String,
    val author: String,
    val publisher: String?,
    val languageTag: String?,
    val charset: Charset,
    val cover: ByteArray?,
    val chapters: List<Chapter>,
    private val raw: ByteArray,
) {
    val rawSize: Int get() = raw.size

    val totalChars: Int
        get() = if (charset == Charsets.UTF_8) raw.size / 3 else raw.size

    /** 解码指定章节的 HTML。 */
    fun chapterHtml(index: Int): String {
        if (index !in chapters.indices) return ""
        val chapter = chapters[index]
        var start = chapter.startByte.coerceIn(0, raw.size)
        var end = chapter.endByte.coerceIn(start, raw.size)
        if (charset == Charsets.UTF_8) {
            while (start > 0 && (raw[start].toInt() and 0xC0) == 0x80) start--
            while (end < raw.size && (raw[end].toInt() and 0xC0) == 0x80) end++
        }
        return String(raw, start, end - start, charset)
    }

    /** 估算章节字符数，用于进度百分比。 */
    fun chapterWeight(index: Int): Int {
        if (index !in chapters.indices) return 0
        val chapter = chapters[index]
        return (chapter.endByte - chapter.startByte).coerceAtLeast(0)
    }
}

class MobiParser(private val data: ByteArray) {

    private val pdb: PalmDb by lazy { PalmDb.parse(data) }
    private val header: MobiHeader by lazy { MobiHeader.parse(pdb) }

    val compression: Int get() = header.compression

    /** 只解析元数据与目录，不解压正文，用于快速导入。 */
    fun info(): MobiInfo {
        val toc = readToc()
        return MobiInfo(
            title = header.title,
            author = header.author,
            publisher = header.publisher,
            languageTag = header.languageTag,
            charset = header.charset,
            chapterCount = toc.size,
            tocTitles = toc.map { it.title },
            cover = extractCover(),
            compression = header.compression,
        )
    }

    /** 完整解析：解压正文并建立章节区间。 */
    fun open(): MobiBook {
        val raw = extractText()
        val tray = readToc()
        val chapters = buildChapters(raw, tray)
        if (chapters.isEmpty()) {
            throw MobiFormatException("未能从这本书中解析出正文")
        }
        return MobiBook(
            title = header.title,
            author = header.author,
            publisher = header.publisher,
            languageTag = header.languageTag,
            charset = header.charset,
            cover = extractCover(),
            chapters = chapters,
            raw = raw,
        )
    }

    // ---------------------------------------------------------------- 正文

    private fun extractText(): ByteArray {
        val recordCount = pdb.recordCount
        if (recordCount <= 1) throw MobiFormatException("这本书没有正文记录")

        var textRecords = header.textRecordCount
        if (textRecords <= 0) throw MobiFormatException("MOBI 文本记录数非法")
        textRecords = minOf(textRecords, recordCount - 1)

        val flags = header.extraDataFlags
        val out = java.io.ByteArrayOutputStream(maxOf(header.textLength, 1024))

        val huff = if (header.compression == 17480) {
            if (header.huffmanRecordCount <= 0 ||
                header.huffmanRecordOffset <= 0 ||
                header.huffmanRecordOffset + header.huffmanRecordCount > recordCount
            ) {
                throw MobiFormatException("HUFF 压缩表索引非法")
            }
            val table = pdb.record(header.huffmanRecordOffset)
            val cdic = (1 until header.huffmanRecordCount).map {
                pdb.record(header.huffmanRecordOffset + it)
            }
            HuffCdic(table, cdic)
        } else {
            null
        }

        for (i in 1..textRecords) {
            val record = TrailingData.trim(pdb.record(i), flags)
            val plain = when (header.compression) {
                1 -> record
                2 -> PalmDoc.decompress(record)
                17480 -> huff!!.decompress(record)
                else -> throw UnsupportedMobiException("不支持的压缩方式")
            }
            out.write(plain)
        }
        var raw = out.toByteArray()
        if (header.textLength in 1..raw.size) {
            raw = raw.copyOf(header.textLength)
        }
        return raw
    }

    // ---------------------------------------------------------------- 目录

    private class TocItem(val title: String, val pos: Int, val level: Int)

    private fun readToc(): List<TocItem> {
        val index = header.ncxIndex
        if (index == 0xFFFFFFFF.toInt() || index !in 0 until pdb.recordCount) return emptyList()
        return try {
            val result = MobiIndex(pdb).read(index)
            val items = ArrayList<TocItem>(result.entries.size)
            for (entry in result.entries) {
                val pos = entry.tags[1]?.firstOrNull() ?: continue
                if (pos <= 0) continue
                val labelOffset = entry.tags[3]?.firstOrNull()
                val labelBytes = labelOffset?.let { result.ctoc[it] }
                val label = labelBytes?.let { decode(it) }?.trim().orEmpty()
                val level = entry.tags[4]?.firstOrNull() ?: 0
                items.add(TocItem(label, pos, level))
            }
            items
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun buildChapters(raw: ByteArray, toc: List<TocItem>): List<Chapter> {
        val valid = toc
            .filter { it.pos in 1 until raw.size }
            .sortedBy { it.pos }
            .distinctBy { it.pos }
        val pagebreaks = findPagebreaks(raw)

        // 有些书的 NCX 只收录“卷”，而正文里每一章都有分页符；
        // 这种情况下分页符切出来的章节更细、也更符合阅读习惯。
        val tocDense = valid.size >= 2 &&
            (pagebreaks.size < 2 || valid.size >= 64 || pagebreaks.size <= valid.size * 3 / 2)

        if (tocDense) {
            val fromToc = chaptersFromToc(raw, valid)
            if (fromToc.isNotEmpty()) return fromToc
        }
        if (pagebreaks.size >= 2) {
            val fromBreaks = chaptersFromPagebreaks(raw, pagebreaks)
            if (fromBreaks.size >= 2) return fromBreaks
        }
        if (valid.size >= 2) {
            val fromToc = chaptersFromToc(raw, valid)
            if (fromToc.isNotEmpty()) return fromToc
        }
        return chaptersByChunk(raw)
    }

    private fun chaptersFromToc(raw: ByteArray, valid: List<TocItem>): List<Chapter> {
        val chapters = ArrayList<Chapter>(valid.size + 1)
        // 目录之前的少量内容通常是封面/导航，内容较多时补成前言
        val firstPos = valid.firstOrNull()?.pos ?: return emptyList()
        if (firstPos > 3000) {
            val lead = raw.copyOfRange(0, firstPos)
            if (plainTextLength(lead) > 200) {
                chapters.add(Chapter("前言", 0, 0, firstPos))
            }
        }
        for ((i, item) in valid.withIndex()) {
            val end = if (i + 1 < valid.size) valid[i + 1].pos else raw.size
            if (end <= item.pos) continue
            val title = item.title.ifBlank { "第 ${chapters.size + 1} 节" }
            chapters.add(Chapter(title, item.level, item.pos, end))
        }
        return chapters
    }

    private fun findPagebreaks(raw: ByteArray): IntArray {
        val marker = "<mbp:pagebreak".toByteArray(Charsets.US_ASCII)
        val positions = ArrayList<Int>()
        var from = 0
        while (true) {
            val found = indexOf(raw, marker, from)
            if (found < 0) break
            positions.add(found)
            from = found + marker.size
        }
        return positions.toIntArray()
    }

    private fun chaptersFromPagebreaks(raw: ByteArray, breaks: IntArray): List<Chapter> {
        val chapters = ArrayList<Chapter>()
        val positions = ArrayList<Int>(breaks.size + 1)
        if (breaks.isNotEmpty() && breaks[0] > 0) positions.add(0)
        breaks.forEach { positions.add(it) }
        for (i in positions.indices) {
            val start = positions[i]
            val end = if (i + 1 < positions.size) positions[i + 1] else raw.size
            if (end - start < 32) continue
            val title = inferTitle(raw, start, end)
            chapters.add(Chapter(title, 0, start, end))
        }
        return chapters
    }

    /** 没有任何可用结构时，按 2 万字节左右切块，尽量落在换行处。 */
    private fun chaptersByChunk(raw: ByteArray): List<Chapter> {
        val chapters = ArrayList<Chapter>()
        val chunk = 20000
        var start = 0
        var index = 1
        while (start < raw.size) {
            var end = minOf(start + chunk, raw.size)
            if (end < raw.size) {
                val nl = indexOf(raw, byteArrayOf(0x0A), end)
                if (nl in 0 until minOf(end + 2000, raw.size)) end = nl + 1
            }
            if (end > start) {
                chapters.add(Chapter("第 $index 节", 0, start, end))
                index++
            }
            start = end
        }
        return chapters
    }

    private fun inferTitle(raw: ByteArray, start: Int, end: Int): String {
        val sample = String(raw, start, minOf(600, end - start), header.charset)
        val bold = Regex("<b>(.{1,40}?)</b>").find(sample)?.groupValues?.get(1)
        val heading = Regex("<h[1-6][^>]*>(.{1,40}?)</h[1-6]>").find(sample)?.groupValues?.get(1)
        val rawTitle = (heading ?: bold)?.replace(Regex("<[^>]+>"), "")?.trim()
        if (!rawTitle.isNullOrBlank()) return rawTitle
        val text = sample.replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return text.take(24).ifBlank { "正文" }
    }

    private fun plainTextLength(bytes: ByteArray): Int {
        var count = 0
        var inTag = false
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            if (b == '<'.code) inTag = true
            else if (b == '>'.code) inTag = false
            else if (!inTag) {
                if (b != 0x20 && b != 0x0A && b != 0x0D && b != 0x09) {
                    count++
                    if (count > 400) return count
                }
            }
            i++
        }
        return count
    }

    // ---------------------------------------------------------------- 封面

    private fun extractCover(): ByteArray? {
        if (header.firstImageIndex < 0 ||
            header.firstImageIndex >= pdb.recordCount
        ) {
            return null
        }
        val candidates = ArrayList<Int>()
        header.coverOffset?.let { candidates.add(header.firstImageIndex + it) }
        header.thumbOffset?.let { candidates.add(header.firstImageIndex + it) }

        // 部分书籍 EXTH 缺失，退而求其次扫描前几张图片
        for (i in 0 until minOf(6, pdb.recordCount - header.firstImageIndex)) {
            candidates.add(header.firstImageIndex + i)
        }
        for (index in candidates.distinct()) {
            if (index !in 0 until pdb.recordCount) continue
            val record = try {
                pdb.record(index)
            } catch (e: Exception) {
                continue
            }
            if (isImage(record)) return record
        }
        return null
    }

    private fun isImage(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        return (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xFF) == 0xD8 ||
            (bytes[0].toInt() and 0xFF) == 0x89 && bytes.startsWithAscii("PNG") ||
            bytes.startsWithAscii("GIF8")
    }

    // ---------------------------------------------------------------- 工具

    private fun decode(bytes: ByteArray): String = String(bytes, header.charset)

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty()) return from
        val last = haystack.size - needle.size
        var i = maxOf(0, from)
        while (i <= last) {
            var match = true
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
            i++
        }
        return -1
    }
}

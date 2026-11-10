package com.chennan.moting.mobi

import java.nio.charset.Charset

open class MobiFormatException(message: String) : Exception(message)

class DrmProtectedException : MobiFormatException("这本书带有 DRM 版权保护，无法解析。")

class UnsupportedMobiException(message: String) : MobiFormatException(message)

/**
 * 解析 MOBI 主记录（record 0）中的 PalmDOC 头、MOBI 头与 EXTH 元数据。
 */
internal class MobiHeader private constructor(
    val compression: Int,
    val textLength: Int,
    val textRecordCount: Int,
    val recordSize: Int,
    val encryption: Int,
    val mobiHeaderLength: Int,
    val mobiType: Int,
    val encodingCode: Int,
    val fileVersion: Int,
    val minVersion: Int,
    val firstNonBookIndex: Int,
    val firstImageIndex: Int,
    val huffmanRecordOffset: Int,
    val huffmanRecordCount: Int,
    val exthFlags: Int,
    val extraDataFlags: Int,
    val ncxIndex: Int,
    val charset: Charset,
    val fullName: String,
    val exth: Map<Int, List<ByteArray>>,
    val pdbName: String,
) {
    val isMobipocket: Boolean get() = mobiHeaderLength > 0

    private fun exthString(type: Int): String? {
        val raw = exth[type]?.firstOrNull() ?: return null
        return String(raw, charset).trim('\u0000', '\uFEFF', ' ', '\n', '\r', '\t')
    }

    private fun exthInt(type: Int): Int? {
        val raw = exth[type]?.firstOrNull() ?: return null
        if (raw.size < 4) return null
        return raw.u32(0).toInt()
    }

    val title: String = (
        exthString(503)?.takeIf { it.isNotBlank() }
            ?: fullName.takeIf { it.isNotBlank() }
            ?: exthString(100)?.takeIf { it.isNotBlank() }
            ?: pdbName.takeIf { it.isNotBlank() }
            ?: "未命名"
        ).replace(Regex("[\\u0000-\\u001f]"), "").trim()

    val author: String = exth[100]
        ?.mapNotNull { String(it, charset).trim('\u0000', ' ', '\n', '\r').takeIf { s -> s.isNotBlank() } }
        ?.distinct()
        ?.joinToString("、")
        .orEmpty()

    val publisher: String? = exthString(101)?.takeIf { it.isNotBlank() }

    val description: String? = exthString(103)?.takeIf { it.isNotBlank() }

    val languageTag: String? = exthString(524)?.takeIf { it.isNotBlank() }

    val coverOffset: Int? = exthInt(201)

    val thumbOffset: Int? = exthInt(202)

    val rights: String? = exthString(109)?.takeIf { it.isNotBlank() }

    companion object {
        fun parse(pdb: PalmDb): MobiHeader {
            val rec0 = pdb.record(0)
            if (rec0.size < 16) throw MobiFormatException("MOBI 头损坏")

            val compression = rec0.u16(0)
            val textLength = rec0.u32(4).toInt()
            val textRecordCount = rec0.u16(8)
            val recordSize = rec0.u16(10)
            val encryption = rec0.u16(12)

            val hasMobi = rec0.startsWithAscii("MOBI", 16)
            var mobiHeaderLength = 0
            var mobiType = 0
            var encodingCode = 1252
            var fileVersion = 0
            var minVersion = 0
            var firstNonBookIndex = 0xFFFFFFFF.toInt()
            var firstImageIndex = 0xFFFFFFFF.toInt()
            var huffOffset = 0
            var huffCount = 0
            var exthFlags = 0
            var extraDataFlags = 0
            var ncxIndex = 0xFFFFFFFF.toInt()
            var fullName = ""

            if (hasMobi) {
                mobiHeaderLength = rec0.u32(0x14).toInt()
                mobiType = rec0.u32(0x18).toInt()
                encodingCode = rec0.u32(0x1C).toInt()
                fileVersion = rec0.u32(0x24).toInt()
                minVersion = rec0.u32(0x68).toInt()
                firstNonBookIndex = rec0.u32(0x50).toInt()
                firstImageIndex = rec0.u32(0x6C).toInt()
                huffOffset = rec0.u32(0x70).toInt()
                huffCount = rec0.u32(0x74).toInt()
                exthFlags = rec0.u32(0x80).toInt()

                if (mobiHeaderLength >= 0xE4 && minVersion >= 5 && rec0.size >= 0xF4) {
                    extraDataFlags = rec0.u16(0xF2)
                }
                // 部分老书的 headerLength 字段偏小（例如 0xE8），但 NCX 索引仍写在 0xF4；
                // 用“目标记录确实是 INDX”来校验，避免把 EXTH 区域误当索引。
                if (rec0.size >= 0xF8) {
                    val candidate = rec0.u32(0xF4).toInt()
                    if (candidate in 0 until pdb.recordCount) {
                        val indexRecord = runCatching { pdb.record(candidate) }.getOrNull()
                        if (indexRecord != null && indexRecord.startsWithAscii("INDX")) {
                            ncxIndex = candidate
                        }
                    }
                }

                val nameOffset = rec0.u32(0x54).toInt()
                val nameLength = rec0.u32(0x58).toInt()
                if (nameOffset > 0 && nameLength > 0 &&
                    nameOffset + nameLength <= rec0.size
                ) {
                    fullName = String(rec0, nameOffset, nameLength, Charsets.ISO_8859_1)
                }
            }

            val charset = when (encodingCode) {
                65001 -> Charsets.UTF_8
                1252, 0 -> Charset.forName("windows-1252")
                else -> runCatching { Charset.forName("cp$encodingCode") }.getOrDefault(Charsets.UTF_8)
            }

            if (fullName.isNotBlank() && charset != Charsets.ISO_8859_1) {
                // 书名实际编码与正文字符集一致
                val nameOffset = rec0.u32(0x54).toInt()
                val nameLength = rec0.u32(0x58).toInt()
                if (nameOffset + nameLength <= rec0.size) {
                    fullName = String(rec0, nameOffset, nameLength, charset)
                }
            }

            // EXTH
            val exth = LinkedHashMap<Int, MutableList<ByteArray>>()
            if (exthFlags and 0x40 != 0 && mobiHeaderLength > 0) {
                val base = 16 + mobiHeaderLength
                if (base + 12 <= rec0.size && rec0.startsWithAscii("EXTH", base)) {
                    val totalLen = rec0.u32(base + 4).toInt()
                    val count = rec0.u32(base + 8).toInt()
                    var p = base + 12
                    val limit = minOf(rec0.size, base + totalLen)
                    var guard = 0
                    while (guard < count && p + 8 <= limit) {
                        val type = rec0.u32(p).toInt()
                        val len = rec0.u32(p + 4).toInt()
                        if (len < 8 || p + len > limit) break
                        exth.getOrPut(type) { mutableListOf() }
                            .add(rec0.copyOfRange(p + 8, p + len))
                        p += len
                        guard++
                    }
                }
            }

            if (encryption != 0) throw DrmProtectedException()
            if (mobiType == 248) {
                throw UnsupportedMobiException("这是 KF8 / AZW3 格式，当前版本仅支持经典 MOBI。")
            }
            if (compression != 1 && compression != 2 && compression != 17480) {
                throw UnsupportedMobiException("不支持的 MOBI 压缩方式：$compression")
            }

            return MobiHeader(
                compression = compression,
                textLength = textLength,
                textRecordCount = textRecordCount,
                recordSize = if (recordSize in 1..65535) recordSize else 4096,
                encryption = encryption,
                mobiHeaderLength = mobiHeaderLength,
                mobiType = mobiType,
                encodingCode = encodingCode,
                fileVersion = fileVersion,
                minVersion = minVersion,
                firstNonBookIndex = firstNonBookIndex,
                firstImageIndex = firstImageIndex,
                huffmanRecordOffset = huffOffset,
                huffmanRecordCount = huffCount,
                exthFlags = exthFlags,
                extraDataFlags = extraDataFlags,
                ncxIndex = ncxIndex,
                charset = charset,
                fullName = fullName,
                exth = exth,
                pdbName = pdb.name,
            )
        }
    }
}

package com.chennan.moting.mobi

internal class IndexEntry(
    val name: ByteArray,
    val tags: Map<Int, IntArray>,
)

internal class IndexResult(
    val entries: List<IndexEntry>,
    val ctoc: Map<Int, ByteArray>,
)

/**
 * 解析 MOBI 的 INDX 索引结构（目录 / NCX 使用）。
 * 移植自 KindleUnpack 的 MobiIndex。
 */
internal class MobiIndex(private val pdb: PalmDb) {

    private class TagSpec(val tag: Int, val valuesPerEntry: Int, val mask: Int, val endFlag: Int)

    private class PendingTag(val tag: Int, val valueCount: Int?, val valueBytes: Int?, val perEntry: Int)

    fun read(headerIndex: Int): IndexResult {
        if (headerIndex !in 0 until pdb.recordCount) {
            throw MobiFormatException("目录索引越界")
        }
        val headerData = pdb.record(headerIndex)
        val header = parseHeader(headerData)
        val entryCount = header[5]
        val tagSectionStart = header[0]

        val ctoc = HashMap<Int, ByteArray>()
        var recordOffset = 0
        val ctocStart = headerIndex + entryCount + 1
        repeat(header[12]) { j ->
            val idx = ctocStart + j
            if (idx < pdb.recordCount) {
                readCtoc(pdb.record(idx)).forEach { (k, v) -> ctoc[k + recordOffset] = v }
            }
            recordOffset += 0x10000
        }

        val (controlBytes, tagTable) = readTagSection(tagSectionStart, headerData)
        val entries = ArrayList<IndexEntry>()
        for (i in headerIndex + 1..headerIndex + entryCount) {
            if (i >= pdb.recordCount) break
            val data = pdb.record(i)
            val h = parseHeader(data)
            val idxtPos = h[4]
            val count = h[5]
            if (idxtPos < 0 || idxtPos + 4 + count * 2 > data.size) continue
            val positions = IntArray(count + 1)
            for (j in 0 until count) {
                positions[j] = data.u16(idxtPos + 4 + j * 2)
            }
            positions[count] = idxtPos
            for (j in 0 until count) {
                val start = positions[j]
                val end = positions[j + 1]
                if (start < 0 || start >= data.size) continue
                val textLength = data.u8(start)
                if (start + 1 + textLength > data.size) continue
                val name = data.copyOfRange(start + 1, start + 1 + textLength)
                val tags = getTagMap(controlBytes, tagTable, data, start + 1 + textLength, end)
                entries.add(IndexEntry(name, tags))
            }
        }
        return IndexResult(entries, ctoc)
    }

    private fun parseHeader(data: ByteArray): IntArray {
        if (data.size < 4 + 13 * 4 || !data.startsWithAscii("INDX")) {
            throw MobiFormatException("INDX 索引损坏")
        }
        return IntArray(13) { i -> data.i32(4 + i * 4) }
    }

    private fun readCtoc(data: ByteArray): Map<Int, ByteArray> {
        val out = HashMap<Int, ByteArray>()
        var offset = 0
        while (offset < data.size && data[offset].toInt() != 0) {
            val start = offset
            val (consumed, length) = variableWidthValue(data, offset) ?: break
            offset += consumed
            if (length < 0 || offset + length > data.size) break
            out[start] = data.copyOfRange(offset, offset + length)
            offset += length
        }
        return out
    }

    private fun readTagSection(start: Int, data: ByteArray): Pair<Int, List<TagSpec>> {
        val tags = ArrayList<TagSpec>()
        if (start < 0 || start + 12 > data.size || !data.startsWithAscii("TAGX", start)) {
            return 0 to tags
        }
        val firstEntryOffset = data.u32(start + 4).toInt()
        val controlByteCount = data.u32(start + 8).toInt()
        var p = 12
        while (p < firstEntryOffset && start + p + 4 <= data.size) {
            val pos = start + p
            tags.add(
                TagSpec(
                    tag = data.u8(pos),
                    valuesPerEntry = data.u8(pos + 1),
                    mask = data.u8(pos + 2),
                    endFlag = data.u8(pos + 3),
                )
            )
            p += 4
        }
        return controlByteCount to tags
    }

    private fun getTagMap(
        controlBytes: Int,
        tagTable: List<TagSpec>,
        entryData: ByteArray,
        startPos: Int,
        endPos: Int,
    ): Map<Int, IntArray> {
        val pending = ArrayList<PendingTag>()
        var controlIndex = 0
        var dataStart = startPos + controlBytes

        for (spec in tagTable) {
            if (spec.endFlag == 0x01) {
                controlIndex++
                continue
            }
            val controlPos = startPos + controlIndex
            if (controlPos !in entryData.indices) continue
            val control = entryData.u8(controlPos)
            var value = control and spec.mask
            if (value == 0) continue

            if (value == spec.mask) {
                if (Integer.bitCount(spec.mask) > 1) {
                    val vwi = variableWidthValue(entryData, dataStart) ?: break
                    dataStart += vwi.first
                    pending.add(PendingTag(spec.tag, null, vwi.second, spec.valuesPerEntry))
                } else {
                    pending.add(PendingTag(spec.tag, 1, null, spec.valuesPerEntry))
                }
            } else {
                var mask = spec.mask
                while (mask and 1 == 0) {
                    mask = mask ushr 1
                    value = value ushr 1
                }
                pending.add(PendingTag(spec.tag, value, null, spec.valuesPerEntry))
            }
        }

        val result = HashMap<Int, IntArray>()
        for (p in pending) {
            val values = ArrayList<Int>()
            val count = p.valueCount
            if (count != null) {
                repeat(count) {
                    repeat(p.perEntry) {
                        val vwi = variableWidthValue(entryData, dataStart) ?: return result
                        dataStart += vwi.first
                        values.add(vwi.second)
                    }
                }
            } else {
                var consumedTotal = 0
                val target = p.valueBytes ?: 0
                while (consumedTotal < target) {
                    val vwi = variableWidthValue(entryData, dataStart) ?: return result
                    dataStart += vwi.first
                    consumedTotal += vwi.first
                    values.add(vwi.second)
                }
            }
            result[p.tag] = values.toIntArray()
        }
        if (endPos in 0..entryData.size && dataStart != endPos) {
            // 尾部 0 填充是常见的，只有非零残留才认为异常；此处静默容忍。
        }
        return result
    }

    /** 返回 (消费字节数, 值)。 */
    private fun variableWidthValue(data: ByteArray, offset: Int): Pair<Int, Int>? {
        var value = 0
        var consumed = 0
        while (true) {
            val p = offset + consumed
            if (p !in data.indices) return null
            val b = data.u8(p)
            consumed++
            value = (value shl 7) or (b and 0x7F)
            if (b and 0x80 != 0) return consumed to value
            if (consumed > 8) return null
        }
    }
}

package com.chennan.moting.mobi

import java.io.ByteArrayOutputStream

/** 处理 MOBI 文本记录末尾的附加数据（extra data flags）。 */
internal object TrailingData {

    private fun trailingEntrySize(data: ByteArray): Int {
        var num = 0
        val start = maxOf(0, data.size - 4)
        for (i in start until data.size) {
            val b = data[i].toInt() and 0xFF
            if (b and 0x80 != 0) num = 0
            num = (num shl 7) or (b and 0x7F)
        }
        return num
    }

    /**
     * 去掉记录尾部的附加条目，以及跨记录的多字节重叠字节。
     * 算法与 KindleUnpack 保持一致：先按 bit1..bit15 依次裁剪，再由 bit0 处理 UTF-8 重叠。
     */
    fun trim(record: ByteArray, extraDataFlags: Int): ByteArray {
        if (extraDataFlags == 0) return record
        var data = record
        val trailers = Integer.bitCount(extraDataFlags ushr 1)
        repeat(trailers) {
            if (data.size < 4) return data
            val size = trailingEntrySize(data)
            if (size <= 0 || size > data.size) return data
            data = data.copyOfRange(0, data.size - size)
        }
        if (extraDataFlags and 1 != 0 && data.isNotEmpty()) {
            val overlap = (data[data.size - 1].toInt() and 3) + 1
            if (overlap <= data.size) {
                data = data.copyOfRange(0, data.size - overlap)
            }
        }
        return data
    }
}

/** PalmDOC (LZ77) 解压。 */
internal object PalmDoc {
    fun decompress(input: ByteArray): ByteArray {
        var out = ByteArray(maxOf(4096, 64))
        var len = 0
        var p = 0
        val n = input.size

        fun ensure(extra: Int) {
            if (len + extra > out.size) {
                var cap = out.size
                while (cap < len + extra) cap = cap shl 1
                out = out.copyOf(cap)
            }
        }

        while (p < n) {
            val c = input[p].toInt() and 0xFF
            p++
            when {
                c in 1..8 -> {
                    val count = minOf(c, n - p)
                    if (count <= 0) break
                    ensure(count)
                    System.arraycopy(input, p, out, len, count)
                    len += count
                    p += count
                }

                c < 0x80 -> {
                    ensure(1)
                    out[len++] = c.toByte()
                }

                c >= 0xC0 -> {
                    ensure(2)
                    out[len++] = 0x20
                    out[len++] = (c xor 0x80).toByte()
                }

                else -> {
                    if (p >= n) break
                    val c2 = (c shl 8) or (input[p].toInt() and 0xFF)
                    p++
                    val distance = (c2 ushr 3) and 0x07FF
                    val count = (c2 and 7) + 3
                    if (distance == 0) continue
                    ensure(count)
                    for (i in 0 until count) {
                        out[len] = out[len - distance]
                        len++
                    }
                }
            }
        }
        return out.copyOf(len)
    }
}

/**
 * HUFF/CDIC 解压。移植自 KindleUnpack 的 HuffcdicReader。
 */
internal class HuffCdic(huffRecord: ByteArray, cdicRecords: List<ByteArray>) {

    private class Code(val length: Int, val terminal: Boolean, val maxCode: Int)

    private class Phrase(val raw: ByteArray, val compressed: Boolean) {
        var value: ByteArray? = null
    }

    private val codes = arrayOfNulls<Code>(256)
    private val minCode = IntArray(33)
    private val maxCode = IntArray(33)
    private val phrases = ArrayList<Phrase>()

    init {
        if (huffRecord.size < 16 || !huffRecord.startsWithAscii("HUFF")) {
            throw MobiFormatException("HUFF 压缩表损坏")
        }
        val off1 = huffRecord.u32(8).toInt()
        val off2 = huffRecord.u32(12).toInt()
        if (off1 < 0 || off1 + 1024 > huffRecord.size || off2 < 0 || off2 + 256 > huffRecord.size) {
            throw MobiFormatException("HUFF 压缩表越界")
        }
        for (i in 0 until 256) {
            val v = huffRecord.i32(off1 + i * 4)
            val codeLen = v and 0x1F
            val terminal = v and 0x80 != 0
            val maxCodeRaw = v ushr 8
            if (codeLen == 0) throw MobiFormatException("HUFF 编码长度非法")
            codes[i] = Code(codeLen, terminal, (((maxCodeRaw + 1) shl (32 - codeLen)) - 1))
        }
        val dict2 = IntArray(64) { huffRecord.i32(off2 + it * 4) }
        for (i in 0 until 32) {
            val codeLen = i + 1
            minCode[codeLen] = dict2[i * 2] shl (32 - codeLen)
            maxCode[codeLen] = ((dict2[i * 2 + 1] + 1) shl (32 - codeLen)) - 1
        }

        for (cdic in cdicRecords) {
            if (cdic.size < 16 || !cdic.startsWithAscii("CDIC")) {
                throw MobiFormatException("CDIC 压缩表损坏")
            }
            val phraseCount = cdic.u32(8).toInt()
            val bits = cdic.u32(12).toInt()
            if (bits !in 0..16) throw MobiFormatException("CDIC 位宽非法")
            val n = minOf(1 shl bits, phraseCount - phrases.size)
            if (n <= 0) continue
            if (16 + n * 2 > cdic.size) throw MobiFormatException("CDIC 偏移表越界")
            for (i in 0 until n) {
                val off = cdic.u16(16 + i * 2)
                val start = 18 + off
                if (start > cdic.size) throw MobiFormatException("CDIC 词条越界")
                val blen = cdic.u16(16 + off)
                val sliceLen = blen and 0x7FFF
                if (start + sliceLen > cdic.size) throw MobiFormatException("CDIC 词条被截断")
                phrases.add(
                    Phrase(
                        raw = cdic.copyOfRange(start, start + sliceLen),
                        compressed = blen and 0x8000 == 0,
                    )
                )
            }
        }
        if (phrases.isEmpty()) throw MobiFormatException("HUFF/CDIC 字典为空")
    }

    fun decompress(input: ByteArray): ByteArray = decompressInternal(input, 0)

    private fun decompressInternal(input: ByteArray, depth: Int): ByteArray {
        if (depth > 64) throw MobiFormatException("HUFF 词典嵌套过深")
        val out = ByteArrayOutputStream(maxOf(64, input.size * 2))
        var bitsLeft = input.size * 8
        var pos = 0
        var n = 32
        var x = readLongBe(input, 0)

        while (true) {
            if (n <= 0) {
                pos += 4
                x = readLongBe(input, pos)
                n += 32
            }
            val code = ((x ushr n) and 0xFFFFFFFFL).toInt()
            var entry = codes[(code ushr 24) and 0xFF] ?: break
            var codeLen = entry.length
            var maxCodeValue = entry.maxCode
            if (!entry.terminal) {
                while (codeLen <= 32 && unsignedLess(code, minCode[codeLen])) {
                    codeLen++
                }
                if (codeLen > 32) break
                maxCodeValue = maxCode[codeLen]
            }
            n -= codeLen
            bitsLeft -= codeLen
            if (bitsLeft < 0) break

            val index = (maxCodeValue - code) ushr (32 - codeLen)
            if (index !in phrases.indices) break
            out.write(resolve(index, depth))
        }
        return out.toByteArray()
    }

    private fun resolve(index: Int, depth: Int): ByteArray {
        val phrase = phrases[index]
        phrase.value?.let { return it }
        val value = if (phrase.compressed) {
            decompressInternal(phrase.raw, depth + 1)
        } else {
            phrase.raw
        }
        phrase.value = value
        return value
    }

    private fun readLongBe(data: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until 8) {
            val p = offset + i
            v = (v shl 8) or (if (p in data.indices) (data[p].toLong() and 0xFF) else 0L)
        }
        return v
    }
}

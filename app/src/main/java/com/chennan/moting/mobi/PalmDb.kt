package com.chennan.moting.mobi

/** Palm 数据库容器：解析记录表，按记录号切分数据。 */
internal class PalmDb private constructor(
    private val data: ByteArray,
    val recordCount: Int,
    private val offsets: IntArray,
) {
    val ident: String get() = String(data, 60, 8, Charsets.ISO_8859_1)

    val name: String get() = data.cString(0, 32)

    fun record(index: Int): ByteArray {
        require(index in 0 until recordCount) { "记录号越界: $index / $recordCount" }
        return data.copyOfRange(offsets[index], offsets[index + 1])
    }

    fun recordSize(index: Int): Int {
        require(index in 0 until recordCount)
        return offsets[index + 1] - offsets[index]
    }

    companion object {
        private const val HEADER_SIZE = 78
        private const val ENTRY_SIZE = 8

        fun parse(data: ByteArray): PalmDb {
            if (data.size < HEADER_SIZE + 2) {
                throw MobiFormatException("文件太小，不是有效的 MOBI 电子书")
            }
            val count = data.u16(76)
            if (count <= 0 || count > 65535) {
                throw MobiFormatException("MOBI 记录表损坏")
            }
            val tableEnd = HEADER_SIZE + count * ENTRY_SIZE
            if (data.size < tableEnd) {
                throw MobiFormatException("MOBI 记录表被截断")
            }
            val offsets = IntArray(count + 1)
            for (i in 0 until count) {
                val raw = data.u32(HEADER_SIZE + i * ENTRY_SIZE)
                if (raw < 0 || raw > data.size) {
                    throw MobiFormatException("MOBI 记录偏移非法")
                }
                offsets[i] = raw.toInt()
            }
            offsets[count] = data.size
            for (i in 0 until count) {
                if (offsets[i] > offsets[i + 1]) {
                    throw MobiFormatException("MOBI 记录偏移未递增")
                }
            }
            return PalmDb(data, count, offsets)
        }
    }
}

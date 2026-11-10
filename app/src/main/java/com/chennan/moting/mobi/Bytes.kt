package com.chennan.moting.mobi

/** 大端字节读取工具（MOBI/PalmDB 全部使用大端序）。 */
internal fun ByteArray.u8(off: Int): Int = this[off].toInt() and 0xFF

internal fun ByteArray.u16(off: Int): Int = (u8(off) shl 8) or u8(off + 1)

internal fun ByteArray.u32(off: Int): Long =
    (u8(off).toLong() shl 24) or
        (u8(off + 1).toLong() shl 16) or
        (u8(off + 2).toLong() shl 8) or
        u8(off + 3).toLong()

/** 32 位读成 Int（按位保留，可能为负，配合无符号比较使用）。 */
internal fun ByteArray.i32(off: Int): Int = u32(off).toInt()

internal fun ByteArray.u64(off: Int): Long {
    var v = 0L
    for (i in 0 until 8) {
        v = (v shl 8) or (this[off + i].toLong() and 0xFF)
    }
    return v
}

inline fun ByteArray.startsWith(prefix: ByteArray, offset: Int = 0): Boolean {
    if (offset < 0 || offset + prefix.size > size) return false
    for (i in prefix.indices) {
        if (this[offset + i] != prefix[i]) return false
    }
    return true
}

inline fun ByteArray.startsWithAscii(prefix: String, offset: Int = 0): Boolean {
    if (offset < 0 || offset + prefix.length > size) return false
    for (i in prefix.indices) {
        if ((this[offset + i].toInt() and 0xFF) != prefix[i].code) return false
    }
    return true
}

/** 读取以 0 结尾的定长字符串。 */
internal fun ByteArray.cString(off: Int, maxLen: Int): String {
    var end = off
    val limit = minOf(off + maxLen, size)
    while (end < limit && this[end].toInt() != 0) end++
    return String(this, off, end - off, Charsets.ISO_8859_1)
}

/** 无符号比较（Java 8 的 compareUnsigned 在低版本 Android 上不保证可用，手写更稳）。 */
internal fun unsignedLess(a: Int, b: Int): Boolean = (a xor Int.MIN_VALUE) < (b xor Int.MIN_VALUE)

package com.chennan.moting.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.chennan.moting.mobi.MobiFormatException
import com.chennan.moting.mobi.MobiParser
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** 导入 MOBI：复制到应用私有目录，解析元数据与封面，写入书架。 */
object BookImporter {

    private const val MAX_SIZE = 300L * 1024 * 1024

    fun import(context: Context, uri: Uri): BookRecord {
        val id = UUID.randomUUID().toString().replace("-", "")
        val booksDir = File(context.filesDir, "books").apply { mkdirs() }
        val temp = File(booksDir, "$id.part")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("无法读取所选文件")
            input.use { source ->
                FileOutputStream(temp).use { output -> source.copyTo(output, 128 * 1024) }
            }
            if (temp.length() < 512) throw MobiFormatException("文件过小，可能不是 MOBI 电子书")
            if (temp.length() > MAX_SIZE) throw MobiFormatException("文件超过 300MB，暂不支持")

            val bytes = temp.readBytes()
            val info = MobiParser(bytes).info()

            val target = File(booksDir, "$id.mobi")
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }

            val coverPath = saveCover(context, id, info.cover)
            val record = BookRecord(
                id = id,
                filePath = "books/${target.name}",
                title = info.title,
                author = info.author,
                coverPath = coverPath,
                sizeBytes = target.length(),
                addedAt = System.currentTimeMillis(),
                chapterCount = info.chapterCount,
                languageTag = info.languageTag,
            )
            LibraryRepository.upsert(record)
            return record
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    private fun saveCover(context: Context, id: String, bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty()) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 480) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            val scaled = if (bitmap.width > 480) {
                val height = (480f * bitmap.height / bitmap.width).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bitmap, 480, height, true)
            } else {
                bitmap
            }
            val dir = File(context.filesDir, "covers").apply { mkdirs() }
            val file = File(dir, "$id.jpg")
            FileOutputStream(file).use { scaled.compress(Bitmap.CompressFormat.JPEG, 86, it) }
            if (scaled !== bitmap) bitmap.recycle()
            scaled.recycle()
            "covers/${file.name}"
        }.getOrNull()
    }
}

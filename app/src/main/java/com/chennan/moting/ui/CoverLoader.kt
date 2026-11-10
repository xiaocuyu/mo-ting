package com.chennan.moting.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.util.concurrent.Executors

/** 简单的封面异步加载器（内存缓存 + 采样解码）。 */
object CoverLoader {

    private val cache = object : LruCache<String, Bitmap>(24) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun load(file: File?, seq: Int, callback: (bitmap: Bitmap?, seq: Int) -> Unit) {
        if (file == null || !file.exists()) {
            callback(null, seq)
            return
        }
        val key = "${file.absolutePath}@${file.lastModified()}"
        cache.get(key)?.let {
            callback(it, seq)
            return
        }
        executor.execute {
            val bitmap = runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) > 360) sample *= 2
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
            }.getOrNull()
            if (bitmap != null) cache.put(key, bitmap)
            main.post { callback(bitmap, seq) }
        }
    }
}

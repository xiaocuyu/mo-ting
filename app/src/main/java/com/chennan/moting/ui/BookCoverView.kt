package com.chennan.moting.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.chennan.moting.data.BookRecord
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * 书籍封面：有封面图则居中裁切显示，否则绘制暖色渐变与书名首字。
 */
class BookCoverView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val clipPath = Path()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFEADFCB.toInt()
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1).toFloat()
        color = 0x1AFFFFFF
    }
    private val spinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x22000000
    }
    private val rect = RectF()

    private var bitmap: Bitmap? = null
    private var title: String = ""
    private var seed: Int = 0
    private var requestSeq = 0

    private val palettes = arrayOf(
        0xFF3B2E23.toInt() to 0xFF221A13.toInt(),
        0xFF40302A.toInt() to 0xFF241713.toInt(),
        0xFF2E3630.toInt() to 0xFF1B211D.toInt(),
        0xFF3B2F3A.toInt() to 0xFF231A20.toInt(),
        0xFF3A3323.toInt() to 0xFF211C12.toInt(),
        0xFF332E3D.toInt() to 0xFF1D1A24.toInt(),
    )

    fun bind(record: BookRecord?) {
        requestSeq++
        val seq = requestSeq
        title = record?.title.orEmpty()
        seed = title.hashCode()
        bitmap = null
        val coverFile = record?.coverPath?.let { File(context.filesDir, it) }
        CoverLoader.load(coverFile, seq) { loaded, callbackSeq ->
            if (callbackSeq == seq) {
                bitmap = loaded
                invalidate()
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val radius = context.dp(9).toFloat()

        clipPath.reset()
        clipPath.addRoundRect(0f, 0f, w, h, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)

        val image = bitmap
        if (image != null) {
            val scale = max(w / image.width, h / image.height)
            val dw = image.width * scale
            val dh = image.height * scale
            val left = (w - dw) / 2f
            val top = (h - dh) / 2f
            rect.set(left, top, left + dw, top + dh)
            canvas.drawBitmap(image, null, rect, bitmapPaint)
        } else {
            val palette = palettes[(seed and Int.MAX_VALUE) % palettes.size]
            fillPaint.shader = LinearGradient(
                0f, 0f, w * 0.3f, h,
                palette.first,
                palette.second,
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w, h, fillPaint)
            canvas.drawRect(0f, 0f, w * 0.055f, h, spinePaint)

            val letter = title.trim().firstOrNull { it.isLetterOrDigit() }?.toString() ?: "书"
            letterPaint.textSize = min(w, h) * 0.42f
            val baseline = h / 2f - (letterPaint.descent() + letterPaint.ascent()) / 2f
            canvas.drawText(letter, w / 2f, baseline, letterPaint)
        }
        canvas.restore()

        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, radius, radius, borderPaint)
        borderPaint.color = 0x1AFFFFFF
    }
}

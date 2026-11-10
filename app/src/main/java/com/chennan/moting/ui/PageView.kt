package com.chennan.moting.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 自绘分页阅读视图：
 *  - 用 StaticLayout 排整章，按可视高度切页；
 *  - 支持左右滑动 / 点击两侧翻页，点击中间切换工具栏；
 *  - 支持行内高亮（TTS 朗读定位）。
 */
class PageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.SERIF
        color = 0xFF2B2620.toInt()
        textSize = context.sp(19)
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66F0D9A8
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var content: CharSequence = ""
    private var layout: StaticLayout? = null

    private var pageStartLines = IntArray(0)
    private var pageEndLines = IntArray(0)
    private var pageStartChars = IntArray(0)
    private var pageEndChars = IntArray(0)
    private var currentPage = 0

    private var highlightStart = -1
    private var highlightEnd = -1

    private var fontSizePx = context.sp(19)
    private var lineSpacingMultiplier = 1.55f
    private var textColor = 0xFF2B2620.toInt()
    private var highlightColor = 0x66F0D9A8

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var dragging = false
    private var dragOffset = 0f
    private var animationTarget = -1
    private var animationDirection = 0
    private var animator: ValueAnimator? = null
    private var pendingRestoreChar = -1

    var onPageChanged: ((page: Int, pageCount: Int) -> Unit)? = null
    var onChromeToggle: (() -> Unit)? = null
    /** 在首页继续向前 / 末页继续向后翻时触发，由外部切换章节。 */
    var onChapterBoundary: ((direction: Int) -> Unit)? = null

    init {
        isClickable = true
        highlightPaint.color = highlightColor
    }

    fun setTypeface(typeface: Typeface) {
        textPaint.typeface = typeface
        rebuildLayout()
    }

    fun setTextSize(px: Float) {
        updateTypography(px, lineSpacingMultiplier)
    }

    fun setLineSpacingMultiplier(multiplier: Float) {
        updateTypography(fontSizePx, multiplier)
    }

    /** 同时更新字号与行距，只重建一次排版。 */
    fun updateTypography(sizePx: Float, spacingMultiplier: Float) {
        if (sizePx == fontSizePx && spacingMultiplier == lineSpacingMultiplier) return
        fontSizePx = sizePx
        textPaint.textSize = sizePx
        lineSpacingMultiplier = spacingMultiplier
        rebuildLayout()
    }

    fun setColors(textColorValue: Int, highlightColorValue: Int) {
        textColor = textColorValue
        textPaint.color = textColorValue
        highlightColor = highlightColorValue
        highlightPaint.color = highlightColorValue
        invalidate()
    }

    /** 内边距变化后重新分页（保持当前字符位置）。 */
    fun refreshPagination() {
        rebuildLayout()
    }

    fun setChapter(content: CharSequence, restoreChar: Int = 0) {
        this.content = content
        pendingRestoreChar = restoreChar.coerceAtLeast(0)
        rebuildLayout()
    }

    val pageCount: Int get() = pageStartLines.size

    val page: Int get() = currentPage

    fun currentCharOffset(): Int = pageStartChars.getOrElse(currentPage) { 0 }

    fun pageCharBounds(): Pair<Int, Int> =
        if (currentPage in pageStartChars.indices) {
            pageStartChars[currentPage] to pageEndChars[currentPage]
        } else {
            0 to 0
        }

    fun setHighlight(start: Int, end: Int, ensureVisible: Boolean = true) {
        highlightStart = start
        highlightEnd = end
        if (ensureVisible && start >= 0 && pageStartChars.isNotEmpty()) {
            val target = pageForChar(start)
            if (target >= 0 && target != currentPage && !dragging && animator == null) {
                currentPage = target
                notifyPageChanged()
            }
        }
        invalidate()
    }

    fun clearHighlight() {
        highlightStart = -1
        highlightEnd = -1
        invalidate()
    }

    fun goToChar(offset: Int, notify: Boolean = true) {
        if (pageStartChars.isEmpty()) return
        val target = pageForChar(offset)
        if (target >= 0 && target != currentPage) {
            currentPage = target
            if (notify) notifyPageChanged() else invalidate()
        }
    }

    fun goToPage(target: Int, notify: Boolean = true) {
        if (target !in pageStartLines.indices || target == currentPage) return
        currentPage = target
        if (notify) notifyPageChanged() else invalidate()
    }

    fun turnPage(direction: Int, animate: Boolean = true) {
        if (pageCount == 0 || direction == 0) return
        val target = currentPage + direction
        if (target !in 0 until pageCount) {
            onChapterBoundary?.invoke(direction)
            return
        }
        if (animate && width > 0) {
            animateTurn(target, direction)
        } else {
            currentPage = target
            notifyPageChanged()
        }
    }

    /** 新章节内容就位后，从右侧（下一章）或左侧（上一章）滑入。 */
    fun playEnterAnimation(direction: Int) {
        if (width <= 0) {
            invalidate()
            return
        }
        animator?.cancel()
        animationTarget = -1
        animationDirection = direction
        val start = if (direction > 0) width.toFloat() else -width.toFloat()
        dragOffset = start
        val valueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                dragOffset = start * (1f - animation.animatedValue as Float)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    dragOffset = 0f
                    animationDirection = 0
                    animator = null
                    invalidate()
                }
            })
        }
        animator = valueAnimator
        valueAnimator.start()
    }

    fun nextChapterFlash() {
        // 预留给章节切换时的视觉反馈（当前版本未使用）
    }

    // ------------------------------------------------------------ 排版

    private fun rebuildLayout() {
        val width = width - paddingLeft - paddingRight
        val height = height - paddingTop - paddingBottom
        if (width <= 0 || height <= 0 || content.isEmpty()) {
            layout = null
            pageStartLines = IntArray(0)
            pageEndLines = IntArray(0)
            pageStartChars = IntArray(0)
            pageEndChars = IntArray(0)
            invalidate()
            return
        }
        val newLayout = StaticLayout.Builder
            .obtain(content, 0, content.length, textPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, lineSpacingMultiplier)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()

        val starts = ArrayList<Int>()
        val ends = ArrayList<Int>()
        var line = 0
        while (line < newLayout.lineCount) {
            starts.add(line)
            var accumulated = 0f
            var end = line
            while (end < newLayout.lineCount) {
                val lineHeight = (newLayout.getLineBottom(end) - newLayout.getLineTop(end)).toFloat()
                if (end > line && accumulated + lineHeight > height) break
                accumulated += lineHeight
                end++
            }
            if (end == line) end = line + 1
            ends.add(end)
            line = end
        }

        pageStartLines = starts.toIntArray()
        pageEndLines = ends.toIntArray()
        pageStartChars = IntArray(starts.size) { newLayout.getLineStart(starts[it]) }
        pageEndChars = IntArray(ends.size) { newLayout.getLineEnd(ends[it] - 1) }

        val restore = if (pendingRestoreChar >= 0) pendingRestoreChar else currentCharOffset()
        pendingRestoreChar = -1
        layout = newLayout
        currentPage = pageForChar(restore).coerceIn(0, max(0, pageStartLines.size - 1))
        notifyPageChanged()
    }

    private fun pageForChar(offset: Int): Int {
        if (pageStartChars.isEmpty()) return -1
        var low = 0
        var high = pageStartChars.size - 1
        var result = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (pageStartChars[mid] <= offset) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    private fun notifyPageChanged() {
        invalidate()
        onPageChanged?.invoke(currentPage, pageCount)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            val restore = pendingRestoreChar
            if (restore < 0) pendingRestoreChar = currentCharOffset()
            rebuildLayout()
        }
    }

    // ------------------------------------------------------------ 绘制

    override fun onDraw(canvas: Canvas) {
        val layout = layout ?: return
        if (pageStartLines.isEmpty()) return
        val pageTop = layout.getLineTop(pageStartLines[currentPage]).toFloat()

        if (animationTarget >= 0) {
            val targetTop = layout.getLineTop(pageStartLines[animationTarget]).toFloat()
            val targetX = if (animationDirection > 0) width + dragOffset else dragOffset - width
            drawPage(canvas, layout, animationTarget, targetX, targetTop)
        }

        drawPage(canvas, layout, currentPage, dragOffset, pageTop)
        if (dragOffset != 0f) {
            val edge = if (dragOffset < 0) dragOffset + width else dragOffset
            drawEdgeShadow(canvas, edge, dragOffset < 0)
        }
    }

    private fun drawPage(canvas: Canvas, layout: StaticLayout, page: Int, offsetX: Float, top: Float) {
        val pageBottom = layout.getLineBottom(pageEndLines[page] - 1).toFloat()
        val clipBottom = minOf(
            (height - paddingBottom).toFloat(),
            paddingTop + (pageBottom - top),
        )
        canvas.save()
        canvas.clipRect(0f, paddingTop.toFloat(), width.toFloat(), clipBottom)
        canvas.translate(paddingLeft + offsetX, paddingTop - top)
        if (page == currentPage) drawHighlight(canvas, layout, page)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawHighlight(canvas: Canvas, layout: StaticLayout, page: Int) {
        if (highlightStart < 0 || highlightEnd <= highlightStart) return
        val start = max(highlightStart, pageStartChars[page])
        val end = min(highlightEnd, pageEndChars[page])
        if (start >= end) return
        val startLine = layout.getLineForOffset(start)
        val endLine = layout.getLineForOffset((end - 1).coerceAtLeast(start))
        val radius = context.dp(3).toFloat()
        for (line in startLine..endLine) {
            if (line < pageStartLines[page] || line >= pageEndLines[page]) continue
            val left = if (line == startLine) layout.getPrimaryHorizontal(start) else layout.getLineLeft(line)
            val right = if (line == endLine) {
                if (end >= layout.getLineEnd(line)) layout.getLineRight(line) else layout.getPrimaryHorizontal(end)
            } else {
                layout.getLineRight(line)
            }
            val top = layout.getLineTop(line).toFloat()
            val bottom = layout.getLineBottom(line).toFloat()
            rect.set(left - context.dp(1), top + 1f, right + context.dp(1), bottom - 1f)
            canvas.drawRoundRect(rect, radius, radius, highlightPaint)
        }
    }

    private fun drawEdgeShadow(canvas: Canvas, edge: Float, movingLeft: Boolean) {
        val shadowWidth = context.dp(18).toFloat()
        val left = if (movingLeft) edge else edge - shadowWidth
        val right = left + shadowWidth
        shadowPaint.shader = LinearGradient(
            left,
            0f,
            right,
            0f,
            if (movingLeft) intArrayOf(0x33000000, 0x00000000) else intArrayOf(0x00000000, 0x33000000),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(left, 0f, right, height.toFloat(), shadowPaint)
    }

    // ------------------------------------------------------------ 翻页动画

    private fun animateTurn(target: Int, direction: Int) {
        animator?.cancel()
        animationTarget = target
        animationDirection = direction
        val startOffset = dragOffset
        val endOffset = if (direction > 0) -width.toFloat() else width.toFloat()
        val valueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float
                dragOffset = startOffset + (endOffset - startOffset) * fraction
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    currentPage = target
                    dragOffset = 0f
                    animationTarget = -1
                    animationDirection = 0
                    animator = null
                    notifyPageChanged()
                }
            })
        }
        animator = valueAnimator
        valueAnimator.start()
    }

    private fun snapBack() {
        animator?.cancel()
        val start = dragOffset
        if (start == 0f) {
            animationTarget = -1
            return
        }
        val valueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float
                dragOffset = start * (1f - fraction)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    dragOffset = 0f
                    animationTarget = -1
                    animationDirection = 0
                    animator = null
                    invalidate()
                }
            })
        }
        animator = valueAnimator
        valueAnimator.start()
    }

    // ------------------------------------------------------------ 触摸

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (pageCount == 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                animator?.cancel()
                animator = null
                animationTarget = -1
                dragOffset = 0f
                downX = event.x
                lastX = event.x
                downY = event.y
                dragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (!dragging && abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) {
                    dragOffset = when {
                        dx < 0 && currentPage >= pageCount - 1 -> dx * 0.18f
                        dx > 0 && currentPage <= 0 -> dx * 0.18f
                        else -> dx
                    }
                    animationTarget = when {
                        dragOffset < -4f && currentPage < pageCount - 1 -> currentPage + 1
                        dragOffset > 4f && currentPage > 0 -> currentPage - 1
                        else -> -1
                    }
                    animationDirection = if (dragOffset < 0) 1 else -1
                    lastX = event.x
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    dragging = false
                    val threshold = width * 0.22f
                    val dx = event.x - downX
                    val atEdge = (currentPage <= 0 && dx > 0) ||
                        (currentPage >= pageCount - 1 && dx < 0)
                    if (atEdge && abs(dx) > threshold) {
                        dragOffset = 0f
                        animationTarget = -1
                        animationDirection = 0
                        invalidate()
                        onChapterBoundary?.invoke(if (dx < 0) 1 else -1)
                        return true
                    }
                    when {
                        animationTarget >= 0 && abs(dragOffset) > threshold -> {
                            animateTurn(animationTarget, animationDirection)
                        }

                        else -> snapBack()
                    }
                    return true
                }
                val x = event.x
                when {
                    x < width * 0.28f -> turnPage(-1)
                    x > width * 0.72f -> turnPage(1)
                    else -> onChromeToggle?.invoke()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                snapBack()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    fun setHighlightColor(color: Int) {
        highlightColor = color
        highlightPaint.color = color
        invalidate()
    }

    fun setTextColorValue(color: Int) {
        textColor = color
        textPaint.color = color
        invalidate()
    }

    fun currentTextColor(): Int = textColor
}

package com.chennan.moting.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.chennan.moting.R
import com.chennan.moting.data.BookRecord
import com.chennan.moting.data.LibraryRepository
import com.chennan.moting.data.ReaderPrefs
import com.chennan.moting.mobi.MobiBook
import com.chennan.moting.mobi.MobiParser
import com.chennan.moting.text.HtmlToText
import com.chennan.moting.text.ParsedChapter
import com.chennan.moting.tts.TtsController
import com.chennan.moting.tts.TtsService
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

class ReaderActivity : Activity(), TtsController.Listener {

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_CHAPTER = "chapter"
        const val EXTRA_CHAR = "char"
        const val EXTRA_LISTEN = "listen"
        const val EXTRA_SHOW_TOC = "show_toc"
        const val EXTRA_SHOW_SETTINGS = "show_settings"
        const val EXTRA_SKIP_PERMISSION = "skip_permission"
        const val EXTRA_WAKE = "wake"
        const val EXTRA_AUTO_TURN = "auto_turn"
        const val EXTRA_AUTO_DIR = "auto_dir"
        private const val REQUEST_NOTIFICATION = 61
    }

    private lateinit var record: BookRecord
    private var book: MobiBook? = null
    private var chapterIndex = 0
    private var parsed: ParsedChapter? = null
    private var spannable: SpannableString? = null
    private var chapterText = ""
    private var cumulative = FloatArray(0)
    private var totalWeight = 1f

    private var theme = ReaderTheme.PAPER
    private var fontSize = ReaderPrefs.DEFAULT_FONT_SIZE
    private var lineSpacing = ReaderPrefs.DEFAULT_LINE_SPACING
    private var chromeVisible = true
    private var ttsActive = false
    private var languageWarningShown = false

    private lateinit var root: View
    private lateinit var pageView: PageView
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var ttsBar: View
    private lateinit var chapterTitle: TextView
    private lateinit var pageInfo: TextView
    private lateinit var bookProgress: TextView
    private lateinit var listenButton: ImageView
    private lateinit var loadingBox: View
    private lateinit var loadingText: TextView
    private lateinit var loadingProgress: ProgressBar
    private lateinit var ttsTitle: TextView
    private lateinit var ttsStatus: TextView
    private lateinit var ttsPlay: ImageView
    private lateinit var ttsSpeed: TextView

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable { persistProgress() }
    private val ttsChapterHandler: () -> Unit = { onTtsChapterFinished() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LibraryRepository.init(this)
        val bookId = intent.getStringExtra(EXTRA_BOOK_ID)
        val found = bookId?.let { LibraryRepository.find(it) }
        if (found == null) {
            Toast.makeText(this, R.string.error_book_missing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        record = found
        theme = ReaderPrefs.theme(this)
        fontSize = ReaderPrefs.fontSize(this)
        lineSpacing = ReaderPrefs.lineSpacing(this)

        if (intent.getBooleanExtra(EXTRA_WAKE, false)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) setShowWhenLocked(true)
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }
        setContentView(R.layout.activity_reader)
        bindViews()
        applyTheme()
        TtsController.addListener(this)
        TtsController.chapterFinishedHandler = ttsChapterHandler
        loadBook()
    }

    private fun bindViews() {
        root = findViewById(R.id.readerRoot)
        pageView = findViewById(R.id.pageView)
        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)
        ttsBar = findViewById(R.id.ttsBar)
        chapterTitle = findViewById(R.id.chapterTitle)
        pageInfo = findViewById(R.id.pageInfo)
        bookProgress = findViewById(R.id.bookProgress)
        listenButton = findViewById(R.id.listenButton)
        loadingBox = findViewById(R.id.loadingBox)
        loadingText = findViewById(R.id.loadingText)
        loadingProgress = findViewById(R.id.loadingProgress)
        ttsTitle = findViewById(R.id.ttsTitle)
        ttsStatus = findViewById(R.id.ttsStatus)
        ttsPlay = findViewById(R.id.ttsPlay)
        ttsSpeed = findViewById(R.id.ttsSpeed)

        pageView.onPageChanged = { _, _ ->
            updateIndicators()
            scheduleSave()
        }
        pageView.onChromeToggle = { toggleChrome() }
        pageView.onChapterBoundary = { direction -> openAdjacentChapter(direction) }

        findViewById<View>(R.id.back).setOnClickListener { onBackPressed() }
        findViewById<View>(R.id.tocButton).setOnClickListener { showToc() }
        findViewById<View>(R.id.settingsButton).setOnClickListener { showSettings() }
        listenButton.setOnClickListener { onListenClicked() }
        ttsPlay.setOnClickListener { onTtsPlayClicked() }
        findViewById<View>(R.id.ttsPrev).setOnClickListener { TtsController.previous() }
        findViewById<View>(R.id.ttsNext).setOnClickListener { TtsController.next() }
        findViewById<View>(R.id.ttsClose).setOnClickListener { stopTts() }
        ttsSpeed.setOnClickListener { showSettings() }
    }

    // ---------------------------------------------------------------- 加载

    private fun loadBook() {
        loadingBox.visibility = View.VISIBLE
        loadingText.text = getString(R.string.opening_book, record.title)
        executor.execute {
            try {
                val file = LibraryRepository.fileFor(record)
                if (!file.exists()) throw IllegalStateException(getString(R.string.error_file_missing))
                val bytes = file.readBytes()
                val parsedBook = MobiParser(bytes).open()
                main.post { onBookReady(parsedBook) }
            } catch (e: Exception) {
                val message = e.message ?: getString(R.string.import_unsupported)
                main.post { onBookFailed(message) }
            }
        }
    }

    private fun onBookReady(loaded: MobiBook) {
        if (isFinishing || isDestroyed) return
        book = loaded
        cumulative = FloatArray(loaded.chapters.size + 1)
        var acc = 0f
        for (i in loaded.chapters.indices) {
            cumulative[i] = acc
            acc += loaded.chapterWeight(i).toFloat()
        }
        cumulative[loaded.chapters.size] = acc
        totalWeight = acc.coerceAtLeast(1f)
        loadingBox.visibility = View.GONE
        record.chapterCount = loaded.chapters.size
        val chapterExtra = intent.getIntExtra(EXTRA_CHAPTER, -1)
        val index = (if (chapterExtra >= 0) chapterExtra else record.chapterIndex)
            .coerceIn(0, loaded.chapters.size - 1)
        val charOffset = if (intent.hasExtra(EXTRA_CHAR)) {
            intent.getIntExtra(EXTRA_CHAR, 0)
        } else {
            record.charOffset
        }
        openChapter(index, charOffset)
        applyChrome()
        if (intent.getBooleanExtra(EXTRA_LISTEN, false)) {
            pageView.postDelayed({ if (!isFinishing) onListenClicked() }, 500)
        }
        if (intent.getBooleanExtra(EXTRA_SHOW_TOC, false)) {
            pageView.postDelayed({ if (!isFinishing) showToc() }, 400)
        }
        if (intent.getBooleanExtra(EXTRA_SHOW_SETTINGS, false)) {
            pageView.postDelayed({ if (!isFinishing) showSettings() }, 400)
        }
        if (intent.getBooleanExtra(EXTRA_AUTO_TURN, false)) {
            val direction = if (intent.getIntExtra(EXTRA_AUTO_DIR, 1) < 0) -1 else 1
            val turner = object : Runnable {
                override fun run() {
                    if (isFinishing || isDestroyed) return
                    pageView.turnPage(direction)
                    pageView.postDelayed(this, 2200)
                }
            }
            pageView.postDelayed(turner, 1500)
        }
    }

    private fun onBookFailed(message: String) {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle(R.string.error_open_title)
            .setMessage(message)
            .setPositiveButton(R.string.confirm) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    // ---------------------------------------------------------------- 章节

    private fun openChapter(index: Int, charOffset: Int = 0) {
        val currentBook = book ?: return
        if (index !in currentBook.chapters.indices) return
        chapterIndex = index
        val html = currentBook.chapterHtml(index)
        val parsedChapter = HtmlToText.convert(html)
        parsed = parsedChapter
        chapterText = parsedChapter.text
        val rendered = ChapterRenderer.render(parsedChapter)
        spannable = rendered
        pageView.updateTypography(sp(fontSize), lineSpacing)
        pageView.setChapter(rendered, charOffset)
        chapterTitle.text = currentBook.chapters[index].title
        updateIndicators()
        scheduleSave()
    }

    /** 手动翻到章节边界时切换上一章 / 下一章。 */
    private fun openAdjacentChapter(direction: Int) {
        val currentBook = book ?: return
        val target = chapterIndex + direction
        if (target !in currentBook.chapters.indices) {
            Toast.makeText(
                this,
                if (direction > 0) R.string.last_chapter else R.string.first_chapter,
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val wasPlaying = TtsController.state == TtsController.State.PLAYING
        val listening = ttsActive
        openChapter(target, if (direction > 0) 0 else Int.MAX_VALUE)
        pageView.playEnterAnimation(direction)
        if (listening) {
            val block = if (direction > 0) 0 else (parsed?.blocks?.lastIndex ?: 0)
            startTtsAt(block, autoplay = wasPlaying)
        }
    }

    private fun updateIndicators() {
        val currentBook = book ?: return
        pageInfo.text = getString(
            R.string.page_indicator,
            pageView.page + 1,
            max(pageView.pageCount, 1),
        )
        val overall = overallProgress()
        bookProgress.text = String.format(Locale.US, "%.1f%%", overall * 100f)
        chapterTitle.text = currentBook.chapters.getOrNull(chapterIndex)?.title.orEmpty()
        ttsTitle.text = currentBook.chapters.getOrNull(chapterIndex)?.title.orEmpty()
    }

    private fun overallProgress(): Float {
        val currentBook = book ?: return 0f
        if (currentBook.chapters.isEmpty()) return 0f
        val start = cumulative.getOrElse(chapterIndex) { 0f }
        val end = cumulative.getOrElse(chapterIndex + 1) { totalWeight }
        val length = chapterText.length.coerceAtLeast(1)
        val within = (pageView.currentCharOffset().toFloat() / length).coerceIn(0f, 1f)
        return ((start + within * (end - start)) / totalWeight).coerceIn(0f, 1f)
    }

    private fun persistProgress() {
        val currentBook = book ?: return
        record.chapterIndex = chapterIndex
        record.charOffset = pageView.currentCharOffset()
        record.chapterCount = currentBook.chapters.size
        record.progress = overallProgress()
        record.lastOpenedAt = System.currentTimeMillis()
        LibraryRepository.upsert(record)
    }

    private fun scheduleSave() {
        main.removeCallbacks(saveRunnable)
        main.postDelayed(saveRunnable, 800)
    }

    // ---------------------------------------------------------------- 主题与排版

    private fun applyTheme() {
        theme = ReaderPrefs.theme(this)
        window.setBackgroundDrawable(ColorDrawable(theme.background))
        root.setBackgroundColor(theme.background)
        loadingBox.setBackgroundColor(theme.background)
        loadingText.setTextColor(theme.secondary)
        loadingProgress.indeterminateTintList =
            android.content.res.ColorStateList.valueOf(theme.accent)
        pageView.setColors(theme.text, theme.highlight)
        chapterTitle.setTextColor(theme.secondary)
        pageInfo.setTextColor(theme.secondary)
        bookProgress.setTextColor(theme.secondary)
        (topBar as? View)?.setBackgroundColor(theme.background)
        bottomBar.setBackgroundColor(theme.background)
        tint(findViewById(R.id.back), theme.secondary)
        tint(findViewById(R.id.tocButton), theme.secondary)
        tint(findViewById(R.id.settingsButton), theme.secondary)
        tint(listenButton, theme.accent)
        applySystemBars(theme.background, theme.lightStatusBar)
    }

    private fun tint(view: View, color: Int) {
        (view as? ImageView)?.setColorFilter(color)
    }

    private fun applyTypography() {
        pageView.updateTypography(sp(fontSize), lineSpacing)
    }

    // ---------------------------------------------------------------- 听书

    private fun onListenClicked() {
        if (ttsActive) {
            onTtsPlayClicked()
            return
        }
        val currentBook = book ?: return
        val chapter = parsed ?: return
        if (chapter.blocks.isEmpty()) return
        requestNotificationPermission()
        TtsController.ensureReady(this)
        TtsController.setSpeed(ReaderPrefs.ttsSpeed(this))
        ttsActive = true
        startTtsAt(blockIndexFor(pageView.currentCharOffset()), autoplay = true)
        TtsService.start(this)
        applyChrome()
        updateTtsUi()
    }

    private fun onTtsPlayClicked() {
        when (TtsController.state) {
            TtsController.State.PLAYING -> TtsController.pause()
            TtsController.State.PAUSED -> TtsController.play(this)
            TtsController.State.IDLE -> onListenClicked()
            TtsController.State.PREPARING -> Unit
        }
    }

    private fun startTtsAt(blockIndex: Int, autoplay: Boolean) {
        val currentBook = book ?: return
        val chapter = parsed ?: return
        TtsController.setContent(
            bookTitle = currentBook.title,
            chapterTitle = currentBook.chapters.getOrNull(chapterIndex)?.title.orEmpty(),
            chapterIndex = chapterIndex,
            languageTag = currentBook.languageTag,
            text = chapterText,
            blocks = chapter.blocks,
            startBlockIndex = blockIndex,
        )
        if (autoplay) TtsController.play(this)
    }

    private fun blockIndexFor(offset: Int): Int {
        val blocks = parsed?.blocks ?: return 0
        val index = blocks.indexOfLast { it.start <= offset }
        return if (index < 0) 0 else index
    }

    private fun stopTts() {
        TtsController.stop()
        ttsActive = false
        pageView.clearHighlight()
        applyChrome()
    }

    private fun onTtsChapterFinished() {
        if (isFinishing || isDestroyed) return
        val currentBook = book ?: return
        if (chapterIndex + 1 < currentBook.chapters.size) {
            openChapter(chapterIndex + 1, 0)
            startTtsAt(0, autoplay = true)
        } else {
            Toast.makeText(this, R.string.tts_book_finished, Toast.LENGTH_SHORT).show()
            stopTts()
        }
    }

    override fun onTtsState(state: TtsController.State) {
        runOnUiThread {
            ttsActive = state != TtsController.State.IDLE
            if (state == TtsController.State.IDLE) pageView.clearHighlight()
            updateTtsUi()
            applyChrome()
        }
    }

    override fun onTtsProgress(blockIndex: Int, start: Int, end: Int) {
        runOnUiThread {
            if (TtsController.state != TtsController.State.PLAYING) return@runOnUiThread
            pageView.setHighlight(start, end)
            scheduleSave()
        }
    }

    override fun onTtsContentChanged() {
        runOnUiThread { updateTtsUi() }
    }

    override fun onTtsError(message: String?) {
        runOnUiThread {
            when (message) {
                "init" -> toastLong(R.string.tts_missing)
                "language" -> if (!languageWarningShown) {
                    languageWarningShown = true
                    toastLong(R.string.tts_language_unsupported)
                }

                "sleep" -> toastLong(R.string.tts_sleep_finished)
                "engine", "speak" -> toastLong(R.string.tts_error)
                else -> Unit
            }
        }
    }

    private fun toastLong(res: Int) {
        Toast.makeText(this, res, Toast.LENGTH_LONG).show()
    }

    private fun updateTtsUi() {
        val currentBook = book ?: return
        val state = TtsController.state
        ttsTitle.text = currentBook.chapters.getOrNull(chapterIndex)?.title.orEmpty()
        ttsPlay.setImageResource(if (state == TtsController.State.PLAYING) R.drawable.ic_pause else R.drawable.ic_play)
        val speed = ReaderPrefs.ttsSpeed(this)
        ttsSpeed.text = formatSpeed(speed)
        val base = when (state) {
            TtsController.State.PLAYING -> getString(R.string.tts_playing)
            TtsController.State.PAUSED -> getString(R.string.tts_paused)
            TtsController.State.PREPARING -> getString(R.string.tts_preparing)
            TtsController.State.IDLE -> ""
        }
        val sleep = TtsController.sleepTimerMinutes()
        ttsStatus.text = buildString {
            append(base)
            if (state != TtsController.State.IDLE) {
                append(" · ")
                append(formatSpeed(speed))
            }
            if (sleep > 0) {
                append(" · ")
                append(getString(R.string.sleep_remaining, sleep))
            }
        }
    }

    private fun formatSpeed(value: Float): String {
        val text = if (value == value.toInt().toFloat()) {
            value.toInt().toString()
        } else {
            value.toString().trimEnd('0').trimEnd('.')
        }
        return getString(R.string.tts_speed_format, text)
    }

    // ---------------------------------------------------------------- 工具栏

    private fun applyChrome() {
        topBar.visibility = if (chromeVisible) View.VISIBLE else View.GONE
        val showTts = ttsActive && TtsController.state != TtsController.State.IDLE
        bottomBar.visibility = if (chromeVisible && !showTts) View.VISIBLE else View.GONE
        ttsBar.visibility = if (chromeVisible && showTts) View.VISIBLE else View.GONE
        updateTtsPadding(showTts)
    }

    /** 听书时给底部播放条留出空间，避免遮挡正文。 */
    private fun updateTtsPadding(listening: Boolean) {
        val bottom = dp(if (listening) 118 else 70)
        if (pageView.paddingBottom == bottom) return
        pageView.setPadding(pageView.paddingLeft, pageView.paddingTop, pageView.paddingRight, bottom)
        pageView.refreshPagination()
    }

    private fun toggleChrome() {
        chromeVisible = !chromeVisible
        applyChrome()
    }

    // ---------------------------------------------------------------- 目录

    private fun showToc() {
        val currentBook = book ?: return
        val dialog = Dialog(this, R.style.BottomSheetTheme)
        val content = layoutInflater.inflate(R.layout.dialog_toc, null)
        dialog.setContentView(content)
        dialog.show()
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.82f).toInt())
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        content.findViewById<TextView>(R.id.tocCount).text =
            getString(R.string.toc_count, currentBook.chapters.size)
        content.findViewById<View>(R.id.tocClose).setOnClickListener { dialog.dismiss() }
        val list = content.findViewById<ListView>(R.id.tocList)
        list.adapter = object : BaseAdapter() {
            override fun getCount(): Int = currentBook.chapters.size
            override fun getItem(position: Int): Any = currentBook.chapters[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_toc, parent, false)
                val title = view.findViewById<TextView>(R.id.tocTitle)
                val chapter = currentBook.chapters[position]
                title.text = chapter.title.ifBlank { getString(R.string.chapter_fallback, position + 1) }
                if (position == chapterIndex) {
                    title.setTextColor(theme.accent)
                    title.setTypeface(null, Typeface.BOLD)
                } else {
                    title.setTextColor(getColor(R.color.text_secondary))
                    title.setTypeface(null, Typeface.NORMAL)
                }
                return view
            }
        }
        list.setSelection(chapterIndex)
        list.setOnItemClickListener { _, _, position, _ ->
            dialog.dismiss()
            val wasPlaying = TtsController.state == TtsController.State.PLAYING
            openChapter(position, 0)
            if (ttsActive) startTtsAt(0, wasPlaying)
        }
    }

    // ---------------------------------------------------------------- 设置

    private fun showSettings() {
        val dialog = Dialog(this, R.style.BottomSheetTheme)
        val content = layoutInflater.inflate(R.layout.dialog_settings, null)
        dialog.setContentView(content)
        dialog.show()
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        content.findViewById<View>(R.id.settingsDone).setOnClickListener { dialog.dismiss() }

        // 主题
        val themeRow = content.findViewById<LinearLayout>(R.id.themeRow)
        val themeChips = ArrayList<TextView>()
        val themes = ReaderTheme.entries
        themes.forEachIndexed { index, item ->
            val chip = createChip(themeLabel(item))
            themeChips.add(chip)
            themeRow.addView(chip, chipParams(index, themes.size))
            chip.setOnClickListener {
                theme = item
                ReaderPrefs.setTheme(this, item)
                applyTheme()
                styleThemeChips(themeChips, item)
                spannable?.let { pageView.setChapter(it, pageView.currentCharOffset()) }
            }
        }
        styleThemeChips(themeChips, theme)

        // 字号
        val fontSeek = content.findViewById<SeekBar>(R.id.fontSeek)
        val fontValue = content.findViewById<TextView>(R.id.fontValue)
        fontSeek.max = ReaderPrefs.MAX_FONT_SIZE - ReaderPrefs.MIN_FONT_SIZE
        fontSeek.progress = fontSize - ReaderPrefs.MIN_FONT_SIZE
        fontValue.text = fontSize.toString()
        fontSeek.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                fontSize = ReaderPrefs.MIN_FONT_SIZE + progress
                ReaderPrefs.setFontSize(this@ReaderActivity, fontSize)
                fontValue.text = fontSize.toString()
                applyTypography()
            }
        })

        // 行距
        val lineSeek = content.findViewById<SeekBar>(R.id.lineSeek)
        val lineValue = content.findViewById<TextView>(R.id.lineValue)
        val steps = ((ReaderPrefs.MAX_LINE_SPACING - ReaderPrefs.MIN_LINE_SPACING) * 10).roundToInt()
        lineSeek.max = steps
        lineSeek.progress = ((lineSpacing - ReaderPrefs.MIN_LINE_SPACING) * 10).roundToInt()
        lineValue.text = String.format(Locale.US, "%.1f", lineSpacing)
        lineSeek.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                lineSpacing = ReaderPrefs.MIN_LINE_SPACING + progress / 10f
                ReaderPrefs.setLineSpacing(this@ReaderActivity, lineSpacing)
                lineValue.text = String.format(Locale.US, "%.1f", lineSpacing)
                applyTypography()
            }
        })

        // 语速
        val speedRow = content.findViewById<LinearLayout>(R.id.speedRow)
        val speedChips = ArrayList<TextView>()
        val speeds = ReaderPrefs.speedOptions
        val currentSpeed = ReaderPrefs.ttsSpeed(this)
        speeds.forEachIndexed { index, speed ->
            val chip = createChip(formatSpeed(speed))
            speedChips.add(chip)
            speedRow.addView(chip, chipParams(index, speeds.size))
            chip.setOnClickListener {
                ReaderPrefs.setTtsSpeed(this, speed)
                TtsController.setSpeed(speed)
                styleChips(speedChips, index)
                updateTtsUi()
            }
        }
        styleChips(speedChips, nearestSpeedIndex(currentSpeed))

        // 定时停止
        val sleepRow = content.findViewById<LinearLayout>(R.id.sleepRow)
        val sleepChips = ArrayList<TextView>()
        val sleepOptions = intArrayOf(0, 15, 30, 60)
        val currentSleep = TtsController.sleepTimerMinutes()
        sleepOptions.forEachIndexed { index, minutes ->
            val label = when (minutes) {
                0 -> getString(R.string.sleep_off)
                else -> getString(R.string.sleep_minutes, minutes)
            }
            val chip = createChip(label)
            sleepChips.add(chip)
            sleepRow.addView(chip, chipParams(index, sleepOptions.size))
            chip.setOnClickListener {
                TtsController.setSleepTimer(minutes)
                styleChips(sleepChips, index)
                updateTtsUi()
            }
        }
        styleChips(sleepChips, sleepOptions.indexOf(currentSleep).coerceAtLeast(0))
    }

    private fun nearestSpeedIndex(speed: Float): Int {
        var best = 0
        var bestDiff = Float.MAX_VALUE
        ReaderPrefs.speedOptions.forEachIndexed { index, value ->
            val diff = kotlin.math.abs(value - speed)
            if (diff < bestDiff) {
                bestDiff = diff
                best = index
            }
        }
        return best
    }

    private fun themeLabel(theme: ReaderTheme): String = when (theme) {
        ReaderTheme.PAPER -> getString(R.string.theme_paper)
        ReaderTheme.SEPIA -> getString(R.string.theme_sepia)
        ReaderTheme.NIGHT -> getString(R.string.theme_night)
    }

    private fun styleThemeChips(chips: List<TextView>, selected: ReaderTheme) {
        val index = ReaderTheme.entries.indexOf(selected)
        styleChips(chips, index)
    }

    private fun styleChips(chips: List<TextView>, selectedIndex: Int) {
        chips.forEachIndexed { index, chip ->
            val selected = index == selectedIndex
            chip.setBackgroundResource(if (selected) R.drawable.bg_chip_on else R.drawable.bg_chip)
            chip.setTextColor(getColor(if (selected) R.color.ink_on_accent else R.color.text_secondary))
        }
    }

    private fun createChip(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 11.5f
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(8), dp(4), dp(8))
        isClickable = true
    }

    private fun chipParams(index: Int, total: Int): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        if (index < total - 1) params.marginEnd = dp(8)
        return params
    }

    // ---------------------------------------------------------------- 权限与生命周期

    private fun requestNotificationPermission() {
        if (intent.getBooleanExtra(EXTRA_SKIP_PERMISSION, false)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION)
        }
    }

    override fun onPause() {
        super.onPause()
        if (book != null) persistProgress()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        main.removeCallbacks(saveRunnable)
        TtsController.removeListener(this)
        if (TtsController.chapterFinishedHandler === ttsChapterHandler) {
            TtsController.chapterFinishedHandler = null
        }
        if (isFinishing) TtsController.stop()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (!chromeVisible) {
            chromeVisible = true
            applyChrome()
            return
        }
        if (ttsActive) {
            Toast.makeText(this, R.string.tts_stopped, Toast.LENGTH_SHORT).show()
            stopTts()
        }
        super.onBackPressed()
    }

    private open class SimpleSeekListener : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) = Unit
        override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
    }
}

package com.chennan.moting.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.chennan.moting.R
import com.chennan.moting.data.BookRecord
import com.chennan.moting.data.LibraryRepository
import com.chennan.moting.mobi.MobiFormatException
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class LibraryActivity : Activity() {

    private lateinit var bookList: LinearLayout
    private lateinit var emptyState: View
    private lateinit var continueCard: View
    private lateinit var subtitle: TextView
    private lateinit var bookCount: TextView

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var progressDialog: AlertDialog? = null
    private var importing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LibraryRepository.init(this)
        setContentView(R.layout.activity_library)
        applySystemBars(getColor(R.color.ink_bg), lightIcons = false)

        bookList = findViewById(R.id.bookList)
        emptyState = findViewById(R.id.emptyState)
        continueCard = findViewById(R.id.continueCard)
        subtitle = findViewById(R.id.subtitle)
        bookCount = findViewById(R.id.bookCount)

        findViewById<View>(R.id.importButton).setOnClickListener { openPicker() }
        findViewById<View>(R.id.fab).setOnClickListener { openPicker() }
        findViewById<View>(R.id.emptyImportAction).setOnClickListener { openPicker() }

        if (savedInstanceState == null) handleExternalIntent(intent)
        handleDebugIntent(intent)
        if (savedInstanceState == null) openLastBookOnLaunch(intent)
    }

    /**
     * 冷启动直达上次阅读的书籍，省去每次从书架进入。
     * 导入（VIEW/SEND）与 adb 调试入口优先，不抢占。
     */
    private fun openLastBookOnLaunch(intent: Intent?) {
        val action = intent?.action
        if (action == Intent.ACTION_VIEW || action == Intent.ACTION_SEND) return
        if (intent?.hasExtra("open_book_id") == true) return
        val record = LibraryRepository.mostRecent() ?: return
        if (!LibraryRepository.fileFor(record).exists()) return
        startActivity(
            Intent(this, ReaderActivity::class.java)
                .putExtra(ReaderActivity.EXTRA_BOOK_ID, record.id)
        )
    }

    /** 仅用于 adb 自动化验证：通过 LibraryActivity 间接打开阅读器。 */
    private fun handleDebugIntent(intent: Intent?) {
        val bookId = intent?.getStringExtra("open_book_id") ?: return
        val book = LibraryRepository.find(bookId) ?: return
        val reader = Intent(this, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id)
        if (intent.hasExtra(ReaderActivity.EXTRA_CHAPTER)) {
            reader.putExtra(ReaderActivity.EXTRA_CHAPTER, intent.getIntExtra(ReaderActivity.EXTRA_CHAPTER, 0))
        }
        if (intent.hasExtra(ReaderActivity.EXTRA_CHAR)) {
            reader.putExtra(ReaderActivity.EXTRA_CHAR, intent.getIntExtra(ReaderActivity.EXTRA_CHAR, 0))
        }
        if (intent.getBooleanExtra(ReaderActivity.EXTRA_LISTEN, false)) {
            reader.putExtra(ReaderActivity.EXTRA_LISTEN, true)
        }
        if (intent.getBooleanExtra(ReaderActivity.EXTRA_SHOW_TOC, false)) {
            reader.putExtra(ReaderActivity.EXTRA_SHOW_TOC, true)
        }
        if (intent.getBooleanExtra(ReaderActivity.EXTRA_SHOW_SETTINGS, false)) {
            reader.putExtra(ReaderActivity.EXTRA_SHOW_SETTINGS, true)
        }
        if (intent.getBooleanExtra(ReaderActivity.EXTRA_SKIP_PERMISSION, false)) {
            reader.putExtra(ReaderActivity.EXTRA_SKIP_PERMISSION, true)
        }
        if (intent.getBooleanExtra(ReaderActivity.EXTRA_WAKE, false)) {
            reader.putExtra(ReaderActivity.EXTRA_WAKE, true)
        }
        if (intent.getBooleanExtra(ReaderActivity.EXTRA_AUTO_TURN, false)) {
            reader.putExtra(ReaderActivity.EXTRA_AUTO_TURN, true)
            reader.putExtra(ReaderActivity.EXTRA_AUTO_DIR, intent.getIntExtra(ReaderActivity.EXTRA_AUTO_DIR, 1))
        }
        startActivity(reader)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleExternalIntent(intent)
        handleDebugIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun handleExternalIntent(intent: Intent?) {
        Log.d("MoTing", "handleExternalIntent action=${intent?.action} data=${intent?.data}")
        if (intent == null) return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) importBook(uri)
    }

    private fun openPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_OPEN)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OPEN && resultCode == RESULT_OK) {
            data?.data?.let { importBook(it) }
        }
    }

    private fun importBook(uri: Uri) {
        if (importing) return
        importing = true
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_importing, null)
        progressDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()
            .also { it.show() }

        Log.d("MoTing", "importBook uri=$uri")
        executor.execute {
            try {
                val record = LibraryRepository.import(this, uri)
                Log.d("MoTing", "import ok: ${record.title}")
                main.post {
                    dismissImportDialog()
                    render()
                    Toast.makeText(this, R.string.import_done, Toast.LENGTH_SHORT).show()
                    openReader(record)
                }
            } catch (e: Exception) {
                Log.e("MoTing", "import failed", e)
                main.post {
                    dismissImportDialog()
                    val message = if (e is MobiFormatException) e.message else e.message ?: getString(R.string.import_unsupported)
                    AlertDialog.Builder(this)
                        .setTitle(R.string.import_failed_title)
                        .setMessage(message)
                        .setPositiveButton(R.string.confirm, null)
                        .show()
                }
            } finally {
                importing = false
            }
        }
    }

    private fun dismissImportDialog() {
        progressDialog?.dismiss()
        progressDialog = null
    }

    private fun openReader(record: BookRecord) {
        startActivity(
            Intent(this, ReaderActivity::class.java)
                .putExtra(ReaderActivity.EXTRA_BOOK_ID, record.id)
        )
    }

    // ---------------------------------------------------------------- 渲染

    private fun render() {
        val books = LibraryRepository.books()
        subtitle.text = getString(R.string.library_subtitle) + " · " + getString(R.string.book_count, books.size)
        bookCount.text = getString(R.string.book_count, books.size)
        emptyState.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE

        renderContinue(books)
        renderList(books)
    }

    private fun renderContinue(books: List<BookRecord>) {
        val recent = books.filter { it.lastOpenedAt > 0L }.maxByOrNull { it.lastOpenedAt }
        if (recent == null) {
            continueCard.visibility = View.GONE
            return
        }
        continueCard.visibility = View.VISIBLE
        continueCard.findViewById<BookCoverView>(R.id.continueCover).bind(recent)
        continueCard.findViewById<TextView>(R.id.continueTitle).text = recent.title
        val meta = buildString {
            append(getString(R.string.chapter_progress, recent.chapterIndex + 1, recent.chapterCount.coerceAtLeast(1)))
            if (recent.progress > 0f) {
                append(" · ")
                append(formatPercent(recent.progress))
            }
        }
        continueCard.findViewById<TextView>(R.id.continueMeta).text = meta
        continueCard.findViewById<ProgressBar>(R.id.continueProgress).progress =
            (recent.progress.coerceIn(0f, 1f) * 1000).toInt()
        continueCard.setOnClickListener { openReader(recent) }
    }

    private fun renderList(books: List<BookRecord>) {
        bookList.removeAllViews()
        val inflater = LayoutInflater.from(this)
        books.forEach { book ->
            val item = inflater.inflate(R.layout.item_book, bookList, false)
            item.findViewById<BookCoverView>(R.id.cover).bind(book)
            item.findViewById<TextView>(R.id.title).text = book.title
            val author = book.author.ifBlank { getString(R.string.book_no_author) }
            item.findViewById<TextView>(R.id.author).text = if (book.chapterCount > 0) {
                "$author · ${book.chapterCount} 章"
            } else {
                author
            }
            val progressBar = item.findViewById<ProgressBar>(R.id.progress)
            val percentView = item.findViewById<TextView>(R.id.percent)
            progressBar.progress = (book.progress.coerceIn(0f, 1f) * 1000).toInt()
            percentView.text = if (book.progress > 0f) {
                formatPercent(book.progress)
            } else {
                "未读"
            }
            item.setOnClickListener { openReader(book) }
            item.setOnLongClickListener {
                showBookMenu(book)
                true
            }
            item.findViewById<ImageView>(R.id.more).setOnClickListener { showBookMenu(book) }

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            params.bottomMargin = dp(10)
            bookList.addView(item, params)
        }
    }

    private fun formatPercent(progress: Float): String {
        val value = progress.coerceIn(0f, 1f) * 100f
        return when {
            value <= 0f -> "0%"
            value < 0.1f -> String.format(Locale.US, "%.2f%%", value)
            value < 1f -> String.format(Locale.US, "%.1f%%", value)
            else -> "${value.roundToInt()}%"
        }
    }

    private fun showBookMenu(book: BookRecord) {
        val options = arrayOf(
            getString(R.string.continue_reading),
            getString(R.string.book_info),
            getString(R.string.delete),
        )
        AlertDialog.Builder(this)
            .setTitle(book.title)
            .setItems(options) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> openReader(book)
                    1 -> showBookInfo(book)
                    2 -> confirmDelete(book)
                }
            }
            .show()
    }

    private fun showBookInfo(book: BookRecord) {
        val sizeMb = book.sizeBytes / 1024f / 1024f
        val message = buildString {
            append("作者：").append(book.author.ifBlank { getString(R.string.book_no_author) }).append('\n')
            append("章节：").append(book.chapterCount).append(" 章\n")
            append("大小：").append(String.format("%.1f MB", sizeMb)).append('\n')
            append("进度：").append((book.progress * 100).roundToInt()).append('%').append('\n')
            append("格式：MOBI")
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.book_info)
            .setMessage(message)
            .setPositiveButton(R.string.confirm, null)
            .show()
    }

    private fun confirmDelete(book: BookRecord) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_message, book.title))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                LibraryRepository.remove(book.id)
                render()
            }
            .show()
    }

    companion object {
        private const val REQUEST_OPEN = 42
    }
}

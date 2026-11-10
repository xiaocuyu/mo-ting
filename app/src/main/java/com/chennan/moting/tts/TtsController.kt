package com.chennan.moting.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.chennan.moting.text.TextBlock
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.min

/**
 * 听书核心：把章节按段落切成朗读片段，交给系统 TTS，并把朗读进度（字级区间）回调给界面。
 */
object TtsController {

    enum class State { IDLE, PREPARING, PLAYING, PAUSED }

    interface Listener {
        fun onTtsState(state: State) {}
        fun onTtsProgress(blockIndex: Int, start: Int, end: Int) {}
        fun onTtsContentChanged() {}
        fun onTtsError(message: String?) {}
    }

    private data class Chunk(val blockIndex: Int, val start: Int, val end: Int)

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    private var engine: TextToSpeech? = null
    private var initialized = false
    private var initFailed = false
    private var pendingPlay = false

    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    private var text: String = ""
    private var chunks: List<Chunk> = emptyList()
    private var currentChunk = 0
    private var token = 0
    private var speed = 1.0f
    private var languageTag: String? = null

    var state: State = State.IDLE
        private set

    var bookTitle: String = ""
        private set
    var chapterTitle: String = ""
        private set
    var chapterIndex: Int = -1
        private set

    var chapterFinishedHandler: (() -> Unit)? = null

    private var sleepMinutes = 0
    private var sleepRunnable: Runnable? = null

    val isReady: Boolean get() = initialized

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun ensureReady(context: Context) {
        if (engine != null || initFailed) return
        val appContext = context.applicationContext
        audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        state = State.PREPARING
        notifyState()
        engine = TextToSpeech(appContext) { status ->
            main.post { onInitialized(status) }
        }
        Log.d("MoTingTts", "TextToSpeech created")
    }

    private fun onInitialized(status: Int) {
        Log.d("MoTingTts", "onInitialized status=$status engines=${engine?.engines?.map { it.name }}")
        if (status != TextToSpeech.SUCCESS) {
            initFailed = true
            state = State.IDLE
            listeners.forEach { it.onTtsError("init") }
            notifyState()
            return
        }
        initialized = true
        engine?.apply {
            setSpeechRate(speed)
            setPitch(1f)
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setOnUtteranceProgressListener(progressListener)
        }
        applyLanguage()
        if (pendingPlay) {
            pendingPlay = false
            speakCurrent()
        }
    }

    fun setContent(
        bookTitle: String,
        chapterTitle: String,
        chapterIndex: Int,
        languageTag: String?,
        text: String,
        blocks: List<TextBlock>,
        startBlockIndex: Int = 0,
    ) {
        this.bookTitle = bookTitle
        this.chapterTitle = chapterTitle
        this.chapterIndex = chapterIndex
        this.languageTag = languageTag
        this.text = text
        this.chunks = buildChunks(text, blocks)
        token++
        val startIndex = chunks.indexOfFirst { it.blockIndex >= startBlockIndex }
        currentChunk = if (startIndex < 0) 0 else startIndex
        applyLanguage()
        listeners.forEach { it.onTtsContentChanged() }
        notifyProgressAtCurrent()
    }

    fun play(context: Context? = null) {
        if (context != null) ensureReady(context)
        if (!initialized) {
            pendingPlay = true
            if (initFailed) listeners.forEach { it.onTtsError("init") }
            return
        }
        speakCurrent()
    }

    fun pause() {
        if (state != State.PLAYING) return
        engine?.stop()
        state = State.PAUSED
        abandonFocus()
        notifyState()
    }

    fun toggle(context: Context) {
        if (state == State.PLAYING) pause() else play(context)
    }

    fun stop() {
        engine?.stop()
        token++
        currentChunk = 0
        state = State.IDLE
        abandonFocus()
        cancelSleepTimer()
        notifyState()
    }

    fun next() {
        if (chunks.isEmpty()) return
        if (currentChunk >= chunks.size - 1) {
            completeChapter()
            return
        }
        currentChunk++
        if (state == State.PLAYING) speakCurrent() else notifyProgressAtCurrent()
    }

    fun previous() {
        if (chunks.isEmpty()) return
        currentChunk = (currentChunk - 1).coerceAtLeast(0)
        if (state == State.PLAYING) speakCurrent() else notifyProgressAtCurrent()
    }

    fun setSpeed(value: Float) {
        speed = value.coerceIn(0.5f, 2.5f)
        engine?.setSpeechRate(speed)
        if (state == State.PLAYING) speakCurrent()
    }

    fun setSleepTimer(minutes: Int) {
        cancelSleepTimer()
        sleepMinutes = minutes
        if (minutes > 0) {
            val runnable = Runnable {
                sleepMinutes = 0
                pause()
                listeners.forEach { it.onTtsError("sleep") }
            }
            sleepRunnable = runnable
            main.postDelayed(runnable, minutes * 60_000L)
        }
    }

    fun sleepTimerMinutes(): Int = sleepMinutes

    private fun cancelSleepTimer() {
        sleepRunnable?.let { main.removeCallbacks(it) }
        sleepRunnable = null
        sleepMinutes = 0
    }

    // ---------------------------------------------------------------- 朗读

    private fun speakCurrent() {
        if (!initialized) return
        val engine = engine ?: return
        if (chunks.isEmpty()) {
            completeChapter()
            return
        }
        if (currentChunk !in chunks.indices) {
            completeChapter()
            return
        }
        requestFocus()
        val chunk = chunks[currentChunk]
        val piece = text.substring(chunk.start, chunk.end)
        state = State.PLAYING
        notifyState()
        val result = engine.speak(
            piece,
            TextToSpeech.QUEUE_FLUSH,
            Bundle(),
            "$token:$currentChunk",
        )
        if (result == TextToSpeech.ERROR) {
            state = State.IDLE
            listeners.forEach { it.onTtsError("speak") }
            notifyState()
        }
    }

    private fun completeChapter() {
        state = State.PAUSED
        notifyState()
        val handler = chapterFinishedHandler
        if (handler != null) {
            main.post { handler.invoke() }
        } else {
            state = State.IDLE
            notifyState()
        }
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            main.post {
                val parsed = parseId(utteranceId) ?: return@post
                if (parsed.first != token || parsed.second != currentChunk) return@post
                if (state != State.PLAYING) {
                    state = State.PLAYING
                    notifyState()
                }
                val chunk = chunks.getOrNull(currentChunk) ?: return@post
                listeners.forEach { it.onTtsProgress(chunk.blockIndex, chunk.start, chunk.end) }
            }
        }

        override fun onDone(utteranceId: String?) {
            main.post {
                val parsed = parseId(utteranceId) ?: return@post
                if (parsed.first != token || parsed.second != currentChunk) return@post
                currentChunk++
                if (currentChunk < chunks.size) speakCurrent() else completeChapter()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            main.post {
                val parsed = parseId(utteranceId) ?: return@post
                if (parsed.first != token || parsed.second != currentChunk) return@post
                state = State.IDLE
                abandonFocus()
                listeners.forEach { it.onTtsError("engine") }
                notifyState()
            }
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            main.post {
                val parsed = parseId(utteranceId) ?: return@post
                if (parsed.first != token || parsed.second != currentChunk) return@post
                val chunk = chunks.getOrNull(currentChunk) ?: return@post
                val absoluteStart = chunk.start + start
                val absoluteEnd = chunk.start + end
                listeners.forEach { it.onTtsProgress(chunk.blockIndex, absoluteStart, absoluteEnd) }
            }
        }
    }

    private fun parseId(id: String?): Pair<Int, Int>? {
        if (id == null) return null
        val parts = id.split(':')
        if (parts.size != 2) return null
        val t = parts[0].toIntOrNull() ?: return null
        val c = parts[1].toIntOrNull() ?: return null
        return t to c
    }

    private fun buildChunks(text: String, blocks: List<TextBlock>): List<Chunk> {
        val result = ArrayList<Chunk>()
        for ((index, block) in blocks.withIndex()) {
            var start = block.start.coerceIn(0, text.length)
            var end = block.end.coerceIn(start, text.length)
            while (start < end && text[start].isWhitespace()) start++
            while (end > start && text[end - 1].isWhitespace()) end--
            if (end - start < 2) continue
            if (end - start <= 480) {
                result.add(Chunk(index, start, end))
                continue
            }
            var cursor = start
            while (cursor < end) {
                val limit = min(cursor + 480, end)
                var cut = limit
                if (limit < end) {
                    var i = limit
                    while (i > cursor + 120) {
                        val c = text[i - 1]
                        if (c == '。' || c == '！' || c == '？' || c == '；' || c == '…' ||
                            c == '.' || c == '!' || c == '?' || c == ';' || c == '\n'
                        ) {
                            cut = i
                            break
                        }
                        i--
                    }
                }
                if (cut <= cursor) cut = limit
                result.add(Chunk(index, cursor, cut))
                cursor = cut
            }
        }
        return result
    }

    private fun notifyProgressAtCurrent() {
        val chunk = chunks.getOrNull(currentChunk) ?: return
        listeners.forEach { it.onTtsProgress(chunk.blockIndex, chunk.start, chunk.start) }
    }

    private fun notifyState() {
        listeners.forEach { it.onTtsState(state) }
    }

    // ---------------------------------------------------------------- 语言

    private fun applyLanguage() {
        val engine = engine ?: return
        if (!initialized) return
        val tag = languageTag?.lowercase(Locale.ROOT)
        val locale = when {
            tag?.startsWith("zh") == true -> Locale.SIMPLIFIED_CHINESE
            tag?.startsWith("en") == true -> Locale.US
            containsCjk(text) -> Locale.SIMPLIFIED_CHINESE
            else -> Locale.getDefault()
        }
        val available = engine.isLanguageAvailable(locale)
        Log.d("MoTingTts", "language $locale available=$available")
        if (available >= TextToSpeech.LANG_AVAILABLE) {
            engine.language = locale
        } else {
            engine.language = Locale.getDefault()
            listeners.forEach { it.onTtsError("language") }
        }
    }

    private fun containsCjk(text: String): Boolean {
        var count = 0
        for (c in text) {
            if (c.code in 0x4E00..0x9FFF) {
                count++
                if (count > 20) return true
            }
        }
        return false
    }

    // ---------------------------------------------------------------- 音频焦点

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> main.post { pause() }
        }
    }

    private fun requestFocus() {
        val manager = audioManager ?: return
        abandonFocus()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focusRequest = request
        manager.requestAudioFocus(request)
    }

    private fun abandonFocus() {
        val manager = audioManager ?: return
        focusRequest?.let { manager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }
}

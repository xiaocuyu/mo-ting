package com.chennan.moting.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.chennan.moting.text.TextBlock
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 听书核心：把章节按段落切成朗读片段，交给系统 TTS，并把朗读进度（字级区间）回调给界面。
 */
object TtsController {

    /** 单次朗读窗口上限：合并相邻段落，减少云端 TTS 建流与音频轨重建次数。 */
    private const val MAX_CHUNK_CHARS = 800

    enum class State { IDLE, PREPARING, PLAYING, PAUSED }

    interface Listener {
        fun onTtsState(state: State) {}
        fun onTtsProgress(blockIndex: Int, start: Int, end: Int) {}
        fun onTtsContentChanged() {}
        fun onTtsError(message: String?) {}
    }

    /** 朗读窗口：文本绝对区间 + 覆盖的段落下标。 */
    private data class Window(val start: Int, val end: Int, val blockStart: Int, val blockEnd: Int)

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    private var engine: TextToSpeech? = null
    private var initialized = false
    private var initFailed = false
    private var pendingPlay = false

    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var deviceCallback: AudioDeviceCallback? = null
    private var bluetoothOutput = false

    private var text: String = ""
    private var paragraphs: List<IntRange> = emptyList()
    private var current: Window? = null
    private var readingOffset = 0
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
        registerDeviceCallback()
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
        this.paragraphs = buildParagraphs(text, blocks)
        token++
        current = windowAt(paragraphs.getOrNull(startBlockIndex)?.first ?: 0)
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
        current = null
        state = State.IDLE
        abandonFocus()
        cancelSleepTimer()
        notifyState()
    }

    fun next() {
        if (paragraphs.isEmpty()) return
        val from = paragraphStartAfter(readingOffset)
        if (from == null) {
            completeChapter()
            return
        }
        current = windowAt(from)
        if (state == State.PLAYING) speakCurrent() else notifyProgressAtCurrent()
    }

    fun previous() {
        if (paragraphs.isEmpty()) return
        val from = paragraphStartBefore(readingOffset) ?: paragraphs.first().first
        current = windowAt(from)
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

    private fun speakCurrent(flush: Boolean = true) {
        if (!initialized) return
        val engine = engine ?: return
        val window = current
        if (window == null) {
            completeChapter()
            return
        }
        requestFocus()
        val piece = text.substring(window.start, window.end)
        readingOffset = window.start
        state = State.PLAYING
        notifyState()
        val result = engine.speak(
            piece,
            if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
            Bundle(),
            "$token:${window.start}",
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
                val window = current ?: return@post
                if (parsed.first != token || parsed.second != window.start) return@post
                if (state != State.PLAYING) {
                    state = State.PLAYING
                    notifyState()
                }
                val firstEnd = paragraphs.getOrNull(window.blockStart)?.last?.plus(1) ?: window.end
                listeners.forEach { it.onTtsProgress(window.blockStart, window.start, firstEnd) }
            }
        }

        override fun onDone(utteranceId: String?) {
            main.post {
                val parsed = parseId(utteranceId) ?: return@post
                val window = current ?: return@post
                if (parsed.first != token || parsed.second != window.start) return@post
                val next = windowAt(window.end)
                if (next == null) {
                    completeChapter()
                } else {
                    current = next
                    speakCurrent(flush = false)
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            main.post {
                val parsed = parseId(utteranceId) ?: return@post
                if (parsed.first != token) return@post
                state = State.IDLE
                abandonFocus()
                listeners.forEach { it.onTtsError("engine") }
                notifyState()
            }
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            main.post {
                val parsed = parseId(utteranceId) ?: return@post
                val window = current ?: return@post
                if (parsed.first != token || parsed.second != window.start) return@post
                val absoluteStart = window.start + start
                val absoluteEnd = window.start + end
                readingOffset = absoluteStart
                listeners.forEach { it.onTtsProgress(blockIndexOf(absoluteStart), absoluteStart, absoluteEnd) }
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

    /** 在 [start, end) 内按标点回溯，返回不超过上限的切分点。 */
    private fun cutAt(text: String, start: Int, limit: Int, end: Int): Int {
        if (limit >= end) return end
        var i = limit
        while (i > start + 120) {
            val c = text[i - 1]
            if (c == '。' || c == '！' || c == '？' || c == '；' || c == '…' ||
                c == '.' || c == '!' || c == '?' || c == ';' || c == '\n'
            ) {
                return i
            }
            i--
        }
        return limit
    }

    private fun buildParagraphs(text: String, blocks: List<TextBlock>): List<IntRange> {
        val result = ArrayList<IntRange>(blocks.size)
        for (block in blocks) {
            var start = block.start.coerceIn(0, text.length)
            var end = block.end.coerceIn(start, text.length)
            while (start < end && text[start].isWhitespace()) start++
            while (end > start && text[end - 1].isWhitespace()) end--
            if (end - start >= 2) result.add(start until end)
        }
        return result
    }

    /** 从 offset 起合并相邻段落，取不超过上限的朗读窗口；越过正文末尾则返回 null。 */
    private fun windowAt(offset: Int): Window? {
        val index = paragraphs.indexOfFirst { offset < it.last + 1 }
        if (index < 0) return null
        val first = paragraphs[index]
        val start = maxOf(first.first, offset)
        if (start > first.last) return null
        var end = first.last + 1
        var lastIndex = index
        while (lastIndex + 1 < paragraphs.size) {
            val candidate = paragraphs[lastIndex + 1]
            if (candidate.last + 1 - start > MAX_CHUNK_CHARS) break
            end = candidate.last + 1
            lastIndex++
        }
        if (end - start > MAX_CHUNK_CHARS) {
            end = cutAt(text, start, start + MAX_CHUNK_CHARS, end)
        }
        if (end - start < 2) return null
        return Window(start, end, index, lastIndex)
    }

    private fun paragraphStartAfter(offset: Int): Int? =
        paragraphs.firstOrNull { it.first > offset }?.first

    private fun paragraphStartBefore(offset: Int): Int? =
        paragraphs.lastOrNull { it.first < offset }?.first

    private fun blockIndexOf(offset: Int): Int {
        val index = paragraphs.indexOfFirst { offset >= it.first && offset <= it.last }
        return if (index >= 0) index else paragraphs.indexOfFirst { it.first > offset }.coerceAtLeast(0)
    }

    private fun notifyProgressAtCurrent() {
        val window = current ?: return
        listeners.forEach { it.onTtsProgress(window.blockStart, window.start, window.start) }
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
        if (focusRequest != null) return
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

    // ---------------------------------------------------------------- 输出设备

    /**
     * 监听音频输出设备变化：蓝牙耳机断开后暂停朗读，避免声音突然切到扬声器。
     * 仅在“断开前确实存在蓝牙输出”时触发，纯外放场景不会误暂停。
     */
    private fun registerDeviceCallback() {
        val manager = audioManager ?: return
        if (deviceCallback != null) return
        refreshBluetoothOutput(manager)
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                refreshBluetoothOutput(manager)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                val wasBluetooth = bluetoothOutput
                refreshBluetoothOutput(manager)
                if (wasBluetooth && !bluetoothOutput && state == State.PLAYING) {
                    main.post { pause() }
                }
            }
        }
        manager.registerAudioDeviceCallback(callback, null)
        deviceCallback = callback
    }

    private fun refreshBluetoothOutput(manager: AudioManager) {
        bluetoothOutput = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
            when (device.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                -> true

                AudioDeviceInfo.TYPE_BLE_HEADSET ->
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

                else -> false
            }
        }
    }
}

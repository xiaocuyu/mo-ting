package com.chennan.moting.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.chennan.moting.R

/**
 * 听书前台服务：维持进程与锁屏播放，提供通知栏 / 蓝牙耳机控制。
 */
class TtsService : Service(), TtsController.Listener {

    companion object {
        private const val CHANNEL_ID = "tts_playback"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.chennan.moting.tts.START"
        private const val ACTION_TOGGLE = "com.chennan.moting.tts.TOGGLE"
        private const val ACTION_NEXT = "com.chennan.moting.tts.NEXT"
        private const val ACTION_PREV = "com.chennan.moting.tts.PREV"
        private const val ACTION_STOP = "com.chennan.moting.tts.STOP"

        fun start(context: Context) {
            val intent = Intent(context, TtsService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }
    }

    private var session: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        createSession()
        TtsController.addListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> TtsController.toggle(this)
            ACTION_NEXT -> TtsController.next()
            ACTION_PREV -> TtsController.previous()
            ACTION_STOP -> {
                TtsController.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            else -> ensureForeground()
        }
        return START_NOT_STICKY
    }

    private fun ensureForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        updateSession()
        updateWakeLock(TtsController.state == TtsController.State.PLAYING)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- 通知

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun actionIntent(action: String, code: Int): PendingIntent {
        val intent = Intent(this, TtsService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            code,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildNotification(): Notification {
        val playing = TtsController.state == TtsController.State.PLAYING
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_headphones)
            .setContentTitle(TtsController.bookTitle.ifBlank { getString(R.string.app_name) })
            .setContentText(TtsController.chapterTitle.ifBlank { getString(R.string.listen) })
            .setSubText(getString(R.string.listen))
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .setContentIntent(
                packageManager.getLaunchIntentForPackage(packageName)?.let {
                    PendingIntent.getActivity(
                        this,
                        0,
                        it,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                }
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_prev),
                    getString(R.string.notification_action_prev),
                    actionIntent(ACTION_PREV, 1),
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(
                        this,
                        if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                    ),
                    getString(
                        if (playing) R.string.notification_action_pause
                        else R.string.notification_action_play,
                    ),
                    actionIntent(ACTION_TOGGLE, 2),
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_next),
                    getString(R.string.notification_action_next),
                    actionIntent(ACTION_NEXT, 3),
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_close),
                    getString(R.string.notification_action_stop),
                    actionIntent(ACTION_STOP, 4),
                ).build()
            )

        session?.let {
            builder.style = Notification.MediaStyle()
                .setMediaSession(it.sessionToken)
                .setShowActionsInCompactView(0, 1, 2)
        }
        return builder.build()
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification()) }
    }

    // ---------------------------------------------------------------- MediaSession

    private fun createSession() {
        val mediaSession = MediaSession(this, "MoTing")
        mediaSession.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                TtsController.play(this@TtsService)
            }

            override fun onPause() {
                TtsController.pause()
            }

            override fun onStop() {
                TtsController.stop()
            }

            override fun onSkipToNext() {
                TtsController.next()
            }

            override fun onSkipToPrevious() {
                TtsController.previous()
            }
        })
        mediaSession.isActive = true
        session = mediaSession
        updateSession()
    }

    private fun updateSession() {
        val mediaSession = session ?: return
        val playing = TtsController.state == TtsController.State.PLAYING
        mediaSession.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, TtsController.bookTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, TtsController.chapterTitle)
                .build()
        )
        val actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_STOP
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    0L,
                    1f,
                )
                .build()
        )
    }

    // ---------------------------------------------------------------- 状态回调

    override fun onTtsState(state: TtsController.State) {
        updateSession()
        updateNotification()
        updateWakeLock(state == TtsController.State.PLAYING)
        if (state == TtsController.State.IDLE) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onTtsContentChanged() {
        updateSession()
        updateNotification()
    }

    override fun onTtsError(message: String?) {
        updateNotification()
    }

    // ---------------------------------------------------------------- WakeLock

    private fun updateWakeLock(playing: Boolean) {
        if (playing) {
            if (wakeLock == null) {
                val power = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MoTing:tts").apply {
                    setReferenceCounted(false)
                }
            }
            val lock = wakeLock
            if (lock != null && !lock.isHeld) {
                runCatching { lock.acquire() }
            }
        } else {
            val lock = wakeLock
            if (lock != null && lock.isHeld) {
                runCatching { lock.release() }
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        TtsController.stop()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        TtsController.removeListener(this)
        session?.isActive = false
        session?.release()
        session = null
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
        super.onDestroy()
    }
}

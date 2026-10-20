package com.remote.pccontroller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.VolumeProviderCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.Executors

class PcBackgroundService : Service() {

    companion object {
        const val CHANNEL_ID = "pc_remote_bg_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_RELOAD_CONFIG = "com.remote.pccontroller.ACTION_RELOAD_CONFIG"

        @Volatile
        private var instance: PcBackgroundService? = null

        fun start(context: Context) {
            val intent = Intent(context, PcBackgroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, PcBackgroundService::class.java)
            context.stopService(intent)
        }

        fun reloadConfig(context: Context) {
            val intent = Intent(context, PcBackgroundService::class.java).apply {
                action = ACTION_RELOAD_CONFIG
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun updateCachedVolume(volume: Int) {
            instance?.updateVolumeFromExternal(volume)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val volumeExecutor = Executors.newSingleThreadExecutor()
    private val volumeDebounceHandler = Handler(Looper.getMainLooper())
    @Volatile private var pendingTargetVolume: Int = -1

    private var mediaSession: MediaSessionCompat? = null
    private var volumeProvider: VolumeProviderCompat? = null
    @Volatile private var currentPcVolume: Int = 50

    private val sendVolumeRunnable = Runnable {
        val target = pendingTargetVolume
        if (target != -1) {
            volumeExecutor.execute {
                sendVolumeToPcDirect(target)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        // Setup or release MediaSession based on priv-app preference
        setupOrUpdateMediaSession()

        return START_STICKY
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        releaseMediaSession()
        volumeExecutor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateVolumeFromExternal(volume: Int) {
        val clamped = volume.coerceIn(0, 100)
        currentPcVolume = clamped
        mainHandler.post {
            volumeProvider?.currentVolume = clamped
        }
    }

    private fun setupOrUpdateMediaSession() {
        val prefs = getSharedPreferences("pc_remote_prefs", Context.MODE_PRIVATE)
        val isPrivAppMode = prefs.getBoolean("priv_app_mode", false)

        if (!isPrivAppMode) {
            releaseMediaSession()
            return
        }

        if (mediaSession != null) {
            return
        }

        val provider = object : VolumeProviderCompat(
            VolumeProviderCompat.VOLUME_CONTROL_ABSOLUTE,
            100,
            currentPcVolume
        ) {
            override fun onSetVolumeTo(volume: Int) {
                val clamped = volume.coerceIn(0, 100)
                currentPcVolume = clamped
                currentVolume = clamped
                sendVolumeToPcThrottled(clamped)
            }

            override fun onAdjustVolume(direction: Int) {
                val step = 5
                val clamped = when {
                    direction > 0 -> (currentPcVolume + step).coerceAtMost(100)
                    direction < 0 -> (currentPcVolume - step).coerceAtLeast(0)
                    else -> currentPcVolume
                }
                currentPcVolume = clamped
                currentVolume = clamped
                sendVolumeToPcThrottled(clamped)
            }
        }
        volumeProvider = provider

        // MediaSessionCompat enables PC volume slider in Android's expandable system volume panel
        mediaSession = MediaSessionCompat(this, "PCRemoteVolume").apply {
            setPlaybackToRemote(provider)
            // Crucial: STATE_PAUSED ensures Android's AudioService routes hardware volume buttons
            // to phone media/ring volume when outside the app, so phone volume always takes priority!
            val state = PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_PAUSED, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .setActions(0)
                .build()
            setPlaybackState(state)
            isActive = true
        }

        fetchInitialPcVolume()
    }

    private fun releaseMediaSession() {
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        volumeProvider = null
    }

    private fun sendVolumeToPcThrottled(volume: Int) {
        pendingTargetVolume = volume
        volumeDebounceHandler.removeCallbacks(sendVolumeRunnable)
        volumeDebounceHandler.postDelayed(sendVolumeRunnable, 80)
    }

    private fun sendVolumeToPcDirect(volume: Int) {
        try {
            val prefs = getSharedPreferences("pc_remote_prefs", Context.MODE_PRIVATE)
            val ip = prefs.getString("pc_ip", "192.168.11.110") ?: "192.168.11.110"
            val port = prefs.getString("pc_port", "5050") ?: "5050"

            val jsonPayload = JSONObject().apply {
                put("volume", volume)
            }.toString()
            val requestBody = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("http://$ip:$port/api/volume")
                .post(requestBody)
                .build()

            val response = ClipboardSyncHelper.client.newCall(request).execute()
            response.close()
        } catch (ignored: Exception) {}
    }

    private fun fetchInitialPcVolume() {
        volumeExecutor.execute {
            try {
                val prefs = getSharedPreferences("pc_remote_prefs", Context.MODE_PRIVATE)
                val ip = prefs.getString("pc_ip", "192.168.11.110") ?: "192.168.11.110"
                val port = prefs.getString("pc_port", "5050") ?: "5050"

                val request = Request.Builder()
                    .url("http://$ip:$port/api/volume")
                    .get()
                    .build()

                val response = ClipboardSyncHelper.client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    response.close()
                    val json = JSONObject(body)
                    val vol = json.optInt("volume", 50)
                    currentPcVolume = vol
                    mainHandler.post {
                        volumeProvider?.currentVolume = vol
                    }
                } else {
                    response.close()
                }
            } catch (ignored: Exception) {}
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PC Remote Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps PC Remote active with quick clipboard actions"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val prefs = getSharedPreferences("pc_remote_prefs", Context.MODE_PRIVATE)
        val isPrivApp = prefs.getBoolean("priv_app_mode", false)

        // Tap notification body to open app
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "Send Clip" action: Direct zero-lag broadcast in priv-app mode, or transparent activity in standard mode
        val sendClipboardPendingIntent = if (isPrivApp) {
            val intent = Intent(this, ClipboardActionReceiver::class.java).apply {
                action = ClipboardActionReceiver.ACTION_SEND_CLIP
            }
            PendingIntent.getBroadcast(
                this,
                1,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            val intent = Intent(this, SendClipboardActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            PendingIntent.getActivity(
                this,
                1,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        // "Get PC Clip" action: Direct zero-lag broadcast in priv-app mode, or transparent activity in standard mode
        val getClipboardPendingIntent = if (isPrivApp) {
            val intent = Intent(this, ClipboardActionReceiver::class.java).apply {
                action = ClipboardActionReceiver.ACTION_GET_CLIP
            }
            PendingIntent.getBroadcast(
                this,
                2,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            val intent = Intent(this, GetClipboardActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            PendingIntent.getActivity(
                this,
                2,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val contentSubtitle = if (isPrivApp) {
            "Active in background • Priv-app zero-lag mode"
        } else {
            "Active in background • Quick clipboard actions"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_clipboard)
            .setContentTitle("🎮 PC Remote Control")
            .setContentText(contentSubtitle)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(R.drawable.ic_clipboard, "📋 Send Clip", sendClipboardPendingIntent)
            .addAction(R.drawable.ic_download, "📥 Get PC Clip", getClipboardPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}

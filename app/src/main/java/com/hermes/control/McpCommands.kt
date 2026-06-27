package com.hermes.control

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * System commands using standard Android APIs + Shizuku shell access.
 */
class McpCommands(private val context: Context) {

    // Shizuku shell instance - lazy to avoid crash if Shizuku not installed
    private val shizuku by lazy { ShizukuShell(context).also { it.bind() } }

    fun getBrightness(): Result<Int> = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    }

    fun setBrightness(value: Int): Result<Unit> = runCatching {
        Settings.System.putInt(context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS, value.coerceIn(0, 255))
    }

    fun setAutoBrightness(enabled: Boolean): Result<Unit> = runCatching {
        val mode = if (enabled) 1 else 0
        Settings.System.putInt(context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE, mode)
    }

    fun getVolumes(): Result<String> = runCatching {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val streams = mapOf(
            "music" to android.media.AudioManager.STREAM_MUSIC,
            "ring" to android.media.AudioManager.STREAM_RING,
            "notification" to android.media.AudioManager.STREAM_NOTIFICATION,
            "alarm" to android.media.AudioManager.STREAM_ALARM,
            "call" to android.media.AudioManager.STREAM_VOICE_CALL,
            "system" to android.media.AudioManager.STREAM_SYSTEM,
        )
        streams.entries.joinToString("\n") { (name, stream) ->
            "$name: ${audioManager.getStreamVolume(stream)}/${audioManager.getStreamMaxVolume(stream)}"
        }
    }

    fun setVolume(stream: String, level: Int): Result<Unit> = runCatching {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val streamMap = mapOf(
            "music" to android.media.AudioManager.STREAM_MUSIC,
            "ring" to android.media.AudioManager.STREAM_RING,
            "notification" to android.media.AudioManager.STREAM_NOTIFICATION,
            "alarm" to android.media.AudioManager.STREAM_ALARM,
            "call" to android.media.AudioManager.STREAM_VOICE_CALL,
            "system" to android.media.AudioManager.STREAM_SYSTEM,
        )
        val audioStream = streamMap[stream.lowercase()] ?: android.media.AudioManager.STREAM_MUSIC
        audioManager.setStreamVolume(audioStream, level.coerceIn(0, 15), 0)
    }

    fun setWifi(enabled: Boolean): Result<Unit> = runCatching {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifi.isWifiEnabled = enabled
    }

    fun getWifiStatus(): Result<String> = runCatching {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wifi.connectionInfo
        """WiFi: ${if (wifi.isWifiEnabled) "ON" else "OFF"}
SSID: ${info.ssid ?: "N/A"}
Signal: ${info.rssi} dBm
IP: ${info.ipAddress}"""
    }

    fun getBatteryStatus(): Result<String> = runCatching {
        val intent = context.registerReceiver(null,
            android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra("level", -1) ?: -1
        val scale = intent?.getIntExtra("scale", -1) ?: -1
        val plugged = intent?.getIntExtra("plugged", 0) ?: 0
        val pct = if (level > 0 && scale > 0) (level * 100 / scale) else -1
        val charging = plugged != 0
        """Battery: $pct%
Charging: $charging"""
    }

    fun setTorch(on: Boolean): Result<Unit> = runCatching {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        val cameraId = cameraManager.cameraIdList[0]
        cameraManager.setTorchMode(cameraId, on)
    }

    fun sendNotification(title: String, content: String): Result<Unit> = runCatching {
        val channelId = "hermes_control"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId, "Hermes Control",
                android.app.NotificationManager.IMPORTANCE_HIGH)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.createNotificationChannel(channel)
        }
        val notification = android.app.Notification.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setAutoCancel(true)
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(System.currentTimeMillis().toInt(), notification)
    }

    fun launchApp(packageName: String): Result<Unit> = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                context.startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    // ==========================================
    // Shizuku Shell Commands
    // ==========================================

    /**
     * Execute a shell command via Shizuku (ADB-level access).
     */
    fun shellExec(command: String, timeout: Long = 30): Result<String> = runCatching {
        shizuku.exec(command, timeout.toInt())
    }

    /**
     * Check Shizuku status.
     */
    fun shizukuStatus(): Result<String> = runCatching {
        val available = shizuku.isShizukuAvailable()
        val permission = if (available) shizuku.hasPermission() else false
        buildString {
            appendLine("Shizuku: ${if (available) "RUNNING" else "NOT RUNNING"}")
            appendLine("Permission: ${if (permission) "GRANTED" else "NOT GRANTED"}")
        }
    }

    /**
     * Request Shizuku permission.
     */
    fun shizukuRequestPermission(): Result<String> = runCatching {
        ShizukuShell.requestPermission()
        "Permission request sent"
    }
}

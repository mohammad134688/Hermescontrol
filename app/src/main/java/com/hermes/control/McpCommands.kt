package com.hermes.control

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import dev.rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Shizuku-powered system commands that normal Android apps can't do.
 * Falls back to standard API when Shizuku is unavailable.
 */
class McpCommands(private val context: Context, private val hasShizuku: Boolean = false) {

    // ─── Brightness ─────────────────────────────────────────────

    fun getBrightness(): Result<Int> = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    }

    fun setBrightness(value: Int): Result<Unit> = runCatching {
        when {
            hasShizuku -> shizukuRun("settings put system screen_brightness $value")
            else -> Settings.System.putInt(context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS, value.coerceIn(0, 255))
        }
    }

    fun setAutoBrightness(enabled: Boolean): Result<Unit> = runCatching {
        val mode = if (enabled) 1 else 0
        when {
            hasShizuku -> shizukuRun("settings put system screen_brightness_mode $mode")
            else -> Settings.System.putInt(context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE, mode)
        }
    }

    // ─── Volume ─────────────────────────────────────────────────

    fun getVolumes(): Result<String> = runCatching {
        "Use termux-volume from MCP Python server — more reliable"
    }

    fun setVolume(stream: String, level: Int): Result<Unit> = runCatching {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE)
                as android.media.AudioManager
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

    // ─── WiFi ───────────────────────────────────────────────────

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

    // ─── Battery ────────────────────────────────────────────────

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

    // ─── Torch ──────────────────────────────────────────────────

    fun setTorch(on: Boolean): Result<Unit> = runCatching {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE)
                as android.hardware.camera2.CameraManager
        val cameraId = cameraManager.cameraIdList[0]
        cameraManager.setTorchMode(cameraId, on)
    }

    // ─── Notification ───────────────────────────────────────────

    fun sendNotification(title: String, content: String): Result<Unit> = runCatching {
        val channelId = "hermes_control"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId, "Hermes Control",
                android.app.NotificationManager.IMPORTANCE_HIGH)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                    as android.app.NotificationManager
            nm.createNotificationChannel(channel)
        }
        val notification = android.app.Notification.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setAutoCancel(true)
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
        nm.notify(System.currentTimeMillis().toInt(), notification)
    }

    // ─── App Launch ─────────────────────────────────────────────

    fun launchApp(packageName: String): Result<Unit> = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            // Try opening Play Store
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

    // ─── Reboot (Shizuku only) ──────────────────────────────────

    fun reboot(): Result<Unit> = runCatching {
        if (!hasShizuku) throw SecurityException("Reboot requires Shizuku")
        shizukuRun("reboot")
    }

    // ─── Shizuku Shell ──────────────────────────────────────────

    fun shizukuShell(command: String): Result<String> = runCatching {
        if (!hasShizuku) throw SecurityException("Shizuku not available")
        shizukuRun(command)
    }

    private fun shizukuRun(command: String): String {
        val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val errorReader = BufferedReader(InputStreamReader(process.errorStream))
        val output = reader.readText().trim()
        val error = errorReader.readText().trim()
        process.waitFor()
        if (output.isNotEmpty()) return output
        if (error.isNotEmpty()) throw RuntimeException(error)
        return "OK"
    }
}

package com.hermes.control

import android.content.ComponentName
import android.content.Context

/**
 * Execute shell commands via Shizuku using AIDL User Service.
 * Uses reflection so the class loads safely even without Shizuku installed.
 */
class ShizukuShell(private val context: Context) {

    private var shellService: IShellService? = null
    private var bound = false
    private var shizukuAvailable = false

    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: android.os.IBinder?) {
            shellService = IShellService.Stub.asInterface(binder)
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellService = null
            bound = false
        }
    }

    /**
     * Check if Shizuku is installed and running (via reflection).
     */
    fun isShizukuAvailable(): Boolean {
        return try {
            val cls = Class.forName("rikka.shizuku.Shizuku")
            val method = cls.getMethod("pingBinder")
            shizukuAvailable = method.invoke(null) as? Boolean ?: false
            shizukuAvailable
        } catch (e: Exception) {
            shizukuAvailable = false
            false
        }
    }

    /**
     * Check if we have Shizuku permission (via reflection).
     */
    fun hasPermission(): Boolean {
        return try {
            val cls = Class.forName("rikka.shizuku.Shizuku")
            val method = cls.getMethod("checkSelfPermission")
            (method.invoke(null) as? Int) == 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Bind to the shell service. Must be called before exec().
     */
    fun bind() {
        if (bound) return
        try {
            val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
            val argsClass = Class.forName("rikka.shizuku.Shizuku\$UserServiceArgs")

            val constructor = argsClass.getConstructor(ComponentName::class.java)
            val component = ComponentName(context.packageName, ShellService::class.java.name)
            val args = constructor.newInstance(component)

            // Chain: .daemon(false).processNameSuffix("shell").debuggable(true).version(1)
            val daemonMethod = argsClass.getMethod("daemon", Boolean::class.javaPrimitiveType)
            daemonMethod.invoke(args, false)
            val suffixMethod = argsClass.getMethod("processNameSuffix", String::class.java)
            suffixMethod.invoke(args, "shell")
            val debugMethod = argsClass.getMethod("debuggable", Boolean::class.javaPrimitiveType)
            debugMethod.invoke(args, true)
            val versionMethod = argsClass.getMethod("version", Int::class.javaPrimitiveType)
            versionMethod.invoke(args, 1)

            // Shizuku.bindUserService(args, serviceConnection)
            val bindMethod = shizukuClass.getMethod("bindUserService", argsClass, android.content.ServiceConnection::class.java)
            bindMethod.invoke(null, args, serviceConnection)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Execute a shell command via the Shizuku user service.
     */
    fun exec(command: String, timeoutSeconds: Int = 30): String {
        if (!isShizukuAvailable()) return "Error: Shizuku is not running"
        if (!hasPermission()) return "Error: Shizuku permission not granted"

        val service = shellService
        if (service == null) {
            bind()
            return "Error: Shell service not connected yet. Retrying..."
        }

        return try {
            service.exec(command, timeoutSeconds)
        } catch (e: Exception) {
            bound = false
            shellService = null
            "Error: ${e.message}"
        }
    }

    /**
     * Check Shizuku + service status.
     */
    fun status(): String {
        val available = isShizukuAvailable()
        val permission = if (available) hasPermission() else false
        return buildString {
            appendLine("Shizuku: ${if (available) "RUNNING" else "NOT RUNNING"}")
            appendLine("Permission: ${if (permission) "GRANTED" else "NOT GRANTED"}")
            appendLine("Service: ${if (bound) "CONNECTED" else "NOT CONNECTED"}")
        }
    }

    companion object {
        fun requestPermission() {
            try {
                val cls = Class.forName("rikka.shizuku.Shizuku")
                val method = cls.getMethod("requestPermission", Int::class.javaPrimitiveType)
                method.invoke(null, 1001)
            } catch (_: Exception) {}
        }
    }
}

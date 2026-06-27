package com.hermes.control

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import rikka.shizuku.Shizuku

/**
 * Execute shell commands via Shizuku using AIDL User Service.
 * The ShellService runs in Shizuku's process with ADB-level privileges.
 */
class ShizukuShell(private val context: Context) {

    private var shellService: IShellService? = null
    private var bound = false
    private var pendingCallback: ((String) -> Unit)? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            shellService = IShellService.Stub.asInterface(binder)
            bound = true
            // Execute any pending command
            pendingCallback?.let { cb ->
                pendingCallback = null
                cb("connected")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellService = null
            bound = false
        }
    }

    /**
     * Check if Shizuku is installed and running.
     */
    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check if we have Shizuku permission.
     */
    fun hasPermission(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Bind to the shell service. Must be called before exec().
     * Returns immediately; service connects async.
     */
    fun bind() {
        if (bound) return
        try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(context.packageName, ShellService::class.java.name)
            )
                .daemon(false)
                .processNameSuffix("shell")
                .debuggable(true)
                .version(1)

            Shizuku.bindUserService(args, serviceConnection)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Unbind from the shell service.
     */
    fun unbind() {
        try {
            if (bound) {
                Shizuku.unbindUserService(Shizuku.UserServiceArgs(
                    ComponentName(context.packageName, ShellService::class.java.name)
                ), serviceConnection, true)
            }
        } catch (_: Exception) {}
        bound = false
        shellService = null
    }

    /**
     * Execute a shell command via the Shizuku user service.
     * Returns the command output as a string.
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
            // Service might have died, unbind and retry
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
        /**
         * Request Shizuku permission. Call from Activity.
         */
        fun requestPermission() {
            try {
                Shizuku.requestPermission(1001)
            } catch (_: Exception) {}
        }
    }
}

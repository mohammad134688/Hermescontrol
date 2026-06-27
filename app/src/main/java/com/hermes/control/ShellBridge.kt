package com.hermes.control

import android.content.Context
import android.os.Environment
import android.os.FileObserver
import java.io.File
import java.util.concurrent.Executors

/**
 * File-based shell command bridge.
 * Uses /sdcard/Download/ for IPC between PRoot and app.
 * App must have WRITE_EXTERNAL_STORAGE or MANAGE_EXTERNAL_STORAGE permission.
 */
class ShellBridge(private val context: Context) {

    private val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    private val cmdFile = File(baseDir, "hermes_cmd.txt")
    private val outFile = File(baseDir, "hermes_out.txt")
    private val lockFile = File(baseDir, "hermes_lock.txt")

    private var running = false
    private var pollThread: Thread? = null
    private var lastCmdTime = 0L

    fun start() {
        if (running) return
        running = true

        ensureDir()
        android.util.Log.d("ShellBridge", "Started. Files at: ${baseDir.absolutePath}")

        // Poll for commands
        pollThread = Thread {
            while (running) {
                try {
                    processCommand()
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    android.util.Log.e("ShellBridge", "Error", e)
                }
            }
        }.apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        pollThread?.interrupt()
        pollThread = null
    }

    private fun ensureDir() {
        if (!baseDir.exists()) baseDir.mkdirs()
    }

    private fun processCommand() {
        try {
            if (!cmdFile.exists()) return

            val cmd = cmdFile.readText().trim()
            if (cmd.isEmpty()) return

            // Clear command
            cmdFile.writeText("")

            android.util.Log.d("ShellBridge", "Executing: $cmd")

            // Execute via Shizuku
            val shell = ShizukuShell(context)
            val output = shell.exec(cmd, 30)

            android.util.Log.d("ShellBridge", "Output length: ${output.length}")

            // Write output
            outFile.writeText(output)
            lockFile.writeText("done")

        } catch (e: Exception) {
            android.util.Log.e("ShellBridge", "Process error", e)
            try {
                outFile.writeText("Error: ${e.message}")
                lockFile.writeText("error")
            } catch (_: Exception) {}
        }
    }
}

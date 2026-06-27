package com.hermes.control

import android.content.Context
import android.os.Environment
import android.os.FileObserver
import java.io.File
import java.util.concurrent.Executors

/**
 * File-based shell command bridge.
 * Uses app's external files dir (no permission needed) for IPC.
 * PRoot writes command → app executes via Shizuku → app writes output.
 * 
 * Files location: /sdcard/Android/data/com.hermes.control/files/
 * PRoot can access via: /sdcard/Android/data/com.hermes.control/files/
 */
class ShellBridge(private val context: Context) {

    private val baseDir: File by lazy {
        // App-specific external dir - no permission needed, accessible from PRoot
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    private val cmdFile by lazy { File(baseDir, "hermes_cmd.txt") }
    private val outFile by lazy { File(baseDir, "hermes_out.txt") }
    private val lockFile by lazy { File(baseDir, "hermes_lock.txt") }

    private val executor = Executors.newSingleThreadExecutor()
    private var observer: FileObserver? = null
    private var running = false
    private var pollThread: Thread? = null

    fun start() {
        if (running) return
        running = true

        // Create all files
        ensureFile(cmdFile)
        ensureFile(outFile)
        ensureFile(lockFile)

        // Log the path for debugging
        android.util.Log.d("ShellBridge", "Bridge files at: ${baseDir.absolutePath}")

        // Use polling instead of FileObserver (more reliable on FUSE)
        pollThread = Thread {
            while (running) {
                try {
                    processCommand()
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    android.util.Log.e("ShellBridge", "Poll error", e)
                }
            }
        }.apply { start() }
    }

    fun stop() {
        running = false
        observer?.stopWatching()
        observer = null
        pollThread?.interrupt()
        pollThread = null
        executor.shutdown()
    }

    private fun ensureFile(file: File) {
        try {
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.createNewFile()
            }
        } catch (e: Exception) {
            android.util.Log.e("ShellBridge", "Failed to create ${file.name}", e)
        }
    }

    private fun processCommand() {
        try {
            if (!cmdFile.exists()) return

            val cmd = cmdFile.readText().trim()
            if (cmd.isEmpty()) return

            // Clear command file
            cmdFile.writeText("")

            // Execute via Shizuku
            val shell = ShizukuShell(context)
            val output = shell.exec(cmd, 30)

            // Write output
            outFile.writeText(output)

            // Write lock to signal completion
            lockFile.writeText("done")

        } catch (e: Exception) {
            try {
                outFile.writeText("Error: ${e.message}")
                lockFile.writeText("error")
            } catch (_: Exception) {}
        }
    }
}

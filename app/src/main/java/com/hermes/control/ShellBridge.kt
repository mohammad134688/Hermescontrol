package com.hermes.control

import android.content.Context
import android.os.FileObserver
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * File-based shell command bridge.
 * Watches /sdcard/Download/hermes_cmd.txt for commands,
 * executes them via Shizuku, writes output to hermes_out.txt.
 * This allows PRoot to trigger ADB-level commands through shared storage.
 */
class ShellBridge(private val context: Context) {

    private val cmdFile = File("/sdcard/Download/hermes_cmd.txt")
    private val outFile = File("/sdcard/Download/hermes_out.txt")
    private val lockFile = File("/sdcard/Download/hermes_lock.txt")
    private val executor = Executors.newSingleThreadExecutor()
    private var observer: FileObserver? = null
    private var running = false

    fun start() {
        if (running) return
        running = true

        // Ensure files exist
        try { cmdFile.createNewFile() } catch (_: Exception) {}
        try { outFile.createNewFile() } catch (_: Exception) {}

        // Watch for changes to the command file
        observer = object : FileObserver(cmdFile.absolutePath, CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                executor.submit { processCommand() }
            }
        }
        observer?.startWatching()

        // Also check immediately if there's a pending command
        executor.submit { processCommand() }
    }

    fun stop() {
        running = false
        observer?.stopWatching()
        observer = null
        executor.shutdown()
    }

    private fun processCommand() {
        try {
            if (!cmdFile.exists()) return

            val cmd = cmdFile.readText().trim()
            if (cmd.isEmpty()) return

            // Write lock to signal we're processing
            lockFile.writeText("processing")

            // Clear the command file
            cmdFile.writeText("")

            // Execute via Shizuku
            val shell = ShizukuShell(context)
            val output = shell.exec(cmd, 30)

            // Write output
            outFile.writeText(output)

            // Release lock
            lockFile.writeText("done")

        } catch (e: Exception) {
            try {
                outFile.writeText("Error: ${e.message}")
                lockFile.writeText("error")
            } catch (_: Exception) {}
        }
    }
}

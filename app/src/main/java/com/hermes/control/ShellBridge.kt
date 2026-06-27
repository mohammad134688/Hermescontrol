package com.hermes.control

import android.content.Context
import android.os.FileObserver
import java.io.File
import java.util.concurrent.Executors

/**
 * File-based shell command bridge.
 * PRoot writes command to hermes_cmd.txt → app executes via Shizuku → app writes to hermes_out.txt.
 * App creates output/lock files with its own UID so it can write to them.
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

        // Don't create cmd file - PRoot creates it
        // DO create out/lock files so we own them
        ensureFile(outFile)
        ensureFile(lockFile)

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

    private fun ensureFile(file: File) {
        try {
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.createNewFile()
            }
            file.setReadable(true, false)
            file.setWritable(true, false)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun processCommand() {
        try {
            if (!cmdFile.exists()) return

            val cmd = cmdFile.readText().trim()
            if (cmd.isEmpty()) return

            // Clear command file
            try { cmdFile.writeText("") } catch (_: Exception) {}

            // Execute via Shizuku
            val shell = ShizukuShell(context)
            val output = shell.exec(cmd, 30)

            // Write output (we created this file, so we can write)
            ensureFile(outFile)
            outFile.writeText(output)

            // Write lock
            ensureFile(lockFile)
            lockFile.writeText("done")

        } catch (e: Exception) {
            try {
                ensureFile(outFile)
                outFile.writeText("Error: ${e.message}")
                ensureFile(lockFile)
                lockFile.writeText("error")
            } catch (_: Exception) {}
        }
    }
}

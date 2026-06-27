package com.hermes.control

import android.content.Context
import android.os.Environment
import java.io.File
import java.util.concurrent.Executors

/**
 * File-based shell command bridge.
 * 
 * cmd.txt → created by PRoot (app reads it)
 * out.txt, lock.txt → created by APP (app writes to them)
 * 
 * On Android FUSE: root-created files are readable by apps,
 * but only the creating app can write to its own files.
 */
class ShellBridge(private val context: Context) {

    private val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    private val cmdFile = File(baseDir, "hermes_cmd.txt")
    private val outFile = File(baseDir, "hermes_out.txt")
    private val lockFile = File(baseDir, "hermes_lock.txt")

    private var running = false
    private var pollThread: Thread? = null

    fun start() {
        if (running) return
        running = true

        // Only create out and lock files (we own these)
        ensureFile(outFile)
        ensureFile(lockFile)

        android.util.Log.d("ShellBridge", "Started. Watching: ${cmdFile.absolutePath}")

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

    private fun ensureFile(file: File) {
        try {
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.createNewFile()
            }
            file.setReadable(true, false)
            file.setWritable(true, false)
        } catch (e: Exception) {
            android.util.Log.e("ShellBridge", "Failed: ${file.name}: ${e.message}")
        }
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

            android.util.Log.d("ShellBridge", "Result: ${output.take(100)}")

            // Write output (we created these files, so we can write)
            ensureFile(outFile)
            ensureFile(lockFile)
            outFile.writeText(output)
            lockFile.writeText("done")

        } catch (e: Exception) {
            android.util.Log.e("ShellBridge", "Error: ${e.message}")
            try {
                ensureFile(outFile)
                ensureFile(lockFile)
                outFile.writeText("Error: ${e.message}")
                lockFile.writeText("error")
            } catch (_: Exception) {}
        }
    }
}

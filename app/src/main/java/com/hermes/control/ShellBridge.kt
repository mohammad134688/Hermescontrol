package com.hermes.control

import android.content.Context
import android.os.Environment
import android.os.FileObserver
import java.io.File
import java.util.concurrent.Executors

/**
 * File-based shell command bridge.
 * App creates files → PRoot writes command → app executes → writes output.
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

        // Create ALL files so we own them and PRoot can write to them
        createFile(cmdFile, "")
        createFile(outFile, "")
        createFile(lockFile, "ready")

        android.util.Log.d("ShellBridge", "Started at ${baseDir.absolutePath}")

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

    private fun createFile(file: File, initialContent: String) {
        try {
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.createNewFile()
            }
            file.setReadable(true, false)
            file.setWritable(true, false)
            if (initialContent.isNotEmpty()) {
                file.writeText(initialContent)
            }
        } catch (e: Exception) {
            android.util.Log.e("ShellBridge", "Failed to create ${file.name}: ${e.message}")
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

            android.util.Log.d("ShellBridge", "Done: ${output.take(50)}")

            // Write output
            outFile.writeText(output)
            lockFile.writeText("done")

        } catch (e: Exception) {
            android.util.Log.e("ShellBridge", "Process error: ${e.message}")
            try {
                outFile.writeText("Error: ${e.message}")
                lockFile.writeText("error")
            } catch (_: Exception) {}
        }
    }
}

package com.hermes.control

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Execute shell commands via Shizuku (ADB-level access).
 * Requires Shizuku to be running and permission granted.
 */
class ShizukuShell {

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
     * Execute a shell command via Shizuku (runs as ADB shell user).
     * Returns a Pair of (stdout+stderr, exitCode).
     */
    fun exec(command: String, timeoutSeconds: Long = 30): Pair<String, Int> {
        return try {
            if (!isShizukuAvailable()) {
                return Pair("Error: Shizuku is not running", -1)
            }
            if (!hasPermission()) {
                return Pair("Error: Shizuku permission not granted", -2)
            }

            // Use Shizuku's newProcess to run the command
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)

            // Read stdout
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                val sb = StringBuilder()
                val buffer = CharArray(4096)
                var read: Int
                while (reader.read(buffer).also { read = it } != -1) {
                    sb.append(buffer, 0, read)
                    // Safety limit: 100KB max output
                    if (sb.length > 102400) {
                        sb.append("\n... (output truncated at 100KB)")
                        break
                    }
                }
                sb.toString()
            }

            // Read stderr
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                val sb = StringBuilder()
                val buffer = CharArray(4096)
                var read: Int
                while (reader.read(buffer).also { read = it } != -1) {
                    sb.append(buffer, 0, read)
                    if (sb.length > 10240) {
                        sb.append("\n... (stderr truncated)")
                        break
                    }
                }
                sb.toString()
            }

            // Wait for process to finish
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            val exitCode = if (finished) process.exitValue() else {
                process.destroyForcibly()
                -99 // timeout
            }

            val output = buildString {
                if (stdout.isNotBlank()) append(stdout)
                if (stderr.isNotBlank()) {
                    if (isNotEmpty()) append("\n")
                    append("[stderr] ").append(stderr)
                }
            }.ifBlank { "(no output)" }

            Pair(output, exitCode)

        } catch (e: Exception) {
            Pair("Error: ${e.message ?: e.javaClass.simpleName}", -3)
        }
    }

    /**
     * Execute a command and return formatted output.
     */
    fun execFormatted(command: String, timeoutSeconds: Long = 30): String {
        val (output, exitCode) = exec(command, timeoutSeconds)
        return buildString {
            append(output)
            if (exitCode != 0) {
                append("\n[exit code: $exitCode]")
            }
        }
    }

    companion object {
        private const val REQUEST_CODE = 1001

        /**
         * Request Shizuku permission. Call this from Activity.
         */
        fun requestPermission() {
            try {
                Shizuku.requestPermission(REQUEST_CODE)
            } catch (e: Exception) {
                // Shizuku not available
            }
        }
    }
}

package com.hermes.control

import android.content.ComponentName
import android.content.Context
import android.os.IBinder
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Execute shell commands via Shizuku.
 * Uses reflection on Shizuku.newProcess() (private in v13).
 */
class ShizukuShell(private val context: Context) {

    fun isShizukuAvailable(): Boolean {
        return try {
            val cls = Class.forName("rikka.shizuku.Shizuku")
            val method = cls.getMethod("pingBinder")
            method.invoke(null) as? Boolean ?: false
        } catch (e: Exception) {
            false
        }
    }

    fun hasPermission(): Boolean {
        return try {
            val cls = Class.forName("rikka.shizuku.Shizuku")
            val method = cls.getMethod("checkSelfPermission")
            (method.invoke(null) as? Int) == 0
        } catch (e: Exception) {
            false
        }
    }

    fun exec(command: String, timeoutSeconds: Int = 30): String {
        if (!isShizukuAvailable()) return "Error: Shizuku is not running"
        if (!hasPermission()) return "Error: Shizuku permission not granted"

        return try {
            // Try Shizuku.newProcess() via reflection (private in v13)
            val shizukuClass = Class.forName("rikka.shizuku.Shizuku")

            // Try to find the method
            val newProcessMethod = try {
                shizukuClass.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
            } catch (e: NoSuchMethodException) {
                // Try alternative signature
                shizukuClass.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java,
                    Boolean::class.javaPrimitiveType
                )
            }

            newProcessMethod.isAccessible = true

            val process = newProcessMethod.invoke(
                null,
                arrayOf("sh", "-c", command),
                null,
                null
            )

            if (process == null) {
                return "Error: newProcess returned null"
            }

            // Read from the process - it should be a ShizukuRemoteProcess
            // which extends Process
            val processClass = process.javaClass

            // Get inputStream via reflection
            val inputStreamMethod = processClass.getMethod("getInputStream")
            val inputStream = inputStreamMethod.invoke(process) as? java.io.InputStream
                ?: return "Error: Could not get input stream"

            val stdout = BufferedReader(InputStreamReader(inputStream)).use { reader ->
                val sb = StringBuilder()
                val buffer = CharArray(4096)
                var read: Int
                while (reader.read(buffer).also { read = it } != -1) {
                    sb.append(buffer, 0, read)
                    if (sb.length > 102400) {
                        sb.append("\n... (truncated)")
                        break
                    }
                }
                sb.toString()
            }

            // Get errorStream
            val errorStreamMethod = processClass.getMethod("getErrorStream")
            val errorStream = errorStreamMethod.invoke(process) as? java.io.InputStream
            val stderr = if (errorStream != null) {
                BufferedReader(InputStreamReader(errorStream)).use { reader ->
                    val sb = StringBuilder()
                    val buffer = CharArray(4096)
                    var read: Int
                    while (reader.read(buffer).also { read = it } != -1) {
                        sb.append(buffer, 0, read)
                        if (sb.length > 10240) break
                    }
                    sb.toString()
                }
            } else ""

            // Wait for process
            val waitForMethod = processClass.getMethod(
                "waitFor", Long::class.javaPrimitiveType, TimeUnit::class.java
            )
            val finished = waitForMethod.invoke(process, timeoutSeconds.toLong(), TimeUnit.SECONDS) as? Boolean ?: false

            if (!finished) {
                try {
                    val destroyMethod = processClass.getMethod("destroy")
                    destroyMethod.invoke(process)
                } catch (_: Exception) {}
            }

            val exitCode = if (finished) {
                val exitMethod = processClass.getMethod("exitValue")
                exitMethod.invoke(process) as? Int ?: -1
            } else -99

            buildString {
                if (stdout.isNotBlank()) append(stdout)
                if (stderr.isNotBlank()) {
                    if (isNotEmpty()) append("\n")
                    append("[stderr] ").append(stderr)
                }
                if (exitCode != 0) append("\n[exit:$exitCode]")
            }

        } catch (e: Exception) {
            "Error: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    fun status(): String {
        val available = isShizukuAvailable()
        val permission = if (available) hasPermission() else false
        return buildString {
            appendLine("Shizuku: ${if (available) "RUNNING" else "NOT RUNNING"}")
            appendLine("Permission: ${if (permission) "GRANTED" else "NOT GRANTED"}")
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

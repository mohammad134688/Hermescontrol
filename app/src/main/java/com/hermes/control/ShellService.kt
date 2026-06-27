package com.hermes.control

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Shell service that runs inside Shizuku's process (ADB privileges).
 * Connected via AIDL binder.
 */
class ShellService : IShellService.Stub() {

    override fun exec(command: String, timeoutSeconds: Int): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))

            val stdout = BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                val sb = StringBuilder()
                val buffer = CharArray(4096)
                var read: Int
                while (reader.read(buffer).also { read = it } != -1) {
                    sb.append(buffer, 0, read)
                    if (sb.length > 102400) {
                        sb.append("\n... (output truncated at 100KB)")
                        break
                    }
                }
                sb.toString()
            }

            val stderr = BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                val sb = StringBuilder()
                val buffer = CharArray(4096)
                var read: Int
                while (reader.read(buffer).also { read = it } != -1) {
                    sb.append(buffer, 0, read)
                    if (sb.length > 10240) break
                }
                sb.toString()
            }

            val finished = process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            val exitCode = if (finished) process.exitValue() else {
                process.destroyForcibly()
                -99
            }

            buildString {
                if (stdout.isNotBlank()) append(stdout)
                if (stderr.isNotBlank()) {
                    if (isNotEmpty()) append("\n")
                    append("[stderr] ").append(stderr)
                }
                append("\n[exit:$exitCode]")
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}

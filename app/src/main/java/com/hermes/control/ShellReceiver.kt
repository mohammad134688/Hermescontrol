package com.hermes.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Environment
import java.io.File
import java.util.concurrent.Executors

/**
 * BroadcastReceiver that executes shell commands via Shizuku.
 * Triggered by: am broadcast --user 0 -a com.hermes.control.EXEC --es cmd "command"
 * Result written to /sdcard/Download/hermes_out.txt
 */
class ShellReceiver : BroadcastReceiver() {

    private val executor = Executors.newSingleThreadExecutor()

    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: intent.getStringExtra("command") ?: ""
        if (cmd.isBlank()) return

        // Go async so we don't block the main thread
        val pendingResult = goAsync()

        executor.submit {
            try {
                val shell = ShizukuShell(context)
                val output = shell.exec(cmd, 30)

                // Write output to file
                val outFile = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "hermes_out.txt"
                )
                outFile.writeText(output)

                // Write lock to signal completion
                val lockFile = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "hermes_lock.txt"
                )
                lockFile.writeText("done")

            } catch (e: Exception) {
                try {
                    val outFile = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "hermes_out.txt"
                    )
                    outFile.writeText("Error: ${e.message}")
                    val lockFile = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "hermes_lock.txt"
                    )
                    lockFile.writeText("error")
                } catch (_: Exception) {}
            } finally {
                pendingResult.finish()
            }
        }
    }
}

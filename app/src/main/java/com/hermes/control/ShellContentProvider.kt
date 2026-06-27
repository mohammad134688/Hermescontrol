package com.hermes.control

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle

/**
 * ContentProvider that accepts shell commands via `content call`.
 * Usage from PRoot:
 *   content call --uri content://com.hermes.control.shell/exec --method shell --arg "top -n 1"
 */
class ShellContentProvider : ContentProvider() {

    companion object {
        private const val AUTHORITY = "com.hermes.control.shell"
        private const val EXEC = 1
        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "exec", EXEC)
        }
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val result = Bundle()
        if (method == "exec" || method == "shell") {
            val command = arg ?: extras?.getString("cmd") ?: extras?.getString("command") ?: ""
            if (command.isNotBlank()) {
                try {
                    val shell = ShizukuShell(context!!)
                    val output = shell.exec(command, 30)
                    result.putString("output", output)
                    result.putInt("exit", 0)
                } catch (e: Exception) {
                    result.putString("output", "Error: ${e.message}")
                    result.putInt("exit", -1)
                }
            } else {
                result.putString("output", "Error: no command")
                result.putInt("exit", -1)
            }
        }
        return result
    }

    override fun query(uri: Uri, proj: Array<String>?, sel: String?, selArgs: Array<String>?, sort: String?): Cursor? {
        if (uriMatcher.match(uri) == EXEC) {
            val cursor = MatrixCursor(arrayOf("status"))
            cursor.addRow(arrayOf("Hermes Shell Provider ready"))
            return cursor
        }
        return null
    }

    override fun getType(uri: Uri): String? = "text/plain"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
}

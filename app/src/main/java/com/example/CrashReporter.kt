package com.example

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The IME service runs in the same process as the launcher activity, so any uncaught
 * throw is a full app crash. On a device we cannot attach a debugger easily, so the
 * crash is written to disk and shown in-app on the next launch instead of being lost.
 */
object CrashReporter {

    private const val FILE_NAME = "last_crash.txt"
    private const val MAX_CHARS = 6000

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread.name, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(context: Context, threadName: String, error: Throwable) {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val text = buildString {
            append(stamp).append('\n')
            append("thread: ").append(threadName).append('\n')
            append(sw.toString().take(MAX_CHARS))
        }
        File(context.filesDir, FILE_NAME).writeText(text)
    }

    fun lastCrash(context: Context): String? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        return runCatching { file.readText() }.getOrNull()
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }
}
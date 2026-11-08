package com.universal.app

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UniversalApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugLogger.init(applicationContext)

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                throwable.printStackTrace(pw)
                val stackTrace = sw.toString()

                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val dumpText = "FATAL CRASH on Thread '${thread.name}' at $timestamp:\n${throwable.javaClass.name}: ${throwable.message}\n$stackTrace\n"

                val crashFile = File(filesDir, "crash_dump.txt")
                crashFile.writeText(dumpText)
                
                val traceFile = File(filesDir, "system_trace.txt")
                traceFile.appendText("[$timestamp] FATAL_CRASH: ${throwable.javaClass.simpleName} - ${throwable.message}\n")
            } catch (e: Exception) {
                // Fail-safe: do not prevent process death forwarding
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}
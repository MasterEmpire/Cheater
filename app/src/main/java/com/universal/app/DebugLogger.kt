package com.universal.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object DebugLogger {
    val logs = mutableStateListOf<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var logFile: File? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun init(context: Context) {
        if (logFile != null) return
        logFile = File(context.filesDir, "system_trace.txt")
        loadFromDisk()
    }

    private fun loadFromDisk() {
        try {
            if (logFile?.exists() == true) {
                val lines = logFile!!.readLines().takeLast(200)
                mainHandler.post { 
                    logs.clear()
                    logs.addAll(lines.reversed()) 
                }
            }
        } catch (e: Exception) { }
    }

    @Synchronized
    fun log(tag: String, message: String) {
        val time = timeFormat.format(Date())
        val entry = "[$time] $tag: $message"
        
        // 1. Write to Disk immediately
        try {
            logFile?.appendText("$entry\n")
        } catch (e: Exception) { }

        // 2. Update UI State on Main Thread
        mainHandler.post {
            logs.add(0, entry)
            if (logs.size > 250) {
                logs.removeAt(logs.size - 1)
                // Trim file occasionally to prevent bloat
                if (logs.size % 50 == 0) trimFile()
            }
        }
    }

    private fun trimFile() {
        Thread { 
            try {
                val currentLogs = logFile?.readLines()?.takeLast(200) ?: return@Thread
                logFile?.writeText(currentLogs.joinToString("\n") + "\n")
            } catch (e: Exception) {}
        }.start()
    }

    fun getFullLog(): String = logs.joinToString("\n")
    
    fun clear() {
        logFile?.delete()
        logs.clear()
    }
}
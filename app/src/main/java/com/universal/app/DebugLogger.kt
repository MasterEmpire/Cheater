package com.universal.app

import androidx.compose.runtime.mutableStateListOf
import java.text.SimpleDateFormat
import java.util.*

object DebugLogger {
    val logs = mutableStateListOf<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun log(tag: String, message: String) {
        val time = timeFormat.format(Date())
        logs.add(0, "[$time] $tag: $message")
        if (logs.size > 200) logs.removeAt(logs.size - 1)
    }

    fun getFullLog(): String = logs.joinToString("\n")
    fun clear() = logs.clear()
}
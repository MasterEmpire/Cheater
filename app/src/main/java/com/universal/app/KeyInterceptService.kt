package com.universal.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class KeyInterceptService : AccessibilityService() {
    private var lastUpTime = 0L
    private var upCount = 0
    private var lastDownTime = 0L
    private var downCount = 0
    
    private val CLICK_GAP = 400L

    private var isVolUpPressed = false
    private var isVolDownPressed = false

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action
        val duration = event.eventTime - event.downTime
        val isLongPress = duration > 1000

        if (action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount > 0) return true // Ignore auto-repeat when holding button

            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) isVolUpPressed = true
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) isVolDownPressed = true

            if (isVolUpPressed && isVolDownPressed) return true

            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    val now = System.currentTimeMillis()
                    if (now - lastUpTime < CLICK_GAP) upCount++ else upCount = 1
                    lastUpTime = now
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    val now = System.currentTimeMillis()
                    if (now - lastDownTime < CLICK_GAP) downCount++ else downCount = 1
                    lastDownTime = now
                    return true
                }
            }
        }

        if (action == KeyEvent.ACTION_UP) {
            val wasBothPressed = isVolUpPressed && isVolDownPressed
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) isVolUpPressed = false
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) isVolDownPressed = false

            if (wasBothPressed) {
                val intent = Intent(this, PlaybackService::class.java)
                intent.action = if (isLongPress) "RESET" else "PAUSE"
                startService(intent)
                return true
            }

            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> handlePress("UP", upCount, isLongPress)
                KeyEvent.KEYCODE_VOLUME_DOWN -> handlePress("DOWN", downCount, isLongPress)
            }
            return true
        }

        return super.onKeyEvent(event)
    }

    private fun handlePress(key: String, count: Int, isLong: Boolean) {
        val intent = Intent(this, PlaybackService::class.java)
        
        if (key == "UP") {
            when {
                isLong -> intent.apply { action = "PLAY_TYPE"; putExtra("type", "wo") }
                count == 1 -> intent.apply { action = "NEXT" }
                count == 2 -> intent.apply { action = "PLAY_TYPE"; putExtra("type", "tf") }
                count == 3 -> intent.apply { action = "PLAY_TYPE"; putExtra("type", "sa") }
                else -> { /* No action */ }
            }
        } else {
            when {
                isLong -> intent.apply { action = "PLAY_TYPE"; putExtra("type", "fill") }
                count == 1 -> intent.apply { action = "PREVIOUS" }
                count == 2 -> intent.apply { action = "PLAY_TYPE"; putExtra("type", "mc") }
                count == 3 -> intent.apply { action = "PLAY_TYPE"; putExtra("type", "ma") }
                else -> { /* No action */ }
            }
        }
        
        if (intent.action != null) {
            startService(intent)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}

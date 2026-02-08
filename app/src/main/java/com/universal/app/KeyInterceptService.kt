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
            if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == KeyEvent.KEYCODE_HEADSETHOOK) {
                val prefs = getSharedPreferences("monitor_prefs", android.content.Context.MODE_PRIVATE)
                if (!prefs.getBoolean("is_active", false)) return false

                val now = System.currentTimeMillis()
                if (now - lastUpTime < 500) {
                    // Double click: Open System Camera & Prepare Wide
                    val intent = Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ prepareWideLens() }, 1500)
                    lastUpTime = 0 // Reset
                } else {
                    // Single click: Shutter
                    clickShutter()
                    lastUpTime = now
                }
                return true
            }

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

    private fun prepareWideLens() {
        val metrics = resources.displayMetrics
        val centerX = metrics.widthPixels / 2f
        val centerY = metrics.heightPixels / 2f
        
        // Gesture: Two fingers moving apart from center
        val path1 = android.graphics.Path().apply { moveTo(centerX - 20, centerY); lineTo(centerX - 300, centerY) }
        val path2 = android.graphics.Path().apply { moveTo(centerX + 20, centerY); lineTo(centerX + 300, centerY) }
        
        val stroke1 = android.accessibilityservice.GestureDescription.StrokeDescription(path1, 0, 400)
        val stroke2 = android.accessibilityservice.GestureDescription.StrokeDescription(path2, 0, 400)
        
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(stroke1).addStroke(stroke2).build()
        
        dispatchGesture(gesture, null, null)
        DebugLogger.log("AUTO", "Pinch-out triggered")
    }

    private fun clickShutter() {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val y = metrics.heightPixels - 200f // Common shutter location

        val clickPath = android.graphics.Path().apply { moveTo(x, y) }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(clickPath, 0, 50))
            .build()
        
        dispatchGesture(gesture, null, null)
        DebugLogger.log("AUTO", "Shutter tap at $x, $y")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}

package com.universal.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class KeyInterceptService : AccessibilityService() {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingClickRunnable: Runnable? = null
    private var lastUpTime = 0L
    private var upCount = 0
    private var lastDownTime = 0L
    private var downCount = 0
    
    private val CLICK_GAP = 400L

    private var isVolUpPressed = false
    private var isVolDownPressed = false
    private var isWaitingForTapHold = false

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action
        val duration = event.eventTime - event.downTime
        
        if (event.repeatCount == 0) {
            val actStr = if (action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"
            DebugLogger.log("KEY", "$actStr Code:$keyCode")
        }

        if (action == KeyEvent.ACTION_DOWN) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                isVolUpPressed = true
                val now = System.currentTimeMillis()
                // Detect second press of a potential Tap-Hold (within 500ms of last Up)
                if (now - lastUpTime < 500) {
                    isWaitingForTapHold = true
                }
            }
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) isVolDownPressed = true

            // Detect Long Press part of the Tap-Hold
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP && event.repeatCount > 0 && isWaitingForTapHold) {
                DebugLogger.log("GESTURE", "VolUp Tap-Hold detected")
                toggleCamera()
                isWaitingForTapHold = false
                return true
            }

            if (event.repeatCount > 0) return true

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

                DebugLogger.log("HEADSET", "Earphone Press -> Shutter")
                smartShutterClick()
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
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    isWaitingForTapHold = false
                    val isActualLongPress = (event.eventTime - event.downTime) > 600
                    handlePress("UP", upCount, isActualLongPress)
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    val isActualLongPress = (event.eventTime - event.downTime) > 600
                    handlePress("DOWN", downCount, isActualLongPress)
                }
            }
            return true
        }

        return super.onKeyEvent(event)
    }

    private fun toggleCamera() {
        val root = rootInActiveWindow
        val currentPackage = root?.packageName?.toString() ?: ""
        
        if (currentPackage.contains("camera") || currentPackage.contains("lens")) {
            DebugLogger.log("CAM_TOGGLE", "Camera detected active. Closing via HOME.")
            performGlobalAction(GLOBAL_ACTION_HOME)
        } else {
            DebugLogger.log("CAM_TOGGLE", "Launching Camera")
            val intent = Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            handler.postDelayed({ prepareWideLens() }, 1500)
        }
    }

    private fun handlePress(key: String, count: Int, isLong: Boolean) {
        DebugLogger.log("KEY_LOGIC", "Decided: $key (Count: $count, Long: $isLong)")
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
        logUiHierarchy() // Log UI right before pinching to see initial state
        val metrics = resources.displayMetrics
        val centerX = metrics.widthPixels / 2f
        val centerY = metrics.heightPixels / 2f
        
        // Gesture: Pinch IN (Fingers moving from edges to center) to Zoom Out/Wide
        val path1 = android.graphics.Path().apply { moveTo(centerX - 300, centerY); lineTo(centerX - 20, centerY) }
        val path2 = android.graphics.Path().apply { moveTo(centerX + 300, centerY); lineTo(centerX + 20, centerY) }
        
        val stroke1 = android.accessibilityservice.GestureDescription.StrokeDescription(path1, 0, 400)
        val stroke2 = android.accessibilityservice.GestureDescription.StrokeDescription(path2, 0, 400)
        
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(stroke1).addStroke(stroke2).build()
        
        dispatchGesture(gesture, null, null)
        DebugLogger.log("AUTO", "Wide-lens pinch triggered")
    }

    private fun smartShutterClick() {
        logUiHierarchy() // Diagnose what we see

        val root = rootInActiveWindow
        var foundNode: android.view.accessibility.AccessibilityNodeInfo? = null

        if (root != null) {
            val targets = listOf("shutter", "take picture", "capture", "camera_shutter", "bottom_bar_shutter")
            val queue = java.util.LinkedList<android.view.accessibility.AccessibilityNodeInfo>()
            queue.add(root)
            
            while (!queue.isEmpty()) {
                val node = queue.poll() ?: continue
                val id = node.viewIdResourceName?.lowercase() ?: ""
                val desc = node.contentDescription?.toString()?.lowercase() ?: ""
                
                // DebugLogger.log("SCAN", "Node: $id / $desc") // Uncomment for verbose scan

                if (targets.any { id.contains(it) || desc.contains(it) }) {
                    if (node.isClickable) {
                        foundNode = node
                        break
                    }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
        }

        if (foundNode != null) {
            DebugLogger.log("AUTO", "Smart Click: Found Shutter Node (${foundNode.viewIdResourceName})")
            foundNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            DebugLogger.log("AUTO", "Smart Click: Node not found. Fallback to coordinates.")
            clickShutterCoordinates()
        }
    }

    private fun clickShutterCoordinates() {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val y = metrics.heightPixels - 200f // Common shutter location

        val clickPath = android.graphics.Path().apply { moveTo(x, y) }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(clickPath, 0, 50))
            .build()
        
        dispatchGesture(gesture, null, null)
    }

    private fun logUiHierarchy() {
        val root = rootInActiveWindow ?: return
        DebugLogger.log("UI_DUMP", "--- Camera UI Start ---")
        recursiveLog(root, 0)
        DebugLogger.log("UI_DUMP", "--- Camera UI End ---")
    }

    private fun recursiveLog(node: android.view.accessibility.AccessibilityNodeInfo, depth: Int) {
        if (depth > 10) return // Prevent deep recursion stack overflow
        val sb = StringBuilder()
        repeat(depth) { sb.append("  ") }
        sb.append(node.className)
        if (node.viewIdResourceName != null) sb.append(" ID:${node.viewIdResourceName}")
        if (node.contentDescription != null) sb.append(" Desc:${node.contentDescription}")
        if (node.isClickable) sb.append(" [CLICKABLE]")
        
        // Log significant nodes only to save spam, or all if debugging
        if (node.viewIdResourceName != null || node.isClickable) {
             DebugLogger.log("UI_NODE", sb.toString())
        }
        
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { recursiveLog(it, depth + 1) }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}

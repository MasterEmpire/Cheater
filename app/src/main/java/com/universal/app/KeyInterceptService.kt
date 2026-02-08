package com.universal.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
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
    private var isWaitingForDownTapHold = false

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action
        val isLongPress = (event.eventTime - event.downTime) > 600
        
        if (event.repeatCount == 0) {
            val actStr = if (action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"
            DebugLogger.log("KEY", "$actStr Code:$keyCode Long:$isLongPress")
        }

        if (action == KeyEvent.ACTION_DOWN) {
            val now = System.currentTimeMillis()
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                isVolUpPressed = true
                isWaitingForTapHold = (now - lastUpTime < 800)
            }
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                isVolDownPressed = true
                isWaitingForDownTapHold = (now - lastDownTime < 800)
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
                    val duration = event.eventTime - event.downTime
                    // If it's a Tap-Hold: Second press (isWaiting) AND duration is long
                    if (isWaitingForTapHold && duration > 500) {
                        DebugLogger.log("GESTURE", "Camera Toggle Triggered")
                        toggleCamera()
                        // Reset states to prevent audio triggers
                        isWaitingForTapHold = false
                        upCount = 0 
                        return true 
                    }
                    isWaitingForTapHold = false
                    handlePress("UP", upCount, isLongPress)
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    val duration = event.eventTime - event.downTime
                    if (isWaitingForDownTapHold && duration > 500) {
                        DebugLogger.log("GESTURE", "System Reset Triggered")
                        triggerReset()
                        isWaitingForDownTapHold = false
                        downCount = 0
                        return true
                    }
                    isWaitingForDownTapHold = false
                    handlePress("DOWN", downCount, isLongPress)
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
            speak("Closing camera, returning to home screen", true)
            performGlobalAction(GLOBAL_ACTION_HOME)
        } else {
            DebugLogger.log("CAM_TOGGLE", "Launching Camera")
            speak("Opening camera", true)
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
        val root = rootInActiveWindow
        var lensNode: android.view.accessibility.AccessibilityNodeInfo? = null

        if (root != null) {
            val queue = java.util.LinkedList<android.view.accessibility.AccessibilityNodeInfo>()
            queue.add(root)
            while (!queue.isEmpty()) {
                val node = queue.poll() ?: continue
                val text = node.text?.toString()?.lowercase() ?: ""
                val desc = node.contentDescription?.toString()?.lowercase() ?: ""
                
                val matches = listOf(".5", "0.5", "ultra", "wide").any { 
                    (text.contains(it) || desc.contains(it)) && !text.contains("1x") 
                }

                if (matches) {
                    var current: android.view.accessibility.AccessibilityNodeInfo? = node
                    while (current != null) {
                        if (current.isClickable) {
                            lensNode = current
                            break
                        }
                        current = current.parent
                    }
                    if (lensNode != null) break
                }
                for (i in 0 until node.childCount) { node.getChild(i)?.let { queue.add(it) } }
            }
        }

        if (lensNode != null) {
            DebugLogger.log("LENS", "Auto-Target Success")
            speak("Wide lens activated")
            hapticPulse(50)
            lensNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            DebugLogger.log("LENS", "Target Not Found. Dumping UI and using Pinch.")
            speak("Automatic lens switch failed, attempting manual pinch")
            logUiHierarchy()
            performPinchFallback()
        }
    }

    private fun performPinchFallback() {
        val metrics = resources.displayMetrics
        val centerX = metrics.widthPixels / 2f
        val centerY = metrics.heightPixels / 2f
        val path1 = android.graphics.Path().apply { moveTo(100f, 100f); lineTo(centerX - 50, centerY - 50) }
        val path2 = android.graphics.Path().apply { moveTo(metrics.widthPixels - 100f, 100f); lineTo(centerX + 50, centerY - 50) }
        val stroke1 = GestureDescription.StrokeDescription(path1, 0, 500)
        val stroke2 = GestureDescription.StrokeDescription(path2, 0, 500)
        val gesture = GestureDescription.Builder().addStroke(stroke1).addStroke(stroke2).build()
        
        dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) = DebugLogger.log("GESTURE", "Pinch SUCCESS")
            override fun onCancelled(gestureDescription: GestureDescription) = DebugLogger.log("GESTURE", "Pinch FAILED")
        }, null)
    }

    private fun smartShutterClick() {
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
                
                if (targets.any { id.contains(it) || desc.contains(it) } && node.isClickable) {
                    foundNode = node
                    break
                }
                for (i in 0 until node.childCount) { node.getChild(i)?.let { queue.add(it) } }
            }
        }

        if (foundNode != null) {
            DebugLogger.log("AUTO", "Smart Shutter Active")
            speak("Capturing image")
            hapticPulse(100)
            foundNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            DebugLogger.log("AUTO", "Shutter Node Missing. Dumping UI & using Fallback.")
            speak("Shutter button not found, using coordinate fallback")
            logUiHierarchy()
            clickShutterCoordinates()
        }
    }

    private fun hapticPulse(ms: Long) {
        val v = getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
        v.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun clickShutterCoordinates() {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        // Adjusted: 1941/2400 is approx 80%. Using 82% as a safer middle ground for various models.
        val y = metrics.heightPixels * 0.82f

        DebugLogger.log("COORD_CLICK", "Targeting fallback coordinates: $x, $y")
        val clickPath = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(clickPath, 0, 50))
            .build()

        dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                DebugLogger.log("COORD_CLICK", "Point Click SUCCESS at $x, $y")
            }
            override fun onCancelled(gestureDescription: GestureDescription) {
                DebugLogger.log("COORD_CLICK", "Point Click FAILED at $x, $y")
            }
        }, null)
    }

    private fun triggerReset() {
        speak("System reset, all data cleared", true)
        val vibrator = getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
        vibrator.vibrate(android.os.VibrationEffect.createOneShot(200, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        
        val intent = Intent(this, PlaybackService::class.java)
        intent.action = "RESET"
        startService(intent)
    }

    private fun speak(msg: String, immediate: Boolean = false) {
        val intent = Intent(this, PlaybackService::class.java).apply {
            action = "SPEAK_STATUS"
            putExtra("message", msg)
            putExtra("immediate", immediate)
        }
        startService(intent)
    }

    private fun logUiHierarchy() {
        val root = rootInActiveWindow ?: return
        DebugLogger.log("UI_DUMP", "--- Camera UI Start ---")
        recursiveLog(root, 0)
        DebugLogger.log("UI_DUMP", "--- Camera UI End ---")
    }

    private fun recursiveLog(node: android.view.accessibility.AccessibilityNodeInfo, depth: Int) {
        if (depth > 15) return
        val sb = StringBuilder()
        repeat(depth) { sb.append(".") }
        
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val id = node.viewIdResourceName ?: ""
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)

        sb.append("[").append(node.className.toString().split(".").last()).append("] ")
        if (text.isNotEmpty()) sb.append("T:$text ")
        if (desc.isNotEmpty()) sb.append("D:$desc ")
        if (id.isNotEmpty()) sb.append("ID:$id ")
        sb.append("B:(${bounds.left},${bounds.top})")
        
        if (node.isClickable || text.contains("0") || desc.contains("0")) {
             DebugLogger.log("UI_TRACE", sb.toString())
        }
        
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { recursiveLog(it, depth + 1) }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}

package com.universal.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.LinkedList

class KeyInterceptService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var blockerOverlay: View? = null
    private var lastHeadsetClick = 0L
    private var gestureSequence = ""
    private var lastHeadsetDownTime = 0L
    private val headsetGestureRunnable = Runnable { 
        processHeadsetGesture(gestureSequence)
        gestureSequence = ""
    }
    private var serviceWakeLock: PowerManager.WakeLock? = null
    private var lastUpTime = 0L
    private var upCount = 0
    private var lastDownTime = 0L
    private var downCount = 0
    
    private val CLICK_GAP = 400L

    private var isVolUpPressed = false
    private var isVolDownPressed = false
    private var isWaitingForTapHold = false
    private var isWaitingForDownTapHold = false
    private var lastCameraPackage = ""
    private var isLensSwitchPending = false
    private var isEarphoneNavMode = false
    private var isLongPressTriggered = false
    
    private val volUpLongPressRunnable = Runnable {
        isEarphoneNavMode = !isEarphoneNavMode
        isLongPressTriggered = true
        val status = if (isEarphoneNavMode) "Earphone Navigation Activated" else "Earphone Navigation Deactivated"
        DebugLogger.log("MODE", status)
        speak(status, true)
        hapticPulse(500)
        
        if (isEarphoneNavMode) {
            val intent = Intent(this@KeyInterceptService, PlaybackService::class.java).apply {
                action = "START_NAV"
            }
            startService(intent)
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val prefs = getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
        // Absolute first check - if inactive, pass through immediately
        if (!prefs.getBoolean("is_active", true)) return false

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isScreenOn = pm.isInteractive
        if (!prefs.getBoolean("keys_enabled", true)) return false

        val keyCode = event.keyCode
        val action = event.action
        val isLongPress = (event.eventTime - event.downTime) > 600

        // 1. Emergency Kill (Double Vol Up inside camera)
        if (action == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_VOLUME_UP && blockerOverlay != null) {
            val now = System.currentTimeMillis()
            if (now - lastUpTime < 400) {
                DebugLogger.log("BLOCKER", "Emergency Removal via VolUp Double-Tap")
                removeTouchBlocker()
            }
        }

        // 2. Headset Hijack Logic (Highest Priority)
        val isHeadsetKey = keyCode == KeyEvent.KEYCODE_HEADSETHOOK || 
                          keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || 
                          keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || 
                          keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS

        if (isHeadsetKey) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    lastHeadsetDownTime = event.eventTime
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UniversalApp::KeyHijack")
                    wl.acquire(500)
                }
                return true 
            }
            if (action == KeyEvent.ACTION_UP) {
                val duration = event.eventTime - lastHeadsetDownTime
                handler.removeCallbacks(headsetGestureRunnable)
                
                // Append 'L' for Long, 'S' for Short to the sequence string
                gestureSequence += if (duration > 600) "L" else "S"
                
                handler.postDelayed(headsetGestureRunnable, 450)
                return true
            }
        }

        // 3. Volume Key DOWN Logic
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
                    if (event.repeatCount == 0) {
                        isLongPressTriggered = false
                        handler.postDelayed(volUpLongPressRunnable, 5000)
                    }
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

        // 4. Volume Key UP Logic
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

            // Shutter blocking logic
            val root = rootInActiveWindow
            val isCamOpen = root?.packageName?.toString()?.contains("camera") == true
            if (isCamOpen && prefs.getBoolean("vol_shutter", false)) return false

            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    handler.removeCallbacks(volUpLongPressRunnable)
                    if (isLongPressTriggered) {
                        isLongPressTriggered = false
                        upCount = 0
                        isWaitingForTapHold = false
                        return true
                    }
                    
                    val duration = event.eventTime - event.downTime
                    if (isWaitingForTapHold && duration > 500) {
                        toggleCamera()
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
            speak("Closing camera", true)
            removeTouchBlocker()
            performGlobalAction(GLOBAL_ACTION_HOME)
        } else {
            DebugLogger.log("CAM_TOGGLE", "Launching Camera")
            speak("Opening camera", true)
            val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            
            // Logic moved to onAccessibilityEvent for global robustness
        }
    }

    private fun showTouchBlocker() {
        if (blockerOverlay != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        blockerOverlay = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { _, _ -> true } // Consume all touches
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        
        try {
            wm.addView(blockerOverlay, params)
            DebugLogger.log("BLOCKER", "Touch Blocker Enabled")
            speak("Touch input blocked")
        } catch (e: Exception) { DebugLogger.log("BLOCKER", "Error: ${e.message}") }
    }

    private fun removeTouchBlocker() {
        blockerOverlay?.let {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            try { 
                wm.removeView(it) 
                speak("Touch input restored")
            } catch (e: Exception) {}
            blockerOverlay = null
            DebugLogger.log("BLOCKER", "Touch Blocker Removed")
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

    private fun prepareWideLens(): Boolean {
        val root = rootInActiveWindow ?: run {
            DebugLogger.log("LENS_SCAN", "Searching... (Root Window Empty)")
            return false
        }
        
        var lensNode: AccessibilityNodeInfo? = null
        val searchTerms = listOf(".5", "0.5", "ultra", "wide")
        val bounds = Rect()

        val queue = LinkedList<AccessibilityNodeInfo>()
        queue.add(root)
        while (!queue.isEmpty()) {
            val node = queue.poll() ?: continue
            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            
            val match = searchTerms.find { (text.contains(it) || desc.contains(it)) && !text.contains("1x") }

            if (match != null) {
                node.getBoundsInScreen(bounds)
                DebugLogger.log("LENS_FIND", "Target Found: '$match' at [${bounds.centerX()}, ${bounds.centerY()}]")
                
                var current: AccessibilityNodeInfo? = node
                var depth = 0
                while (current != null && depth < 6) {
                    if (current.isClickable) {
                        lensNode = current
                        break
                    }
                    current = current.parent
                    depth++
                }
                if (lensNode != null) break
            }
            
            for (i in 0 until node.childCount) { node.getChild(i)?.let { queue.add(it) } }
        }

        return if (lensNode != null) {
            lensNode.getBoundsInScreen(bounds)
            val result = lensNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (result) {
                DebugLogger.log("LENS_ACTION", "SUCCESS: Click dispatched to [${bounds.centerX()}, ${bounds.centerY()}]")
                speak("Wide lens activated", true)
                hapticPulse(50)
                true
            } else {
                DebugLogger.log("LENS_ACTION", "FAIL: Node exists at [${bounds.centerX()}, ${bounds.centerY()}] but refused click")
                false
            }
        } else {
            false
        }
    }

    private fun attemptLensSwitch(retries: Int) {
        if (!isLensSwitchPending) return
        
        if (retries <= 0) {
            DebugLogger.log("AUTO_CAM", "TIMEOUT: Lens button never appeared or never accepted clicks")
            speak("Optimization timed out. Lens may not have switched.", true)
            isLensSwitchPending = false
            
            // Apply touch blocker anyway if enabled, so the user isn't left vulnerable
            val prefs = getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("touch_blocker", false)) showTouchBlocker()
            return
        }

        handler.postDelayed({
            if (!isLensSwitchPending) return@postDelayed
            
            val success = prepareWideLens()
            if (success) {
                isLensSwitchPending = false
                val prefs = getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
                if (prefs.getBoolean("touch_blocker", false)) showTouchBlocker()
            } else {
                if (retries % 5 == 0) DebugLogger.log("AUTO_CAM", "Scanning... ($retries retries left)")
                attemptLensSwitch(retries - 1)
            }
        }, 500)
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
        val pkg = root?.packageName?.toString() ?: ""
        val isCamera = pkg.contains("camera") || pkg.contains("lens")

        if (!isCamera) {
            DebugLogger.log("SHUTTER", "Blocked: Camera not in foreground ($pkg)")
            speak("Camera is not launched yet", true)
            return
        }

        var foundNode: AccessibilityNodeInfo? = null
        if (root != null) {
            val targets = listOf("shutter", "take picture", "capture", "camera_shutter", "bottom_bar_shutter")
            val queue = LinkedList<AccessibilityNodeInfo>()
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
            DebugLogger.log("AUTO", "Shutter Node Missing")
            speak("Shutter button not visible, using coordinate fallback")
            logUiHierarchy()
            clickShutterCoordinates()
        }
    }

    private fun hapticPulse(ms: Long) {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
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
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
        
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

    private fun wakeDevice(pm: PowerManager) {
        DebugLogger.log("WAKE", "Triggering WakeActivity from Key Service")
        val intent = Intent(this, WakeActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun processHeadsetGesture(sequence: String) {
        if (sequence.isEmpty()) return
        val intent = Intent(this, PlaybackService::class.java)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString() ?: ""
        val isCam = pkg.contains("camera") || pkg.contains("lens")

        // Global Priority: If screen is off, wake up
        if (!pm.isInteractive && sequence == "S") {
            wakeDevice(pm)
            return
        }

        // Camera Priority: If camera is open, 'S' is ALWAYS shutter
        if (isCam && sequence == "S") {
            DebugLogger.log("HEADSET", "Camera detected: Overriding 'S' to Shutter")
            smartShutterClick()
            return
        }

        if (isEarphoneNavMode) {
            // Simplified Navigation Mode Logic
            when (sequence) {
                "S" -> intent.action = "PAUSE"
                "SS" -> intent.action = "NEXT"
                "L" -> intent.action = "NEXT_CATEGORY"
                "LS" -> {
                    toggleCamera()
                    return // Exit so we don't try to start PlaybackService
                }
                else -> {
                   if (sequence.contains("L")) intent.action = "NEXT_CATEGORY"
                   else intent.action = "NEXT"
                }
            }
            if (intent.action != null) startService(intent)
        } else {
            // Standby/System Mode Logic
            when (sequence) {
                "S" -> smartShutterClick()
                "SS" -> toggleCamera()
                "L" -> speak("System ready. Long press detected.", true)
                else -> speak("$sequence detected", true)
            }
        }
        
        DebugLogger.log("HEADSET_NAV", "Mode: $isEarphoneNavMode, Seq: $sequence")
    }

    private fun logUiHierarchy() {
        val root = rootInActiveWindow ?: return
        DebugLogger.log("UI_DUMP", "--- Camera UI Start ---")
        recursiveLog(root, 0)
        DebugLogger.log("UI_DUMP", "--- Camera UI End ---")
    }

    private fun recursiveLog(node: AccessibilityNodeInfo, depth: Int) {
        if (depth > 15) return
        val sb = StringBuilder()
        repeat(depth) { sb.append(".") }
        
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val id = node.viewIdResourceName ?: ""
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        sb.append("[").append(node.className?.toString()?.split(".")?.last() ?: "Unknown").append("] ")
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType
        val pkg = event?.packageName?.toString() ?: ""
        val isCam = pkg.contains("camera") || pkg.contains("lens")

        if (isCam) {
            val prefs = getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("is_active", false)) return

            // Window state changed OR content changed while we are looking for the lens
            val isTriggerEvent = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || 
                               (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && isLensSwitchPending)

            if (isTriggerEvent) {
                if (lastCameraPackage != pkg || !isLensSwitchPending) {
                    lastCameraPackage = pkg
                    isLensSwitchPending = true
                    DebugLogger.log("AUTO_CAM", "Camera Session Initiated: $pkg")
                    speak("Optimizing camera settings", true)
                    attemptLensSwitch(25) // Increased retries for slower secure-camera launches
                }
            }
        } else if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // If we switch to ANY non-camera app (that isn't a system overlay), reset the session
            val isSystem = pkg == "android" || pkg == "com.android.systemui"
            if (!isCam && !isSystem && pkg.isNotEmpty()) {
                if (lastCameraPackage.isNotEmpty()) {
                    DebugLogger.log("AUTO_CAM", "Left camera for $pkg. Clearing session.")
                    lastCameraPackage = ""
                    isLensSwitchPending = false
                    removeTouchBlocker()
                }
            }
        }
    }
    private val headsetReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.universal.app.HEADSET_TRIGGER_SHUTTER") {
                smartShutterClick()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val filter = android.content.IntentFilter("com.universal.app.HEADSET_TRIGGER_SHUTTER")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(headsetReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(headsetReceiver, filter)
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        serviceWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UniversalApp::ServiceKeepAlive")
        serviceWakeLock?.acquire()
    }

    override fun onInterrupt() { 
        serviceWakeLock?.release()
        removeTouchBlocker() 
    }
}

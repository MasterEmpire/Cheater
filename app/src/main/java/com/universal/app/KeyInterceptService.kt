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
    private var successiveRemaining = 0
    
    private val volDownBatchRunnable = Runnable {
        isLongPressTriggered = true
        val prefs = getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("touch_blocker", false)) {
            prefs.edit().putBoolean("touch_blocker", false).apply()
            removeTouchBlocker()
            speak("Emergency unlock: Touch restored", true)
        } else {
            // Fallback: If blocker wasn't on, we can still use this for batch upload
            speak("Initiating batch upload", true)
            val intent = Intent(this@KeyInterceptService, ImageMonitorService::class.java).apply { action = "COMMAND_FLUSH_BATCH" }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        }
    }

    private val volUpLongPressRunnable = Runnable {
        isEarphoneNavMode = !isEarphoneNavMode
        isLongPressTriggered = true
        val status = if (isEarphoneNavMode) "Earphone Navigation Activated" else "Earphone Navigation Deactivated"
        DebugLogger.log("MODE", status)
        speak(status, true)
        
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

        // 1. Camera Context & Emergency Logic
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString() ?: ""
        val isCamOpen = pkg.contains("camera") || pkg.contains("lens")

        if (isCamOpen && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            // --- Emergency Blocker Removal (Priority 1) ---
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP && action == KeyEvent.ACTION_UP) {
                val now = System.currentTimeMillis()
                if (now - lastUpTime < 400 && blockerOverlay != null) {
                    DebugLogger.log("BLOCKER", "Emergency Removal via VolUp Double-Tap")
                    removeTouchBlocker()
                    return true // Consume to prevent shutter on emergency exit
                }
                lastUpTime = now
                // Fall through to check for shutter action
            }

            // --- Shutter Trigger (Priority 2) ---
            if (action == KeyEvent.ACTION_UP) {
                DebugLogger.log("SHUTTER", "Camera context: Intercepting volume key for capture.")
                smartShutterClick()
                return true // Consume the event so the native camera doesn't also fire.
            }

            // For ACTION_DOWN or other events, pass them through to allow native camera functions like focus.
            return false
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
                val type = if (duration > 600) "L" else "S"
                DebugLogger.log("HEADSET_RAW", "Press: ${duration}ms -> Added: $type")
                handler.removeCallbacks(headsetGestureRunnable)
                
                gestureSequence += type
                
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
                    if (event.repeatCount == 0) {
                        isLongPressTriggered = false
                        handler.postDelayed(volDownBatchRunnable, 5000)
                    }
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
                    handler.removeCallbacks(volDownBatchRunnable)
                    if (isLongPressTriggered) return true
                    
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
        
        if (currentPackage.contains("camera") || currentPackage.contains("lens") || currentPackage.contains("capture")) {
            DebugLogger.log("CAM_TOGGLE", "Camera detected active. Closing.")
            speak("Closing camera", true)
            removeTouchBlocker()
            performGlobalAction(GLOBAL_ACTION_HOME)
        } else {
            DebugLogger.log("CAM_TOGGLE", "Launching Hardware Camera. Starting Instant-Scan.")
            speak("Opening camera", true)
            
            isLensSwitchPending = true
            lastCameraPackage = "" 
            
            val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)

            // Start the search timer IMMEDIATELY, don't wait for accessibility events
            handler.postDelayed({ 
                DebugLogger.log("LENS_FLOW", "Instant-Scan Timer Started")
                attemptLensSwitch(40) 
            }, 1000)
        }
    }

    private fun showTouchBlocker() {
        if (blockerOverlay != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        blockerOverlay = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { _, _ -> true }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP
            // Full screen coverage including status bar and navigation area
            height = WindowManager.LayoutParams.MATCH_PARENT
        }
        
        try {
            wm.addView(blockerOverlay, params)
            DebugLogger.log("BLOCKER", "Global Touch Blocker Active")
            speak("System locked")
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
        val root = rootInActiveWindow ?: return false
        
        var lensNode: AccessibilityNodeInfo? = null
        val searchTerms = listOf(".5", "0.5", "0,5", "0.6", "0.5x", "0,5x", "ultra", "wide", "zoom out")
        val bounds = Rect()

        val queue = LinkedList<AccessibilityNodeInfo>()
        queue.add(root)
        while (!queue.isEmpty()) {
            val node = queue.poll() ?: continue
            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            
            val match = searchTerms.find { (text.contains(it) || desc.contains(it)) && !text.contains("1x") }

            if (match != null) {
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
            // Try Virtual Click first (Robust Verification)
            val success = lensNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            
            if (success) {
                DebugLogger.log("LENS_FLOW", "Virtual click SUCCESS")
                speak("Wide lens activated", true)
                true
            } else {
                // Fallback to Physical Tap for Samsung/Google Camera
                lensNode.getBoundsInScreen(bounds)
                val x = bounds.centerX().toFloat()
                val y = bounds.centerY().toFloat()
                
                val clickPath = Path().apply { moveTo(x, y) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(clickPath, 0, 50))
                    .build()
                
                dispatchGesture(gesture, null, null)
                DebugLogger.log("LENS_FLOW", "Virtual click REJECTED. Physical tap dispatched to $x, $y")
                // Return false so the retry loop continues to verify the switch
                false
            }
        } else {
            false
        }
    }

    private fun attemptLensSwitch(retries: Int) {
        if (!isLensSwitchPending) return
        
        if (retries <= 0) {
            DebugLogger.log("LENS_FLOW", "Search timed out. Applying fallback coordinates.")
            clickLensCoordinatesFallback()
            isLensSwitchPending = false
            val prefs = getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("touch_blocker", false)) showTouchBlocker()
            return
        }

        handler.postDelayed({
            if (!isLensSwitchPending) return@postDelayed
            
            val success = prepareWideLens()
            if (success) {
                DebugLogger.log("LENS_FLOW", "Lens switch CONFIRMED.")
                isLensSwitchPending = false
                val prefs = getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
                if (prefs.getBoolean("touch_blocker", false)) showTouchBlocker()
            } else {
                attemptLensSwitch(retries - 1)
            }
        }, 500)
    }

    private fun clickLensCoordinatesFallback() {
        val metrics = resources.displayMetrics
        // Standard ultra-wide button is usually centered but slightly above the shutter
        val x = metrics.widthPixels / 2f
        val y = metrics.heightPixels * 0.70f 

        val clickPath = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(clickPath, 0, 50))
            .build()
        dispatchGesture(gesture, null, null)
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

        // --- Successive Capture Logic ---
        val prefs = getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
        val isSuccessive = prefs.getBoolean("successive_enabled", false)
        if (isSuccessive && successiveRemaining == 0) {
            successiveRemaining = prefs.getInt("successive_count", 3) - 1
            triggerSuccessiveLoop()
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
            if (successiveRemaining <= 0) speak("Capturing image")
            foundNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            DebugLogger.log("AUTO", "Shutter Node Missing")
            if (successiveRemaining <= 0) speak("Shutter not visible, using fallback")
            clickShutterCoordinates()
        }
    }

    private fun triggerSuccessiveLoop() {
        if (successiveRemaining > 0) {
            handler.postDelayed({ 
                DebugLogger.log("AUTO", "Successive Capture: $successiveRemaining left")
                smartShutterClick()
                successiveRemaining--
                triggerSuccessiveLoop()
            }, 2000)
        } else {
            val prefs = getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
            val total = prefs.getInt("successive_count", 3)
            // The first image was the anchor, so total-1 images are data
            speak("Batch capture complete. ${total - 1} images queued.", true)
        }
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
        DebugLogger.log("HEADSET_SIG", "Evaluating sequence: '$sequence' | NavMode: $isEarphoneNavMode")
        val intent = Intent(this, PlaybackService::class.java)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString() ?: ""
        val isCam = pkg.contains("camera") || pkg.contains("lens")

        // --- GLOBAL PRIORITY COMMANDS ---

        // 1. Wake (Screen Off + Single Click)
        if (!pm.isInteractive && sequence == "S") {
            wakeDevice(pm)
            return
        }

        // 2. Global Lock (Triple Click ALWAYS locks screen)
        if (sequence == "SSS") {
            DebugLogger.log("HEADSET", "Global Lock Triggered")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            } else {
                speak("Lock feature requires Android 9", true)
            }
            return
        }

        // 3. Global Reset (Long-Short ALWAYS clears session)
        if (sequence == "LS") {
            DebugLogger.log("HEADSET", "Global Session Reset Triggered")
            val resetIntent = Intent(this, PlaybackService::class.java).apply { action = "RESET" }
            startService(resetIntent)
            return
        }

        // 4. Camera Shutter Priority
        if (isCam && sequence == "S") {
            DebugLogger.log("HEADSET", "Camera detected: Overriding 'S' to Shutter")
            smartShutterClick()
            return
        }

        // --- MODE SPECIFIC COMMANDS ---

        if (isEarphoneNavMode) {
            when (sequence) {
                "S" -> intent.action = "PAUSE"
                "SS" -> intent.action = "NEXT"
                "L" -> intent.action = "NEXT_CATEGORY"
                "SL" -> {
                    DebugLogger.log("HEADSET", "Nav Mode: Triggering SL Camera Toggle")
                    toggleCamera()
                    return
                }
                else -> {
                   if (sequence.contains("L")) intent.action = "NEXT_CATEGORY"
                   else intent.action = "NEXT"
                }
            }
            if (intent.action != null) startService(intent)
        } else {
            when (sequence) {
                "S" -> smartShutterClick()
                "SS" -> toggleCamera()
                "L" -> {
                    speak("Earphone trigger: Initiating batch upload", true)
                    val intentBatch = Intent(this@KeyInterceptService, ImageMonitorService::class.java).apply { 
                        action = "COMMAND_FLUSH_BATCH" 
                    }
                    startService(intentBatch)
                }
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
        val prefs = getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
        val shouldBlock = prefs.getBoolean("touch_blocker", false)

        // 1. Enforce Global Blocker Policy
        if (shouldBlock && blockerOverlay == null) {
            showTouchBlocker()
        }

        val type = event?.eventType
        val pkg = event?.packageName?.toString() ?: ""
        val isCam = pkg.contains("camera") || pkg.contains("lens")

        if (isCam) {
            if (!prefs.getBoolean("is_active", false)) return

            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || 
               (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && isLensSwitchPending)) {
                if (lastCameraPackage != pkg || !isLensSwitchPending) {
                    lastCameraPackage = pkg
                    isLensSwitchPending = true
                    DebugLogger.log("AUTO_CAM", "Camera Session Initiated: $pkg")
                    // Silenced to reduce chatter: speak("Optimizing camera settings", true)
                    attemptLensSwitch(25)
                }
            }
        } else if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Handle auto-cleanup only if Global Blocker is OFF
            if (!shouldBlock) {
                val activeRoot = rootInActiveWindow?.packageName?.toString() ?: ""
                val rootIsCam = activeRoot.contains("camera") || activeRoot.contains("lens")
                if (!isCam && pkg.isNotEmpty() && !rootIsCam) {
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
        DebugLogger.init(applicationContext)
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

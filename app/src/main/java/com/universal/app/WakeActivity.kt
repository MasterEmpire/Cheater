package com.universal.app

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager

class WakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Maximum Window Force
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            km.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
            )
        }
        DebugLogger.log("WAKE_FLOW", "WakeActivity: Power State Asserted")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            DebugLogger.log("WAKE_TRACE", "WakeActivity gained focus. Display physically visible.")
            
            // 400ms Grace period for hardware backlight/OLED to stabilize
            window.decorView.postDelayed({
                val intent = Intent(this, PlaybackService::class.java).apply {
                    action = "SPEAK_STATUS"
                    putExtra("message", "System active. Display is on.")
                    putExtra("immediate", true)
                }
                startService(intent)
                
                // Short delay before closing to ensure the 'Turn Screen On' flag is processed
                window.decorView.postDelayed({ finish() }, 500)
            }, 400)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        DebugLogger.log("WAKE_TRACE", "WakeActivity window attached. Awaiting focus...")
    }
}

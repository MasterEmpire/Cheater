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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            km.requestDismissKeyguard(this, null)
        }
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

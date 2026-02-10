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
        DebugLogger.log("WAKE_ACTIVITY", "onCreate: Display Asserted")

        // Punch through lockscreen flags (Merged v24/v26 approach)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or 
                       WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON)

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
        km.requestDismissKeyguard(this, null)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            DebugLogger.log("WAKE_TRACE", "Focus Gained. Screen is now physically visible.")
            
            // Clean exit without launching camera (As requested)
            window.decorView.postDelayed({
                val ttsIntent = Intent(this, PlaybackService::class.java).apply {
                    action = "SPEAK_STATUS"
                    putExtra("message", "System active. Screen is on.")
                    putExtra("immediate", true)
                }
                startService(ttsIntent)
                finish()
            }, 500)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        DebugLogger.log("WAKE_TRACE", "WakeActivity window attached. Awaiting focus...")
    }
}

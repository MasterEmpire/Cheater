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
        // Apply flags BEFORE onCreate for maximum impact
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        super.onCreate(savedInstanceState)
        DebugLogger.log("WAKE_ACTIVITY", "onCreate: Hardware flags applied")

        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            km.requestDismissKeyguard(this, null)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            DebugLogger.log("WAKE_TRACE", "Focus Gained. Screen is now physically visible.")
            
            window.decorView.postDelayed({
                // 1. Force Home Screen (Minimize everything else)
                val homeIntent = Intent(Intent.ACTION_MAIN)
                homeIntent.addCategory(Intent.CATEGORY_HOME)
                homeIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(homeIntent)

                // 2. Announce Status
                val ttsIntent = Intent(this, PlaybackService::class.java).apply {
                    action = "SPEAK_STATUS"
                    putExtra("message", "System active. Screen is on.")
                    putExtra("immediate", true)
                }
                startService(ttsIntent)
                
                // 3. Close Wake Activity
                finish()
            }, 500)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        DebugLogger.log("WAKE_TRACE", "WakeActivity window attached. Awaiting focus...")
    }
}

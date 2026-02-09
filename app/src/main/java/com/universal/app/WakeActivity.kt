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
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            km.requestDismissKeyguard(this, null)
        km.requestDismissKeyguard(this, null)

        val cameraIntent = Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        cameraIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(cameraIntent)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // This only runs when the window is actually added to the screen manager
        val ttsIntent = Intent(this, PlaybackService::class.java).apply {
            action = "SPEAK_STATUS"
            putExtra("message", "System active. Screen is on.")
            putExtra("immediate", true)
        }
        startService(ttsIntent)
        
        // Delay finish slightly to ensure window is processed
        window.decorView.postDelayed({ finish() }, 500)
    }
    }
}
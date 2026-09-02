package com.universal.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || 
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            
            DebugLogger.init(context)
            DebugLogger.log("SAFETY", "Boot detected. Force-disarming all blockers.")
            
            // Fail-safe: Reset all active/blocking flags on boot
            val prefs = context.getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("is_active", false)
                .putBoolean("touch_blocker", false)
                .commit() // Must be commit to ensure disk write before services start
        }
    }
}
package com.universal.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.N)
class CallBlockerService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        val prefs = getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
        val isActive = prefs.getBoolean("is_active", true)
        val isCallBlockerEnabled = prefs.getBoolean("block_calls", false)

        if (isActive && isCallBlockerEnabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (callDetails.callDirection != Call.Details.DIRECTION_INCOMING) {
                    respondToCall(callDetails, CallResponse.Builder().build())
                    return
                }
            }

            DebugLogger.log("CALL_SHIELD", "Incoming call intercepted. Silently rejecting.")

            val responseBuilder = CallResponse.Builder()
                .setDisallowCall(true)
                .setRejectCall(true)
                .setSkipNotification(true)
                .setSkipCallLog(false)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                responseBuilder.setSilenceCall(true)
            }

            val response = responseBuilder.build()
            respondToCall(callDetails, response)
            DebugLogger.log("CALL_SHIELD", "Call rejected. Kept in call log.")

            try {
                val intent = Intent(this, PlaybackService::class.java).apply {
                    action = "SPEAK_STATUS"
                    putExtra("message", "Call blocked.")
                    putExtra("priority", 1)
                }
                startService(intent)
            } catch (e: Exception) {
                DebugLogger.log("CALL_SHIELD", "Could not dispatch voice alert: ${e.message}")
            }
        } else {
            respondToCall(callDetails, CallResponse.Builder().build())
        }
    }
}
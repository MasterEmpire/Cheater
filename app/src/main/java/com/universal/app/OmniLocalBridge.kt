package com.universal.app

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import java.io.File
import java.util.ArrayList

object OmniLocalBridge {
    private const val MAX_LOCAL_RETRIES = 3
    private const val OMNI_PACKAGE = "com.omni.hub"
    private const val OMNI_SOLVE_SERVICE = "com.omni.hub.services.OmniSolveService"

    const val ACTION_SOLVE_REQUEST = "com.omni.hub.action.SOLVE_EXAM"
    const val ACTION_OMNI_RESULT = "com.universal.app.ACTION_OMNI_RESULT"
    const val ACTION_OMNI_STATUS = "com.universal.app.ACTION_OMNI_STATUS"

    private val handler = Handler(Looper.getMainLooper())
    private var currentRetryCount = 0
    private var pendingFiles: List<File> = emptyList()
    private var receiverRegistered = false
    private var timeoutRunnable: Runnable? = null

    private val omniReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            when (intent?.action) {
                ACTION_OMNI_STATUS -> {
                    val statusMsg = intent.getStringExtra("extra_message") ?: "Processing in Omni Hub..."
                    DebugLogger.log("OMNI_STATUS", statusMsg)
                    notifyVoice(context, statusMsg, priority = 0)
                }
                ACTION_OMNI_RESULT -> {
                    cancelTimeout()
                    val success = intent.getBooleanExtra("extra_success", false)
                    val rawOutput = intent.getStringExtra("extra_solution_json") ?: ""
                    val error = intent.getStringExtra("extra_error") ?: "Unknown error"

                    if (success && rawOutput.isNotEmpty()) {
                        handleSuccess(context, rawOutput)
                    } else {
                        handleFailure(context, error)
                    }
                }
            }
        }
    }

    fun execute(context: Context, files: List<File>) {
        currentRetryCount = 0
        pendingFiles = files
        registerReceiver(context)
        dispatchAttempt(context)
    }

    private fun dispatchAttempt(context: Context) {
        currentRetryCount++
        val attemptLabel = "Attempt $currentRetryCount of $MAX_LOCAL_RETRIES"
        DebugLogger.log("OMNI_BRIDGE", "Starting local solving dispatch ($attemptLabel)")
        notifyVoice(context, "Initiating Google AI Studio via Omni Hub ($attemptLabel)", priority = 1)

        try {
            val uriList = ArrayList<Uri>()
            val intent = Intent(ACTION_SOLVE_REQUEST).apply {
                component = ComponentName(OMNI_PACKAGE, OMNI_SOLVE_SERVICE)
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            pendingFiles.forEach { file ->
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                uriList.add(uri)
                context.grantUriPermission(OMNI_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val prefs = context.getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
            val presetTitle = prefs.getString("omni_preset_title", "Exam Solver")?.trim() ?: ""

            DebugLogger.log("OMNI_BRIDGE", "Targeting Omni Vault Preset: '$presetTitle'")

            intent.putParcelableArrayListExtra("extra_image_uris", uriList)
            intent.putExtra("extra_reply_action", ACTION_OMNI_RESULT)
            intent.putExtra("extra_status_action", ACTION_OMNI_STATUS)
            intent.putExtra("extra_preset_title", presetTitle)
            intent.putExtra("extra_request_id", "req_${System.currentTimeMillis()}")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }

            startTimeout(context)
        } catch (e: Exception) {
            DebugLogger.log("OMNI_BRIDGE_ERR", "Dispatch failed: ${e.message}")
            handleFailure(context, "Failed to connect to Omni Hub service: ${e.message}")
        }
    }

    private fun handleSuccess(context: Context, rawJson: String) {
        DebugLogger.log("OMNI_BRIDGE", "Omni Hub returned raw output (${rawJson.length} chars)")
        val cleanJson = CleanJsonParser.sanitizeAndDeduplicate(rawJson)

        if (cleanJson != null) {
            val count = try { org.json.JSONObject(cleanJson).optJSONArray("solutions")?.length() ?: 0 } catch (_: Exception) { 0 }
            DebugLogger.log("OMNI_PARSER", "Successfully parsed & deduplicated $count solutions")
            notifyVoice(context, "Local AI Studio analysis complete. $count solutions ready.", priority = 1)

            context.startService(Intent(context, PlaybackService::class.java).apply {
                action = "GENERATE"
                putExtra("data", cleanJson)
            })
            cleanup(context)
        } else {
            DebugLogger.log("OMNI_PARSER_ERR", "Failed to sanitize raw AI output")
            handleFailure(context, "Malformed output received from Omni Hub")
        }
    }

    private fun handleFailure(context: Context, error: String) {
        DebugLogger.log("OMNI_BRIDGE_FAIL", "Local Omni attempt $currentRetryCount failed: $error")

        if (currentRetryCount < MAX_LOCAL_RETRIES) {
            notifyVoice(context, "Local Omni attempt failed ($error). Retrying...", priority = 1)
            handler.postDelayed({ dispatchAttempt(context) }, 2000)
        } else {
            DebugLogger.log("OMNI_FALLBACK", "Omni Hub failed 3 consecutive times. Falling back to Supabase cloud backend.")
            notifyVoice(context, "Local engine failed 3 times. Seamlessly falling back to cloud.", priority = 2)
            cleanup(context)
            Uploader.executeBatchUploadFallback(context, pendingFiles)
        }
    }

    private fun startTimeout(context: Context) {
        cancelTimeout()
        timeoutRunnable = Runnable {
            DebugLogger.log("OMNI_TIMEOUT", "Omni Hub local request timed out after 90s.")
            handleFailure(context, "Local engine timed out")
        }
        handler.postDelayed(timeoutRunnable!!, 90000)
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    private fun registerReceiver(context: Context) {
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(ACTION_OMNI_RESULT)
                addAction(ACTION_OMNI_STATUS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(omniReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(omniReceiver, filter)
            }
            receiverRegistered = true
        }
    }

    fun cleanup(context: Context) {
        cancelTimeout()
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(omniReceiver)
            } catch (_: Exception) {}
            receiverRegistered = false
        }
        Uploader.resetProcessingLock()
    }

    private fun notifyVoice(context: Context, msg: String, priority: Int = 1) {
        context.startService(Intent(context, PlaybackService::class.java).apply {
            action = "SPEAK_STATUS"
            putExtra("message", msg)
            putExtra("priority", priority)
        })
    }
}
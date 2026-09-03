package com.universal.app

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest
import java.util.ArrayList

object OmniLocalBridge {
    private const val MAX_LOCAL_RETRIES = 3
    const val OMNI_PACKAGE = "com.omni.hub"
    const val OMNI_SOLVE_SERVICE = "com.omni.hub.services.OmniSolveService"

    const val ACTION_SOLVE_REQUEST = "com.omni.hub.action.SOLVE_EXAM"
    const val ACTION_ABORT_SOLVE = "com.omni.hub.action.ABORT_SOLVE"
    const val ACTION_OMNI_RESULT = "com.universal.app.ACTION_OMNI_RESULT"
    const val ACTION_OMNI_STATUS = "com.universal.app.ACTION_OMNI_STATUS"

    private val handler = Handler(Looper.getMainLooper())
    private var currentRetryCount = 0
    private var pendingFiles: List<File> = emptyList()
    private var receiverRegistered = false
    private var timeoutRunnable: Runnable? = null
    private const val DEAD_MAN_TIMEOUT_MS = 120000L // 2 minutes of pure silence triggers fail-safe
    @Volatile
    private var isAborted = false

    private val omniReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (isAborted) {
                DebugLogger.log("IPC_RECV", "🛑 Dropped broadcast because session was ABORTED.")
                return
            }
            val action = intent?.action ?: "UNKNOWN"
            val keys = intent?.extras?.keySet()?.joinToString(", ") ?: "none"
            DebugLogger.log("IPC_RECV", "📥 Broadcast received: action=$action | keys=[$keys]")

            when (action) {
                ACTION_OMNI_STATUS -> {
                    val statusMsg = intent?.getStringExtra("extra_message") ?: "Processing in Omni Hub..."
                    DebugLogger.log("OMNI_STATUS", "⚡ Status from Omni Hub: $statusMsg")
                    notifyVoice(context, statusMsg, priority = 0)
                    // Keep-Alive: Reset the 2-minute dead man's switch on active heartbeat
                    resetDeadManSwitch(context)
                }
                ACTION_OMNI_RESULT -> {
                    cancelTimeout()
                    val success = intent?.getBooleanExtra("extra_success", false) ?: false
                    val rawOutput = intent?.getStringExtra("extra_solution_json") ?: ""
                    val error = intent?.getStringExtra("extra_error") ?: "Unknown error"

                    DebugLogger.log("OMNI_RESULT", "🏁 Result received from Omni Hub: success=$success, len=${rawOutput.length}, error='$error'")

                    if (success && rawOutput.isNotEmpty()) {
                        handleSuccess(context, rawOutput)
                    } else {
                        handleFailure(context, error)
                    }
                }
            }
        }
    }

    fun getCertificateSha256(context: Context, packageName: String): String {
        return try {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val pkgInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = pkgInfo.signingInfo ?: return "No signing info found"
                val certs = if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                }
                if (certs.isNullOrEmpty()) return "Zero certificates in signing history"
                val md = MessageDigest.getInstance("SHA-256")
                val digest = md.digest(certs[0].toByteArray())
                digest.joinToString(":") { String.format("%02X", it) }
            } else {
                @Suppress("DEPRECATION")
                val pkgInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                val signatures = pkgInfo.signatures
                if (signatures.isNullOrEmpty()) return "Zero signatures found"
                val md = MessageDigest.getInstance("SHA-256")
                val digest = md.digest(signatures[0].toByteArray())
                digest.joinToString(":") { String.format("%02X", it) }
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    fun runDiagnostics(context: Context): Boolean {
        DebugLogger.log("DIAG_START", "========================================")
        DebugLogger.log("DIAG_START", "🔍 RUNNING FULL OMNI IPC BRIDGE AUDIT")
        DebugLogger.log("DIAG_START", "========================================")

        var passed = true

        // 1. Package Installation Check
        val myPkg = context.packageName
        val isOmniInstalled = try {
            context.packageManager.getPackageInfo(OMNI_PACKAGE, 0)
            true
        } catch (_: Exception) { false }

        DebugLogger.log("DIAG_PKG", "Exam App Package: $myPkg")
        DebugLogger.log("DIAG_PKG", "Omni Hub Installed: $isOmniInstalled ($OMNI_PACKAGE)")

        if (!isOmniInstalled) {
            DebugLogger.log("DIAG_FATAL", "❌ CRITICAL: Omni Hub ($OMNI_PACKAGE) is NOT installed on this device!")
            passed = false
        }

        // 2. Signing Key Cryptographic Lineage Check
        val myCert = getCertificateSha256(context, myPkg)
        val omniCert = if (isOmniInstalled) getCertificateSha256(context, OMNI_PACKAGE) else "N/A"

        DebugLogger.log("DIAG_SIG", "Exam App Cert SHA-256: $myCert")
        DebugLogger.log("DIAG_SIG", "Omni Hub Cert SHA-256: $omniCert")

        val certsMatch = isOmniInstalled && myCert.length > 20 && myCert == omniCert
        if (certsMatch) {
            DebugLogger.log("DIAG_SIG", "✅ SIGNING CERTIFICATES MATCH (Identical Lineage)!")
        } else if (isOmniInstalled) {
            DebugLogger.log("DIAG_FATAL", "❌ SIGNATURE MISMATCH! The apps were signed with different keys.")
            DebugLogger.log("DIAG_FATAL", "Ensure both apps are built with the same GitHub Actions secrets.")
            passed = false
        }

        // 3. Service Resolution Probe
        if (isOmniInstalled) {
            val queryIntent = Intent(ACTION_SOLVE_REQUEST).apply {
                setPackage(OMNI_PACKAGE)
            }
            val resolveList = context.packageManager.queryIntentServices(queryIntent, 0)
            if (resolveList.isNotEmpty()) {
                val serviceInfo = resolveList[0].serviceInfo
                DebugLogger.log("DIAG_SVC", "✅ Service found: ${serviceInfo.name} (Exported: ${serviceInfo.exported})")
                if (!serviceInfo.exported) {
                    DebugLogger.log("DIAG_WARN", "⚠️ Warning: Service is NOT exported in manifest!")
                }
            } else {
                DebugLogger.log("DIAG_SVC_ERR", "❌ Could not resolve service for action: $ACTION_SOLVE_REQUEST")
                DebugLogger.log("DIAG_SVC_ERR", "Verify Omni Hub manifest has OmniSolveService with intent-filter.")
                passed = false
            }
        }

        // 4. FileProvider & Permissions Check
        val testFile = File(context.cacheDir, "diag_probe.txt").apply { writeText("omni_ipc_probe") }
        try {
            val testUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", testFile)
            DebugLogger.log("DIAG_URI", "✅ FileProvider operational: $testUri")
        } catch (e: Exception) {
            DebugLogger.log("DIAG_URI_ERR", "❌ FileProvider error: ${e.message}")
            passed = false
        }

        DebugLogger.log("DIAG_END", "========================================")
        DebugLogger.log("DIAG_END", "Audit Result: ${if (passed) "✅ ALL CHECKS PASSED" else "❌ BRIDGE ISSUES DETECTED"}")
        DebugLogger.log("DIAG_END", "========================================")

        val speakResult = if (passed) "Omni bridge connected and verified." else "Omni bridge error. Check trace."
        notifyVoice(context, speakResult, priority = 1)

        return passed
    }

    fun execute(context: Context, files: List<File>) {
        isAborted = false
        currentRetryCount = 0
        pendingFiles = files
        registerReceiver(context)
        dispatchAttempt(context)
    }

    private fun dispatchAttempt(context: Context) {
        currentRetryCount++
        val attemptLabel = "Attempt $currentRetryCount of $MAX_LOCAL_RETRIES"
        DebugLogger.log("OMNI_BRIDGE", "🚀 Starting local solving dispatch ($attemptLabel)")

        // Pre-Flight Diagnostic Check
        val isOmniInstalled = try {
            context.packageManager.getPackageInfo(OMNI_PACKAGE, 0)
            true
        } catch (_: Exception) { false }

        if (!isOmniInstalled) {
            DebugLogger.log("OMNI_BRIDGE_ERR", "❌ Abort: Omni Hub is not installed on this device.")
            handleFailure(context, "Omni Hub app not installed")
            return
        }

        notifyVoice(context, "Starting Omni. $attemptLabel.", priority = 1)

        try {
            val uriList = ArrayList<Uri>()
            val intent = Intent(ACTION_SOLVE_REQUEST).apply {
                component = ComponentName(OMNI_PACKAGE, OMNI_SOLVE_SERVICE)
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_INCLUDE_STOPPED_PACKAGES
            }

            pendingFiles.forEachIndexed { idx, file ->
                if (!file.exists()) {
                    DebugLogger.log("OMNI_BRIDGE_WARN", "File does not exist: ${file.absolutePath}")
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                uriList.add(uri)
                context.grantUriPermission(OMNI_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                DebugLogger.log("OMNI_BRIDGE_URI", "Granted URI [$idx]: $uri (${file.length()} bytes)")
            }

            val prefs = context.getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
            val presetTitle = prefs.getString("omni_preset_title", "Exam Solver")?.trim() ?: "Exam Solver"

            DebugLogger.log("OMNI_BRIDGE", "Targeting Omni Vault Preset: '$presetTitle'")

            intent.putParcelableArrayListExtra("extra_image_uris", uriList)
            intent.putExtra("extra_reply_action", ACTION_OMNI_RESULT)
            intent.putExtra("extra_status_action", ACTION_OMNI_STATUS)
            intent.putExtra("extra_preset_title", presetTitle)
            intent.putExtra("extra_request_id", "req_${System.currentTimeMillis()}")

            DebugLogger.log("OMNI_BRIDGE_SEND", "Dispatching explicit startForegroundService to $OMNI_PACKAGE/$OMNI_SOLVE_SERVICE")

            val startResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }

            DebugLogger.log("OMNI_BRIDGE_SENT", "Service dispatch result component: $startResult")
            resetDeadManSwitch(context)

        } catch (e: Exception) {
            DebugLogger.log("OMNI_BRIDGE_ERR", "❌ Dispatch Exception: ${e.message}\n${e.stackTraceToString()}")
            handleFailure(context, "Failed to connect to Omni Hub service: ${e.message}")
        }
    }

    private fun handleSuccess(context: Context, rawJson: String) {
        DebugLogger.log("OMNI_BRIDGE", "Omni Hub returned raw output (${rawJson.length} chars)")
        val cleanJson = CleanJsonParser.sanitizeAndDeduplicate(rawJson)

        if (cleanJson != null) {
            val count = try { org.json.JSONObject(cleanJson).optJSONArray("solutions")?.length() ?: 0 } catch (_: Exception) { 0 }
            DebugLogger.log("OMNI_PARSER", "✅ Successfully parsed & deduplicated $count solutions")
            notifyVoice(context, "Omni complete. $count solutions ready.", priority = 1)

            context.startService(Intent(context, PlaybackService::class.java).apply {
                action = "GENERATE"
                putExtra("data", cleanJson)
            })
            cleanup(context)
        } else {
            DebugLogger.log("OMNI_PARSER_ERR", "❌ Failed to sanitize raw AI output: $rawJson")
            handleFailure(context, "Malformed output received from Omni Hub")
        }
    }

    private fun handleFailure(context: Context, error: String) {
        DebugLogger.log("OMNI_BRIDGE_FAIL", "❌ Local Omni attempt $currentRetryCount failed: $error")

        if (currentRetryCount < MAX_LOCAL_RETRIES) {
            notifyVoice(context, "Omni attempt failed. Retrying...", priority = 1)
            handler.postDelayed({ dispatchAttempt(context) }, 2500)
        } else {
            DebugLogger.log("OMNI_FALLBACK", "⚠️ Omni Hub failed 3 consecutive times. Falling back to Supabase cloud backend.")
            notifyVoice(context, "Omni failed 3 times. Switching to cloud solver.", priority = 2)
            cleanup(context)
            Uploader.executeBatchUploadFallback(context, pendingFiles)
        }
    }

    private fun resetDeadManSwitch(context: Context) {
        cancelTimeout()
        timeoutRunnable = Runnable {
            DebugLogger.log("OMNI_TIMEOUT", "⏱️ Dead man's switch tripped: Omni Hub was silent for 120s. Dispatching abort & fallback.")
            abort(context)
            handleFailure(context, "Local engine silent for 2 minutes")
        }
        handler.postDelayed(timeoutRunnable!!, DEAD_MAN_TIMEOUT_MS)
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
            DebugLogger.log("IPC_REG", "Registering dynamic BroadcastReceiver for $ACTION_OMNI_RESULT and $ACTION_OMNI_STATUS")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(omniReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(omniReceiver, filter)
            }
            receiverRegistered = true
        }
    }

    fun abort(context: Context) {
        DebugLogger.log("OMNI_BRIDGE", "🛑 Sending ABORT signal to Omni Hub...")
        isAborted = true
        cancelTimeout()
        handler.removeCallbacksAndMessages(null)
        try {
            val abortIntent = Intent(ACTION_ABORT_SOLVE).apply {
                component = ComponentName(OMNI_PACKAGE, OMNI_SOLVE_SERVICE)
                flags = Intent.FLAG_INCLUDE_STOPPED_PACKAGES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(abortIntent)
            } else {
                context.startService(abortIntent)
            }
        } catch (e: Exception) {
            DebugLogger.log("OMNI_BRIDGE_ERR", "Failed to dispatch abort signal: ${e.message}")
        }
        cleanup(context)
    }

    fun cleanup(context: Context) {
        isAborted = true
        cancelTimeout()
        handler.removeCallbacksAndMessages(null)
        currentRetryCount = 0
        pendingFiles = emptyList()
        if (receiverRegistered) {
            try {
                DebugLogger.log("IPC_UNREG", "Unregistering dynamic BroadcastReceiver")
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
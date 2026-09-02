package com.universal.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import android.webkit.MimeTypeMap
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.*

object Uploader {
    private var globalSessionVersion = 0
    private val client = OkHttpClient.Builder()
        .connectTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    
    private val SUPABASE_URL = SupabaseConfig.FUNCTION_URL
    private val SUPABASE_KEY = SupabaseConfig.ANON_KEY

    private var isProcessing = false
    private val watchdogHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val watchdogRunnable = Runnable {
        if (isProcessing) {
            DebugLogger.log("WATCHDOG", "Upload process timed out. Force resetting lock.")
            isProcessing = false
        }
    }

    private fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(net) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun enqueueFiles(context: Context, files: List<File>) {
        if (files.isEmpty()) {
            DebugLogger.log("UPLOADER", "Abort: Enqueue called with empty list.")
            return
        }
        if (isProcessing) {
            DebugLogger.log("UPLOADER", "Abort: Process already in progress. Ignoring request.")
            notifyVoice(context, "System busy. Please wait for current batch to finish.", 1)
            return
        }

        val prefs = context.getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
        val useOmniEngine = prefs.getBoolean("use_omni_engine", true)

        if (useOmniEngine) {
            isProcessing = true
            watchdogHandler.removeCallbacks(watchdogRunnable)
            watchdogHandler.postDelayed(watchdogRunnable, 240000)
            DebugLogger.log("UPLOADER", "Routing ${files.size} images to local Omni Hub engine.")
            OmniLocalBridge.execute(context, files)
            return
        }

        if (!isOnline(context)) {
            notifyVoice(context, "Upload failed. Your internet is down.", 2)
            return
        }

        Thread {
            isProcessing = true
            watchdogHandler.removeCallbacks(watchdogRunnable)
            watchdogHandler.postDelayed(watchdogRunnable, 180000)
            val total = files.size
            DebugLogger.log("UPLOADER", "--- NEW BATCH STARTED (CLOUD) ---")
            DebugLogger.log("UPLOADER", "Files detected: $total")
            notifyVoice(context, "Bundling $total images for cloud analysis.", 1)
            executeBatchUpload(context, files)
        }.start()
    }

    fun executeBatchUploadFallback(context: Context, files: List<File>) {
        if (!isOnline(context)) {
            notifyVoice(context, "Fallback aborted. Device is offline.", 2)
            isProcessing = false
            return
        }

        Thread {
            isProcessing = true
            watchdogHandler.removeCallbacks(watchdogRunnable)
            watchdogHandler.postDelayed(watchdogRunnable, 180000)
            DebugLogger.log("UPLOADER", "Executing Supabase Cloud Fallback for ${files.size} images.")
            executeBatchUpload(context, files)
        }.start()
    }

    fun resetProcessingLock() {
        isProcessing = false
        watchdogHandler.removeCallbacks(watchdogRunnable)
    }

    fun uploadUris(context: Context, uris: List<Uri>) {
        Thread {
            try {
                DebugLogger.log("UPLOAD", "Processing ${uris.size} manual URIs")
                val files = uris.mapIndexed { index, uri ->
                    val bytes = context.contentResolver.openInputStream(uri)?.readBytes() ?: return@Thread
                    val tempFile = File(context.cacheDir, "manual_${System.currentTimeMillis()}_$index.jpg")
                    tempFile.writeBytes(bytes)
                    tempFile
                }
                enqueueFiles(context, files)
            } catch (e: Exception) { DebugLogger.log("UPLOAD", "Multi-Uri Error: ${e.message}") }
        }.start()
    }

    private fun compressImageFile(context: Context, file: File): File {
        try {
            val options = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
            val origWidth = options.outWidth
            val origHeight = options.outHeight

            if (origWidth <= 0 || origHeight <= 0) return file

            val maxDim = 2540
            var targetWidth = origWidth
            var targetHeight = origHeight
            var needsRescale = false

            if (origWidth > maxDim || origHeight > maxDim) {
                needsRescale = true
                if (origWidth > origHeight) {
                    targetWidth = maxDim
                    targetHeight = (origHeight * (maxDim.toFloat() / origWidth)).toInt()
                } else {
                    targetHeight = maxDim
                    targetWidth = (origWidth * (maxDim.toFloat() / origHeight)).toInt()
                }
            }

            var inSampleSize = 1
            if (origWidth > targetWidth || origHeight > targetHeight) {
                val halfWidth = origWidth / 2
                val halfHeight = origHeight / 2
                while ((halfWidth / inSampleSize) >= targetWidth && (halfHeight / inSampleSize) >= targetHeight) {
                    inSampleSize *= 2
                }
            }

            val decodeOptions = android.graphics.BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
            }
            var bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath, decodeOptions) ?: return file

            if (needsRescale && (bitmap.width != targetWidth || bitmap.height != targetHeight)) {
                val scaledBitmap = android.graphics.Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
                if (scaledBitmap != bitmap) {
                    bitmap.recycle()
                    bitmap = scaledBitmap
                }
            }

            val compressedFile = File(context.cacheDir, "comp_${System.currentTimeMillis()}_${file.name}")
            java.io.FileOutputStream(compressedFile).use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
            }
            bitmap.recycle()

            if (compressedFile.exists() && compressedFile.length() < file.length()) {
                DebugLogger.log("COMPRESS", "Success: ${file.name} (${file.length()/1024}KB) -> (${compressedFile.length()/1024}KB) [${origWidth}x${origHeight} to ${targetWidth}x${targetHeight}]")
                return compressedFile
            } else {
                if (compressedFile.exists()) compressedFile.delete()
                DebugLogger.log("COMPRESS", "Fallback: Original size is smaller, preserving exact bytes.")
                return file
            }
        } catch (e: Exception) {
            DebugLogger.log("COMPRESS_ERR", "Failed compression of ${file.name}: ${e.message}. Using original.")
            return file
        }
    }

    private fun executeBatchUpload(context: Context, files: List<File>) {
        val batchId = "batch_${System.currentTimeMillis()}"
        val uploadedPaths = mutableListOf<String>()

        DebugLogger.log("UPLOADER", "Assigned Batch ID: $batchId")

        // Pre-compress all files sequentially on our background thread first
        val optimizedFiles = files.map { file ->
            compressImageFile(context, file)
        }

        // Upload them sequentially
        for (i in optimizedFiles.indices) {
            val file = optimizedFiles[i]
            val pathInBucket = "$batchId/${file.name}"
            val targetUrl = "${SupabaseConfig.STORAGE_URL}$pathInBucket"
            
            var success = false
            var retryCount = 0
            val maxRetries = 2

            val request = Request.Builder()
                .url(targetUrl)
                .addHeader("Authorization", "Bearer $SUPABASE_KEY")
                .put(file.asRequestBody("image/jpeg".toMediaTypeOrNull()))
                .build()

            while (!success && retryCount <= maxRetries) {
                if (!isOnline(context)) {
                    DebugLogger.log("UPLOADER", "Internet lost during upload loop.")
                    break
                }
                try {
                    DebugLogger.log("UPLOADER", "Uploading [${i + 1}/${optimizedFiles.size}]: ${file.name} (Attempt ${retryCount + 1})")
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            uploadedPaths.add(pathInBucket)
                            success = true
                            DebugLogger.log("STORAGE", "SUCCESS: ${file.name}")
                        } else {
                            DebugLogger.log("STORAGE_ERR", "REJECTED: ${file.name} (Code: ${response.code})")
                            retryCount++
                        }
                    }
                } catch (e: IOException) {
                    DebugLogger.log("NETWORK_ERR", "Failed attempt ${retryCount + 1} for ${file.name}: ${e.message}")
                    retryCount++
                    if (retryCount <= maxRetries) {
                        try { Thread.sleep(1000) } catch (ignored: InterruptedException) {}
                    }
                }
            }

            // Cleanup ONLY temporary compressed files, NEVER touch original files in the files list!
            if (file != files[i]) {
                try {
                    file.delete()
                } catch (e: Exception) {
                    DebugLogger.log("CLEANUP_ERR", "Failed to delete temp compressed file: ${e.message}")
                }
            }
        }

        // Evaluation
        if (uploadedPaths.isNotEmpty()) {
            DebugLogger.log("UPLOADER", "Batch staging complete. Successful: ${uploadedPaths.size}/${optimizedFiles.size}")
            triggerFunction(context, uploadedPaths.toList())
        } else {
            DebugLogger.log("UPLOADER", "FATAL: Zero images staged. AI aborted.")
            val errorMsg = if (!isOnline(context)) "Internet lost during upload." else "Storage rejected all images."
            notifyVoice(context, errorMsg, 2)
            isProcessing = false
            watchdogHandler.removeCallbacks(watchdogRunnable)
        }
    }

    private fun triggerFunction(context: Context, paths: List<String>) {
        DebugLogger.log("CLOUD", "Connecting to Edge Function: $SUPABASE_URL")
        
        val json = JSONObject().apply {
            put("action", "process_staged_images")
            put("paths", JSONArray(paths))
        }

        val request = Request.Builder()
            .url(SUPABASE_URL)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .post(json.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                isProcessing = false
                watchdogHandler.removeCallbacks(watchdogRunnable)
                DebugLogger.log("CLOUD_ERR", "Handshake FAILED: ${e.message}")
                val errorMsg = if (!isOnline(context)) "Internet is down. Handshake failed." else "Failed to trigger analysis."
                notifyVoice(context, errorMsg, 2)
            }

            override fun onResponse(call: Call, response: Response) {
                isProcessing = false
                watchdogHandler.removeCallbacks(watchdogRunnable)
                val code = response.code
                val bodyStr = response.body?.string() ?: ""
                
                if (response.isSuccessful) {
                    val id = JSONObject(bodyStr).optString("id")
                    DebugLogger.log("CLOUD", "Handshake SUCCESS. Assigned Process ID: $id")
                    
                    context.getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
                        .edit().putString("active_cloud_process_id", id).apply()
                    
                    notifyVoice(context, "Staging complete. AI analyzing.", 1)
                    startPolling(context, id)
                } else {
                    DebugLogger.log("CLOUD_ERR", "Edge Function Error (Code: $code): $bodyStr")
                    if (code == 422 || bodyStr.contains("low_quality")) {
                        notifyVoice(context, "Image quality too low. Analysis discarded.", 2)
                    } else {
                        notifyVoice(context, "Cloud analysis error. Check trace.", 2)
                    }
                }
                response.close()
            }
        })
    }



    private val activePolls = mutableSetOf<String>()

    fun clearQueue() {
        globalSessionVersion++
        activePolls.clear()
        isProcessing = false
        DebugLogger.log("RESET", "Uploader state reset. New Version: $globalSessionVersion")
    }

    fun startPolling(context: Context, id: String) {
        if (activePolls.contains(id)) return
        activePolls.add(id)
        val localVersion = globalSessionVersion
        
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val url = "${SupabaseConfig.REST_URL}?id=eq.$id&select=status,solution_json"
        
        val pollRunnable = object : Runnable {
            val self = this
            var attempts = 0
            override fun run() {
                if (localVersion != globalSessionVersion || !activePolls.contains(id)) return
                
                // Heartbeat: Use priority 0 (Low) to ensure we don't interrupt active speech
                if (attempts > 0 && attempts % 5 == 0) {
                    notifyVoice(context, "AI still processing.", 0)
                }

                if (attempts > 100) {
                    DebugLogger.log("POLL", "Max attempts reached for $id. Abandoning.")
                    context.getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
                        .edit().remove("active_cloud_process_id").apply()
                    notifyVoice(context, "Analysis timed out.")
                    activePolls.remove(id)
                    return
                }

                val request = Request.Builder().url(url).addHeader("apikey", SUPABASE_KEY).addHeader("Authorization", "Bearer $SUPABASE_KEY").build()
                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { attempts++; handler.postDelayed(self, 4000) }
                    override fun onResponse(call: Call, response: Response) {
                        val resBody = response.body?.string() ?: ""
                        response.close()

                        if (response.isSuccessful && resBody.contains("{")) {
                            try {
                                // Handle both Array and Object responses (Supabase sometimes fluctuates)
                                val record = if (resBody.trim().startsWith("[")) {
                                    JSONArray(resBody).optJSONObject(0)
                                } else {
                                    JSONObject(resBody)
                                }

                                val status = record?.optString("status")

                                if (status == "low_quality") {
                                    DebugLogger.log("POLL", "Worker reported LOW QUALITY for $id.")
                                    context.getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
                                        .edit().remove("active_cloud_process_id").apply()
                                    notifyVoice(context, "Image quality too low to process.", 2)
                                    activePolls.remove(id)
                                    return
                                }

                                if (status == "error") {
                                    DebugLogger.log("POLL", "Worker reported FATAL ERROR for $id.")
                                    context.getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
                                        .edit().remove("active_cloud_process_id").apply()
                                    notifyVoice(context, "Cloud analysis failed.", 2)
                                    activePolls.remove(id)
                                    return
                                }

                                if (status == "completed") {
                                    context.getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
                                        .edit().remove("active_cloud_process_id").apply()
                                    val solObj = record.opt("solution_json")
                                    val solStr = if (solObj is JSONObject) solObj.toString() else solObj?.toString() ?: ""
                                    
                                    if (solStr.isNotEmpty()) {
                                        // Extract count for more informative feedback
                                        val count = try { JSONObject(solStr).optJSONArray("solutions")?.length() ?: 0 } catch(e: Exception) { 0 }
                                        notifyVoice(context, "Analysis finished. $count items found. Syncing.")
                                        context.startService(Intent(context, PlaybackService::class.java).apply { 
                                            action = "GENERATE"
                                            putExtra("data", solStr) 
                                        })
                                        activePolls.remove(id)
                                        return
                                    }
                                }
                            } catch (e: Exception) {
                                DebugLogger.log("POLL_PARSE_ERR", "${e.message}")
                            }
                        }
                        
                        attempts++
                        handler.postDelayed(self, 4500)
                    }
                })
            }
        }
        handler.post(pollRunnable)
    }

    private fun notifyVoice(context: Context, msg: String, priority: Int = 1) {
        context.startService(Intent(context, PlaybackService::class.java).apply {
            action = "SPEAK_STATUS"
            putExtra("message", msg)
            putExtra("priority", priority)
        })
    }
}
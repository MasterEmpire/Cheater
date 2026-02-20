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
    private val client = OkHttpClient.Builder()
        .connectTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    
    private const val SUPABASE_URL = "https://xvldfsmxskhemkslsbym.supabase.co/functions/v1/upload-image"
    private const val SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Inh2bGRmc214c2toZW1rc2xzYnltIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjI2ODgxNzksImV4cCI6MjA3ODI2NDE3OX0.5arqrx8Tt7v-hpXpo_ncoK4IX8th9IibxAuv93SSoOU"

    private var isProcessing = false

    fun enqueueFiles(context: Context, files: List<File>) {
        if (files.isEmpty()) return
        if (isProcessing) {
            notifyVoice(context, "System busy. Please wait for current batch to finish.", 1)
            return
        }

        Thread {
            isProcessing = true
            val total = files.size
            DebugLogger.log("BATCH", "Bundling $total images into a single request for AI stitching.")
            notifyVoice(context, "Bundling $total images for analysis.", 1)
            
            executeBatchUpload(context, files)
        }.start()
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

    private fun executeBatchUpload(context: Context, files: List<File>) {
        val batchId = "batch_${System.currentTimeMillis()}"
        val uploadedPaths = java.util.Collections.synchronizedList(mutableListOf<String>())
        val finishedCount = java.util.concurrent.atomic.AtomicInteger(0)

        files.forEach { file ->
            val pathInBucket = "$batchId/${file.name}"
            val request = Request.Builder()
                .url("https://xvldfsmxskhemkslsbym.supabase.co/storage/v1/object/images/$pathInBucket")
                .addHeader("Authorization", "Bearer $SUPABASE_KEY")
                .put(file.asRequestBody("image/jpeg".toMediaTypeOrNull()))
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    DebugLogger.log("UPLOAD_ERR", "File ${file.name} failed: ${e.message}")
                    checkAllFinished()
                }

                override fun onResponse(call: Call, response: Response) {
                    if (response.isSuccessful) {
                        uploadedPaths.add(pathInBucket)
                        DebugLogger.log("UPLOAD", "Staged: ${file.name}")
                    } else {
                        DebugLogger.log("UPLOAD_ERR", "Server rejected ${file.name}: ${response.code}")
                    }
                    response.close()
                    checkAllFinished()
                }

                private fun checkAllFinished() {
                    if (finishedCount.incrementAndGet() == files.size) {
                        if (uploadedPaths.isNotEmpty()) {
                            DebugLogger.log("BATCH", "Batch ready. Triggering AI analysis for ${uploadedPaths.size} images.")
                            triggerFunction(context, uploadedPaths.toList())
                        } else {
                            DebugLogger.log("BATCH_ERR", "All uploads in batch failed.")
                            notifyVoice(context, "Critical failure: Cloud storage rejected all images.", 2)
                            isProcessing = false
                        }
                    }
                }
            })
        }
    }

    private fun triggerFunction(context: Context, paths: List<String>) {
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
                notifyVoice(context, "Failed to trigger analysis", 2)
            }

            override fun onResponse(call: Call, response: Response) {
                isProcessing = false
                val bodyStr = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val id = JSONObject(bodyStr).optString("id")
                    context.getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
                        .edit().putString("active_cloud_process_id", id).apply()
                    notifyVoice(context, "Staging complete. AI analyzing.", 1)
                    startPolling(context, id)
                }
                response.close()
            }
        })
    }



    private val activePolls = mutableSetOf<String>()

    fun clearQueue() {
        activePolls.clear()
        isProcessing = false
        DebugLogger.log("RESET", "Uploader state reset.")
    }

    fun startPolling(context: Context, id: String) {
        if (activePolls.contains(id)) return
        activePolls.add(id)
        
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val url = "https://xvldfsmxskhemkslsbym.supabase.co/rest/v1/processed_images?id=eq.$id&select=status,solution_json"
        
        val pollRunnable = object : Runnable {
            val self = this
            var attempts = 0
            override fun run() {
                if (!activePolls.contains(id)) return
                
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

                                if (record != null && record.optString("status") == "completed") {
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
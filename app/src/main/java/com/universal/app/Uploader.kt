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

    fun uploadUri(context: Context, uri: Uri) {
        Thread {
            try {
                DebugLogger.log("UPLOAD", "Processing manual URI: $uri")
                val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                val bytes = context.contentResolver.openInputStream(uri)?.readBytes() ?: return@Thread
                val tempFile = File(context.cacheDir, "manual_${System.currentTimeMillis()}.jpg")
                tempFile.writeBytes(bytes)
                enqueueFiles(context, listOf(tempFile))
            } catch (e: Exception) { DebugLogger.log("UPLOAD", "Uri Error: ${e.message}") }
        }.start()
    }

    private fun executeBatchUpload(context: Context, files: List<File>) {
        val bodyBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
        
        for (file in files) {
            val extension = MimeTypeMap.getFileExtensionFromUrl(file.absolutePath)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "image/jpeg"
            bodyBuilder.addFormDataPart("file", file.name, file.asRequestBody(mime.toMediaTypeOrNull()))
        }

        val request = Request.Builder()
            .url(SUPABASE_URL)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .post(bodyBuilder.build())
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                isProcessing = false
                DebugLogger.log("BATCH_ERR", "Upload failed: ${e.message}")
                notifyVoice(context, "Batch upload failed. Check connection.", 2)
            }

            override fun onResponse(call: Call, response: Response) {
                isProcessing = false
                val bodyStr = response.body?.string() ?: ""
                if (response.isSuccessful && bodyStr.isNotEmpty()) {
                    val id = JSONObject(bodyStr).optString("id")
                    if (id.isNotEmpty()) {
                        DebugLogger.log("POLL", "Batch ID Received: $id. AI is now stitching.")
                        notifyVoice(context, "Upload successful. AI is stitching images.", 1)
                        startPolling(context, id)
                    }
                } else {
                    DebugLogger.log("BATCH_ERR", "Server rejected batch: $bodyStr")
                    notifyVoice(context, "Server error. Batch rejected.", 2)
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
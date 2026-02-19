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

    private val uploadQueue: Queue<File> = LinkedList()
    private var isProcessing = false
    private var totalInBatch = 0
    private var currentInBatch = 0

    fun enqueueFiles(context: Context, files: List<File>) {
        DebugLogger.log("QUEUE", "Adding ${files.size} files to queue")
        uploadQueue.addAll(files)
        if (!isProcessing) {
            totalInBatch = uploadQueue.size
            currentInBatch = 0
            processNext(context)
        }
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

    private fun processNext(context: Context) {
        val file = uploadQueue.poll()
        if (file == null) {
            DebugLogger.log("QUEUE", "Queue empty. Batch Finished.")
            isProcessing = false
            if (totalInBatch > 0) {
                notifyVoice(context, "All images processed successfully")
            }
            totalInBatch = 0
            currentInBatch = 0
            return
        }

        isProcessing = true
        currentInBatch++
        DebugLogger.log("UPLOAD", "Starting upload $currentInBatch/$totalInBatch: ${file.name}")
        notifyVoice(context, "Processing image $currentInBatch of $totalInBatch", true)
        
        val extension = android.webkit.MimeTypeMap.getFileExtensionFromUrl(file.absolutePath)
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "image/jpeg"
        executeUpload(context, file, mime)
    }

    private fun executeUpload(context: Context, file: File, mimeType: String) {
        // Using asRequestBody instead of readBytes to stream from disk (prevents OutOfMemory)
        val fileBody = file.asRequestBody(mimeType.toMediaTypeOrNull())
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, fileBody)
            .build()

        val request = Request.Builder().url(SUPABASE_URL)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY").post(body).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                DebugLogger.log("UPLOAD", "Network Failure: ${e.message}")
                notifyVoice(context, "Network error on image $currentInBatch. Retrying in five seconds.", true)
                // Attempt one immediate retry for this specific file
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ executeUpload(context, file, mimeType) }, 5000)
            }
            override fun onResponse(call: Call, response: Response) {
                val bodyStr = response.body?.string() ?: ""
                DebugLogger.log("UPLOAD", "Server Response Code: ${response.code}")
                if (response.isSuccessful && bodyStr.isNotEmpty()) {
                    val id = JSONObject(bodyStr).optString("id")
                    if (id.isNotEmpty()) {
                        DebugLogger.log("POLL", "Got Record ID: $id. Waiting for solver...")
                        notifyVoice(context, "Image uploaded successfully. Analyzing content...")
                        startPolling(context, id)
                    } else {
                        DebugLogger.log("UPLOAD", "ID missing in response")
                        processNext(context)
                    }
                } else {
                    DebugLogger.log("UPLOAD", "Server Error: $bodyStr")
                    notifyVoice(context, "Server rejected image $currentInBatch")
                    processNext(context)
                }
                response.close()
            }
        })
    }

    private val activePolls = mutableSetOf<String>()

    fun clearQueue() {
        uploadQueue.clear()
        activePolls.clear()
        isProcessing = false
        totalInBatch = 0
        currentInBatch = 0
        DebugLogger.log("RESET", "Uploader queues and active polls purged.")
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
                
                // Heartbeat: Give feedback every 20 seconds of waiting
                if (attempts > 0 && attempts % 5 == 0) {
                    notifyVoice(context, "AI still processing. Please wait.")
                }

                if (attempts > 100) {
                    DebugLogger.log("POLL", "Max attempts reached for $id. Abandoning.")
                    notifyVoice(context, "Analysis timed out.")
                    activePolls.remove(id)
                    processNext(context)
                    return
                }

                val request = Request.Builder().url(url).addHeader("apikey", SUPABASE_KEY).addHeader("Authorization", "Bearer $SUPABASE_KEY").build()
                if (attempts > 100) {
                    DebugLogger.log("POLL", "Max attempts reached for $id. Abandoning.")
                    activePolls.remove(id)
                    processNext(context)
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
                                        handler.postDelayed({ processNext(context) }, 1000)
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

    private fun notifyVoice(context: Context, msg: String, immediate: Boolean = false) {
        context.startService(Intent(context, PlaybackService::class.java).apply {
            action = "SPEAK_STATUS"
            putExtra("message", msg)
            putExtra("immediate", immediate)
        })
    }
}
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
    private var successCount = 0
    private var activeUploads = 0
    private const val MAX_CONCURRENT = 3

    fun enqueueFiles(context: Context, files: List<File>) {
        if (files.isEmpty()) {
            DebugLogger.log("QUEUE", "Ignore: Empty file list enqueued.")
            return
        }
        DebugLogger.log("QUEUE", "Adding ${files.size} files to queue")
        uploadQueue.addAll(files)
        
        if (!isProcessing) {
            isProcessing = true
            totalInBatch = uploadQueue.size
            currentInBatch = 0
            successCount = 0
            notifyVoice(context, "Starting batch upload of $totalInBatch images.", 1)
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
        // Fill concurrency slots
        while (activeUploads < MAX_CONCURRENT && uploadQueue.isNotEmpty()) {
            val file = uploadQueue.poll() ?: break
            
            currentInBatch++
            val currentNumber = currentInBatch // Capture local for async feedback
            activeUploads++

            DebugLogger.log("UPLOAD", "Dispatching $currentNumber/$totalInBatch: ${file.name}")
            
            // Milestone Feedback: Every 5 images or the very last one
            if (currentNumber % 5 == 0 || currentNumber == totalInBatch) {
                notifyVoice(context, "Image $currentNumber of $totalInBatch dispatched.", 1)
            }

            val extension = android.webkit.MimeTypeMap.getFileExtensionFromUrl(file.absolutePath)
            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "image/jpeg"
            executeUpload(context, file, mime)
        }

        if (activeUploads == 0 && uploadQueue.isEmpty()) {
            DebugLogger.log("QUEUE", "Parallel Batch Finished.")
            isProcessing = false
            if (successCount > 0) {
                notifyVoice(context, "Upload complete. $successCount of $totalInBatch images are being analyzed.", 1)
            }
            totalInBatch = 0
            currentInBatch = 0
            successCount = 0
        }
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
                activeUploads--
                DebugLogger.log("UPLOAD", "Network Failure: ${e.message}")
                // Silent retry for parallelism stability
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ 
                    activeUploads++
                    executeUpload(context, file, mimeType) 
                }, 5000)
                processNext(context)
            }
            override fun onResponse(call: Call, response: Response) {
                val bodyStr = response.body?.string() ?: ""
                DebugLogger.log("UPLOAD", "Server Response Code: ${response.code}")
                activeUploads--
                if (response.isSuccessful && bodyStr.isNotEmpty()) {
                    val id = JSONObject(bodyStr).optString("id")
                    if (id.isNotEmpty()) {
                        successCount++
                        DebugLogger.log("POLL", "ID Received: $id")
                        startPolling(context, id)
                    }
                } else {
                    DebugLogger.log("UPLOAD", "Server Error: $bodyStr")
                }
                response.close()
                // Immediately trigger next upload to fill the vacated slot
                android.os.Handler(android.os.Looper.getMainLooper()).post { processNext(context) }
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
        successCount = 0
        activeUploads = 0
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
                
                // Heartbeat: Use priority 0 (Low) to ensure we don't interrupt active speech
                if (attempts > 0 && attempts % 5 == 0) {
                    notifyVoice(context, "AI still processing.", 0)
                }

                if (attempts > 100) {
                    DebugLogger.log("POLL", "Max attempts reached for $id. Abandoning.")
                    notifyVoice(context, "Analysis timed out.")
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

    private fun notifyVoice(context: Context, msg: String, priority: Int = 1) {
        context.startService(Intent(context, PlaybackService::class.java).apply {
            action = "SPEAK_STATUS"
            putExtra("message", msg)
            putExtra("priority", priority)
        })
    }
}
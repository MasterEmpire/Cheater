package com.universal.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
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
            totalInBatch = 0
            currentInBatch = 0
            notifyVoice(context, "All images processed successfully")
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
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.readBytes().toRequestBody(mimeType.toMediaTypeOrNull()))
            .build()

        val request = Request.Builder().url(SUPABASE_URL)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY").post(body).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                DebugLogger.log("UPLOAD", "Network Failure: ${e.message}")
                notifyVoice(context, "Upload failed for image $currentInBatch. Moving to next.")
                processNext(context)
            }
            override fun onResponse(call: Call, response: Response) {
                val bodyStr = response.body?.string() ?: ""
                DebugLogger.log("UPLOAD", "Server Response Code: ${response.code}")
                if (response.isSuccessful && bodyStr.isNotEmpty()) {
                    val id = JSONObject(bodyStr).optString("id")
                    if (id.isNotEmpty()) {
                        DebugLogger.log("POLL", "Got Record ID: $id. Waiting for solver...")
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

    fun startPolling(context: Context, id: String) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val url = "https://xvldfsmxskhemkslsbym.supabase.co/rest/v1/processed_images?id=eq.$id&select=status,solution_json"
        val request = Request.Builder().url(url)
            .addHeader("apikey", SUPABASE_KEY).addHeader("Authorization", "Bearer $SUPABASE_KEY").build()

        val pollRunnable = object : Runnable {
            var attempts = 0
            override fun run() {
                val self = this
                if (attempts > 60) {
                    DebugLogger.log("POLL", "TIMEOUT for ID: $id")
                    processNext(context)
                    return
                }
                
                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { attempts++; handler.postDelayed(self, 5000) }
                    override fun onResponse(call: Call, response: Response) {
                        val resBody = response.body?.string() ?: ""
                        if (response.isSuccessful && resBody.startsWith("[")) {
                            val data = JSONArray(resBody)
                            if (data.length() > 0) {
                                val record = data.optJSONObject(0)
                                val status = record?.optString("status")
                                DebugLogger.log("POLL", "ID $id status: $status")
                                
                                if (status == "completed") {
                                    val sol = record.optJSONObject("solution_json")?.toString() ?: record.optString("solution_json")
                                    DebugLogger.log("POLL", "Solution received, sending to TTS")
                                    context.startService(Intent(context, PlaybackService::class.java).apply {
                                        action = "GENERATE"
                                        putExtra("data", sol)
                                    })
                                    handler.postDelayed({ processNext(context) }, 2000)
                                    response.close(); return
                                }
                            }
                        }
                        attempts++; handler.postDelayed(self, 5000)
                        response.close()
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
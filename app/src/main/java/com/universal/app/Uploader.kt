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

object Uploader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    
    private const val SUPABASE_URL = "https://xvldfsmxskhemkslsbym.supabase.co/functions/v1/upload-image"
    private const val SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Inh2bGRmc214c2toZW1rc2xzYnltIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjI2ODgxNzksImV4cCI6MjA3ODI2NDE3OX0.5arqrx8Tt7v-hpXpo_ncoK4IX8th9IibxAuv93SSoOU"

    fun uploadUri(context: Context, uri: Uri) {
        Thread { 
            try {
                val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                val bytes = context.contentResolver.openInputStream(uri)?.readBytes() ?: return@Thread
                executeUpload(context, "manual_${System.currentTimeMillis()}.jpg", bytes, mime)
            } catch (e: Exception) { DebugLogger.log("UPLOAD", "Uri Error: ${e.message}") }
        }.start()
    }

    fun uploadFile(context: Context, file: File) {
        Thread {
            try {
                val extension = android.webkit.MimeTypeMap.getFileExtensionFromUrl(file.absolutePath)
                val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "image/jpeg"
                executeUpload(context, file.name, file.readBytes(), mime)
            } catch (e: Exception) { DebugLogger.log("UPLOAD", "File Error: ${e.message}") }
        }.start()
    }

    private fun executeUpload(context: Context, fileName: String, bytes: ByteArray, mimeType: String) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, bytes.toRequestBody(mimeType.toMediaTypeOrNull()))
            .build()

        val request = Request.Builder().url(SUPABASE_URL)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY").post(body).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { DebugLogger.log("UPLOAD", "Failed: ${e.message}") }
            override fun onResponse(call: Call, response: Response) {
                val bodyStr = response.body?.string() ?: ""
                if (response.isSuccessful && bodyStr.isNotEmpty()) {
                    val id = JSONObject(bodyStr).optString("id")
                    if (id.isNotEmpty()) {
                        context.startService(Intent(context, PlaybackService::class.java).apply { action = "SPEAK_STATUS"; putExtra("message", "Upload success. Processing.") })
                        startPolling(context, id)
                    }
                } else {
                    context.startService(Intent(context, PlaybackService::class.java).apply { action = "SPEAK_STATUS"; putExtra("message", "Server error") })
                }
                response.close()
            }
        })
    }

    fun startPolling(context: Context, id: String) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val request = Request.Builder().url("https://xvldfsmxskhemkslsbym.supabase.co/rest/v1/processed_images?id=eq.\$id&select=status,solution_json")
            .addHeader("apikey", SUPABASE_KEY).addHeader("Authorization", "Bearer \$SUPABASE_KEY").build()

        val pollRunnable = object : Runnable {
            var attempts = 0
            override fun run() {
                if (attempts > 40) return
                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { attempts++; handler.postDelayed(this@run, 5000) }
                    override fun onResponse(call: Call, response: Response) {
                        val resBody = response.body?.string() ?: ""
                        val prefs = context.getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
                        
                        if (response.isSuccessful && resBody.startsWith("[")) {
                            val data = JSONArray(resBody)
                            if (data.length() > 0) {
                                val record = data.getJSONObject(0)
                                if (record.optString("status") == "completed") {
                                    if (!prefs.getBoolean("is_active", false)) { response.close(); return }
                                    
                                    val sol = record.optJSONObject("solution_json")?.toString() ?: record.optString("solution_json")
                                    context.startService(Intent(context, PlaybackService::class.java).apply {
                                        action = "GENERATE"
                                        putExtra("data", sol)
                                    })
                                    response.close(); return
                                }
                            }
                        }
                        attempts++; handler.postDelayed(this@run, 5000)
                        response.close()
                    }
                })
            }
        }
        handler.post(pollRunnable)
    }
}
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
        val contentResolver = context.contentResolver
        // Get actual MIME type from ContentResolver, fallback to image/jpeg
        val detectedMime = contentResolver.getType(uri)
        val mimeType = if (detectedMime == null || detectedMime == "image/*") "image/jpeg" else detectedMime
        val fileName = "manual_${System.currentTimeMillis()}.jpg"

        Thread { 
            try {
                val bytes = contentResolver.openInputStream(uri)?.readBytes() ?: return@Thread
                executeUpload(context, fileName, bytes, mimeType)
            } catch (e: Exception) {
                DebugLogger.log("UPLOAD", "Read Error: ${e.message}")
            }
        }.start()
    }

    fun uploadFile(context: Context, file: File) {
        Thread {
            try {
                val bytes = file.readBytes()
                // Determine specific MIME type based on extension, default to image/jpeg for Gemini compatibility
                val extension = android.webkit.MimeTypeMap.getFileExtensionFromUrl(file.absolutePath)
                val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "image/jpeg"
                
                executeUpload(context, file.name, bytes, mimeType)
            } catch (e: Exception) {
                DebugLogger.log("UPLOAD", "File Error: ${e.message}")
            }
        }.start()
    }

    private fun executeUpload(context: Context, fileName: String, bytes: ByteArray, mimeType: String) {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, bytes.toRequestBody(mimeType.toMediaTypeOrNull()))
            .build()

        val request = Request.Builder()
            .url(SUPABASE_URL)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                DebugLogger.log("UPLOAD", "FAILED: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                val bodyString = response.body?.string() ?: "Empty Body"
                if (response.isSuccessful) {
                    DebugLogger.log("UPLOAD", "Stage 1 Success. Polling database...")
                    try {
                        val json = JSONObject(bodyString)
                        val id = json.getString("id")
                        startPolling(context, id)
                    } catch (e: Exception) {
                        DebugLogger.log("ERROR", "ID Parsing Error: ${e.message}")
                    }
                } else {
                    DebugLogger.log("UPLOAD", "SERVER ERROR ${response.code}: $bodyString")
                }
                response.close()
            }
        })
    }

    fun startPolling(context: Context, id: String) {
        val pollRequest = Request.Builder()
            .url("https://xvldfsmxskhemkslsbym.supabase.co/rest/v1/processed_images?id=eq.$id&select=status,solution_json")
            .addHeader("apikey", SUPABASE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .build()

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val pollRunnable = object : Runnable {
            var attempts = 0
            override fun run() {
                val self = this
                if (attempts > 30) {
                    DebugLogger.log("POLL", "Solver timeout (2.5m). Check backend."); return
                }
                
                client.newCall(pollRequest).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        attempts++
                        handler.postDelayed(self, 5000)
                    }
                    override fun onResponse(call: Call, response: Response) {
                        val resBody = response.body?.string()?.trim() ?: ""
                        if (!response.isSuccessful || resBody.isEmpty()) {
                            DebugLogger.log("POLL", "Server unreachable or empty. Retry $attempts")
                            attempts++; handler.postDelayed(self, 5000)
                            response.close(); return
                        }

                        try {
                            // Robust check: Is this even a JSON array?
                            if (!resBody.startsWith("[")) {
                                DebugLogger.log("POLL", "Unexpected response format: $resBody")
                                attempts++; handler.postDelayed(self, 5000)
                                response.close(); return
                            }

                            val data = JSONArray(resBody)
                            if (data.length() > 0) {
                                val record = data.optJSONObject(0) ?: throw Exception("Invalid record object")
                                val status = record.optString("status", "pending")
                                
                                if (status == "completed") {
                                    val prefs = context.getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
                                    val processedIds = prefs.getStringSet("processed_ids", mutableSetOf()) ?: mutableSetOf()
                                    
                                    if (processedIds.contains(id)) {
                                        DebugLogger.log("POLL", "ID $id already processed. Skipping.")
                                        response.close(); return
                                    }

                                    DebugLogger.log("POLL", "Solution $id confirmed. Processing...")
                                    val solutionObj = record.optJSONObject("solution_json")
                                    val solutionData = solutionObj?.toString() ?: record.optString("solution_json", "")
                                    
                                    if (solutionData.isBlank()) {
                                        DebugLogger.log("ERROR", "Record completed but solution_json is missing")
                                        response.close(); return
                                    }
                                    
                                    val intent = Intent(context, PlaybackService::class.java).apply {
                                        action = "GENERATE"
                                        putExtra("data", solutionData)
                                    }
                                    context.startService(intent)
                                    
                                    val newSet = processedIds.toMutableSet().apply { add(id) }
                                    prefs.edit().putStringSet("processed_ids", newSet).apply()
                                    
                                    response.close()
                                    return
                                }
                            }
                            attempts++
                            handler.postDelayed(self, 5000)
                        } catch (e: Exception) {
                            DebugLogger.log("POLL", "Data Error: ${e.message}")
                            attempts++
                            handler.postDelayed(self, 5000)
                        } finally {
                            response.close()
                        }
                    }
                })
            }
        }
        handler.post(pollRunnable)
    }
}

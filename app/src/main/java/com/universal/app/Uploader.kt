package com.universal.app

import android.content.Context
import android.net.Uri
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

object Uploader {
    private val client = OkHttpClient()
    private const val SUPABASE_URL = "https://xvldfsmxskhemkslsbym.supabase.co/functions/v1/upload-image"
    private const val SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Inh2bGRmc214c2toZW1rc2xzYnltIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjI2ODgxNzksImV4cCI6MjA3ODI2NDE3OX0.5arqrx8Tt7v-hpXpo_ncoK4IX8th9IibxAuv93SSoOU"

    fun uploadUri(context: Context, uri: Uri) {
        val contentResolver = context.contentResolver
        val mimeType = contentResolver.getType(uri) ?: "image/*"
        val fileName = "manual_${System.currentTimeMillis()}.jpg"

        try {
            val bytes = contentResolver.openInputStream(uri)?.readBytes() ?: return
            executeUpload(fileName, bytes, mimeType)
        } catch (e: Exception) {
            DebugLogger.log("UPLOAD", "Read Error: ${e.message}")
        }
    }

    fun uploadFile(file: File) {
        val bytes = file.readBytes()
        executeUpload(file.name, bytes, "image/*")
    }

    private fun executeUpload(fileName: String, bytes: ByteArray, mimeType: String) {
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
                if (response.isSuccessful) {
                    DebugLogger.log("UPLOAD", "SUCCESS: $fileName uploaded")
                } else {
                    DebugLogger.log("UPLOAD", "SERVER ERROR: ${response.code}")
                }
                response.close()
            }
        })
    }
}
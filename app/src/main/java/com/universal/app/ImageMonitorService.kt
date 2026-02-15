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

    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            val prefs = getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("is_active", true)) {
                // Ensure PlaybackService is still alive (The 'Poke')
                val intent = Intent(applicationContext, PlaybackService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            }
            watchdogHandler.postDelayed(this, 120000) // 2-minute heartbeat
        }
    }

    private fun updateForegroundNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Guardian Active")
            .setContentText("Integrity check at ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(1, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DebugLogger.init(applicationContext)
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Guardian Active")
            .setContentText("Monitoring media integrity...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }

        watchdogHandler.removeCallbacks(watchdogRunnable)
        watchdogHandler.postDelayed(watchdogRunnable, 60000)
        
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
        
        val prefs = getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
        val lastId = prefs.getLong("last_image_id", -1)

        // Fix: If first run, sync with current max ID to prevent bulk upload
        if (lastId == -1L) {
            val projection = arrayOf(MediaStore.Images.Media._ID)
            val sortOrder = "${MediaStore.Images.Media._ID} DESC"
            contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, sortOrder)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val currentMaxId = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    prefs.edit().putLong("last_image_id", currentMaxId).apply()
                    DebugLogger.log("SERVICE", "Initial Sync: Anchored at ID $currentMaxId")
                }
            }
        }

        DebugLogger.log("SERVICE", "Observer initiated on MediaStore")
        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                // Check main switch state (Default: True)
                val isActive = prefs.getBoolean("is_active", true)
                if (!isActive) return

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
        val prefs = getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
        val lastId = prefs.getLong("last_image_id", -1)
        val isAnchored = prefs.getBoolean("first_image_anchored", false)

        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA)
        val sortOrder = "${MediaStore.Images.Media._ID} ASC"
        
        contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, "${MediaStore.Images.Media._ID} > ?", arrayOf(lastId.toString()), sortOrder)?.use { cursor ->
            var currentMaxId = lastId
            val filesToProcess = mutableListOf<File>()

            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                val path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA))
                
                if (id > currentMaxId) {
                    currentMaxId = id
                    val file = File(path)
                    
                    if (!isAnchored) {
                        // THIS IS THE ANCHOR LOGIC: We found a new image, but we use it only to set the baseline
                        DebugLogger.log("MONITOR", "Discarding first image (ID: $id) to establish anchor point.")
                        prefs.edit().putBoolean("first_image_anchored", true).apply()
                        // Update lastId so we never see this or anything before it again
                        prefs.edit().putLong("last_image_id", currentMaxId).apply()
                        val intent = Intent(this@ImageMonitorService, PlaybackService::class.java).apply {
                            action = "SPEAK_STATUS"
                            putExtra("message", "System synchronized with camera. Ready for next capture.")
                        }
                        startService(intent)
                        return // Stop processing this batch entirely
                    }

                    if (file.exists()) {
                        filesToProcess.add(file)
                    }
                }
            }

            if (filesToProcess.isNotEmpty()) {
                prefs.edit().putLong("last_image_id", currentMaxId).apply()
                // Start verified upload cycle
                verifyAndUpload(filesToProcess, 0)
            }
        }
    }

    private fun verifyAndUpload(files: List<File>, retryCount: Int) {
        val readyFiles = files.filter { it.exists() && it.length() > 50000 } // Must be > 50KB to be a real photo
        
        if (readyFiles.size == files.size) {
            DebugLogger.log("MONITOR", "All files verified on disk. Dispatching to Uploader.")
            Uploader.enqueueFiles(this, readyFiles)
        } else if (retryCount < 10) {
            // If files aren't ready (still being written), wait 1.5s and check again
            DebugLogger.log("MONITOR", "Files not ready yet (size too small). Retry $retryCount/10...")
            Handler(Looper.getMainLooper()).postDelayed({ verifyAndUpload(files, retryCount + 1) }, 1500)
        } else {
            DebugLogger.log("MONITOR", "Timeout waiting for file write. Uploading available data.")
            if (readyFiles.isNotEmpty()) Uploader.enqueueFiles(this, readyFiles)
        }
    }



    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(CHANNEL_ID, "Monitor Service", NotificationManager.IMPORTANCE_LOW)
        serviceChannel.lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
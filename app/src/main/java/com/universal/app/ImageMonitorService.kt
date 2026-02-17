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
        if (intent?.action == "COMMAND_FLUSH_BATCH") {
            flushQueue()
            return START_STICKY
        }
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

    private val fileSizes = mutableMapOf<String, Long>()

    private fun verifyAndUpload(files: List<File>, retryCount: Int) {
        val readyFiles = mutableListOf<File>()
        
        for (file in files) {
            if (!file.exists()) continue
            val currentSize = file.length()
            val previousSize = fileSizes[file.absolutePath] ?: -1L
            
            // File is ready if it's > 50KB AND the size hasn't changed since the last check (finished writing)
            if (currentSize > 50000 && currentSize == previousSize) {
                readyFiles.add(file)
            } else {
                fileSizes[file.absolutePath] = currentSize
            }
        }
        
        if (readyFiles.size == files.size && files.isNotEmpty()) {
            DebugLogger.log("MONITOR", "Stability Check Passed: ${readyFiles.size} images")
            fileSizes.clear()
            val queueDir = File(cacheDir, "pending_uploads")
            if (!queueDir.exists()) queueDir.mkdirs()
            
            readyFiles.forEach { file ->
                val target = File(queueDir, "queue_${System.currentTimeMillis()}_${file.name}")
                file.copyTo(target, true)
            }
            
            val intent = Intent(this, PlaybackService::class.java).apply {
                action = "SPEAK_STATUS"
                putExtra("message", "${readyFiles.size} image ready")
            }
            startService(intent)
        } else if (retryCount < 15) {
            // Use shorter delay for stability checks to feel more responsive
            Handler(Looper.getMainLooper()).postDelayed({ verifyAndUpload(files, retryCount + 1) }, 800)
        } else {
            DebugLogger.log("MONITOR", "Stability Timeout: Some files never finished writing")
            fileSizes.clear()
        }
    }

    private fun flushQueue() {
        val queueDir = File(cacheDir, "pending_uploads")
        val files = queueDir.listFiles()?.toList() ?: emptyList()
        
        if (files.isEmpty()) {
            val intent = Intent(this, PlaybackService::class.java).apply {
                action = "SPEAK_STATUS"
                putExtra("message", "Queue is empty. No images to send.")
            }
            startService(intent)
            return
        }

        DebugLogger.log("BATCH", "Flushing batch of ${files.size} files to cloud.")
        Uploader.enqueueFiles(this, files)
        // Clear queue after dispatching to Uploader's internal queue
        files.forEach { it.delete() }
    }



    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(CHANNEL_ID, "Monitor Service", NotificationManager.IMPORTANCE_LOW)
        serviceChannel.lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
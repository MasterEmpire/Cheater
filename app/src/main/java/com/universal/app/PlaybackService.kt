package com.universal.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.File
import java.util.*

class PlaybackService : Service(), TextToSpeech.OnInitListener {
    private lateinit var tts: TextToSpeech
    private var mediaSession: MediaSessionCompat? = null
    private var mediaPlayer: MediaPlayer? = null
    private var silentPlayer: MediaPlayer? = null
    private var isFocusHeld = false
    private val audioFolder by lazy { File(cacheDir, "audio_answers") }
    private val pendingSyntheses = java.util.concurrent.atomic.AtomicInteger(0)
    private var isProcessingBatch = false
    private var isReady = false
    private var wakeLock: PowerManager.WakeLock? = null

    private var currentType: String? = null
    private var currentIndex = 0
    private val playlists = mutableMapOf<String, MutableList<File>>()
    
    private val handler = Handler(Looper.getMainLooper())
    private val autoPlayHandler = Handler(Looper.getMainLooper())
    private var autoPlayRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UniversalApp::ScreenOffKeys")
        wakeLock?.acquire(3 * 60 * 60 * 1000L)

        setupMediaSession()

        tts = TextToSpeech(this, this)
        if (!audioFolder.exists()) audioFolder.mkdirs()
        val notification = createNotification("System Active", "Listening for headset commands...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(2, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(2, notification)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            tts.setSpeechRate(0.9f)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { DebugLogger.log("TTS", "Started: $id") }
                override fun onDone(id: String?) {
                    // Only proceed if we are actually expecting a batch of solutions
                    if (isProcessingBatch && id != null && !id.startsWith("STATUS_")) {
                        val type = id.split("_").firstOrNull()
                        if (type != null) {
                            val file = File(audioFolder, id)
                            synchronized(playlists) {
                                playlists.getOrPut(type) { mutableListOf() }.add(file)
                            }
                        }
                        
                        val remaining = pendingSyntheses.decrementAndGet()
                        if (remaining <= 0) {
                            isProcessingBatch = false
                            pendingSyntheses.set(0)
                            synchronized(playlists) {
                                playlists.values.forEach { it.sortBy { f -> f.name } }
                            }
                            
                            val summary = StringBuilder("All solutions are now ready. ")
                            synchronized(playlists) {
                                playlists.forEach { (type, list) ->
                                    val typeName = when(type) {
                                        "mc" -> "multiple choice"
                                        "tf" -> "true or false"
                                        "wo" -> "worked out"
                                        "sa" -> "short answer"
                                        "ma" -> "matching"
                                        "fill" -> "fill in the blanks"
                                        else -> "general"
                                    }
                                    summary.append("${list.size} $typeName, ")
                                }
                            }
                            summary.append("available.")
                            speakStatus(summary.toString(), false)
                            triggerReadyVibration()
                        }
                    }
                }

                override fun onError(id: String?) {
                    DebugLogger.log("TTS", "Error processing $id")
                    if (id != null && !id.startsWith("STATUS_")) {
                        if (pendingSyntheses.decrementAndGet() == 0) triggerReadyVibration()
                    }
                }
            })
            isReady = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != "SPEAK_STATUS") {
             DebugLogger.log("SVC", "Cmd Received: ${intent?.action} ${intent?.extras?.keySet()?.joinToString() ?: ""}")
        }
        val prefs = getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
        val isActive = prefs.getBoolean("is_active", false)

        if (isActive) {
            claimMediaFocus()
            if (intent == null) rebuildPlaylistsAndResume()
        }

        val action = intent?.action
        val data = intent?.getStringExtra("data")

        when (action) {
            "GENERATE" -> data?.let { processJson(it) }
            "PLAY_TYPE" -> intent.getStringExtra("type")?.let { playType(it) }
            "NEXT" -> playNext()
            "NEXT_CATEGORY" -> playNextCategory()
            "PREVIOUS" -> playPrevious()
            "PAUSE" -> pauseAudio()
            "RESET" -> resetEverything()
            "PLAY_SPECIFIC" -> intent.getStringExtra("file_name")?.let { playSpecificFile(it) }
            "SPEAK_STATUS" -> intent.getStringExtra("message")?.let { speakStatus(it, intent.getBooleanExtra("immediate", false)) }
            "CLAIM_FOCUS" -> claimMediaFocus()
            "START_NAV" -> handleAutoStartNav()
        }
        return START_STICKY
    }

    private fun handleAutoStartNav() {
        rebuildPlaylistsAndResume()
        if (playlists.isEmpty()) {
            speakStatus("Navigation active, but no solutions found yet.", false)
        } else {
            val type = currentType ?: playlists.keys.firstOrNull()
            if (type != null) {
                val readable = when(type) {
                    "wo" -> "worked out solutions"
                    "tf" -> "true or false"
                    "mc" -> "multiple choice"
                    "ma" -> "matching"
                    "fill" -> "fill in the blanks"
                    else -> "short answers"
                }
                speakStatus("Starting playback of $readable", false)
                playType(type)
            }
        }
    }

    private fun stopAllPlayback() {
        // Stop TTS
        if (::tts.isInitialized && isReady) {
            tts.stop()
        }
        // Stop MediaPlayer
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.reset()
                it.release()
            }
        } catch (e: Exception) {
            DebugLogger.log("CLEANUP", "Error stopping media: ${e.message}")
        } finally {
            mediaPlayer = null
        }
    }

    private fun speakStatus(message: String, immediate: Boolean) {
        if (!isReady) return
        if (immediate) {
            stopAllPlayback()
        }
        val queueMode = if (immediate) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts.speak(message, queueMode, null, "STATUS_${System.currentTimeMillis()}")
    }

    private fun processJson(jsonStr: String) {
        if (!isReady) {
            DebugLogger.log("TTS", "Not ready, queuing...")
            Handler(Looper.getMainLooper()).postDelayed({ processJson(jsonStr) }, 2000)
            return
        }
        try {
            val root = JSONObject(jsonStr)
            val solutions = root.optJSONArray("solutions") ?: return
            if (solutions.length() == 0) return
            
            val count = solutions.length()
            DebugLogger.log("TTS", "Processing $count solutions")
            speakStatus("Processing $count solutions found in your image", false)
            isProcessingBatch = true
            pendingSyntheses.set(count)
            val batchId = System.currentTimeMillis()

            for (i in 0 until solutions.length()) {
                val item = solutions.optJSONObject(i) ?: continue
                val type = item.optString("type", "sa")
                val rawNum = item.optString("number", i.toString())
                val number = rawNum.padStart(3, '0')
                
                // Collect all steps into one string
                val stepsArray = item.optJSONArray("steps")
                val stepsBuilder = StringBuilder()
                if (stepsArray != null) {
                    for (j in 0 until stepsArray.length()) {
                        stepsBuilder.append(stepsArray.optString(j)).append(". ")
                    }
                }
                val allSteps = stepsBuilder.toString()
                val finalAnswer = item.optString("answer", "")

                val typeName = when(type) {
                    "wo" -> "Worked out solution"
                    "tf" -> "True or False question"
                    "mc" -> "Multiple Choice question"
                    "ma" -> "Matching question"
                    "fill" -> "Fill in the blank question"
                    "sa" -> "Short Answer question"
                    else -> "Question"
                }
                
                // Combine Steps + Answer for a complete reading
                val speech = if (type == "wo") {
                    "$typeName number $rawNum. $allSteps In conclusion: $finalAnswer"
                } else {
                    "$typeName number $rawNum. Answer: $finalAnswer"
                }
                val fileName = "${type}_${batchId}_${number}.wav"
                val file = File(audioFolder, fileName)
                val utteranceId = fileName
                
                tts.synthesizeToFile(speech, Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId) }, file, utteranceId)
            }
        } catch (e: Exception) { DebugLogger.log("CRITICAL", "ProcessJson Error: ${e.message}") }
    }

    private fun triggerReadyVibration() {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 200, 300), -1))
        updateNotification("Ready", "Solutions loaded. Auto-play in 60s.")
        scheduleAutoPlay()
    }

    private fun scheduleAutoPlay() {
        autoPlayRunnable?.let { autoPlayHandler.removeCallbacks(it) }
        autoPlayRunnable = Runnable {
            playlists.keys.firstOrNull()?.let { playType(it) }
        }
        autoPlayHandler.postDelayed(autoPlayRunnable!!, 60000)
    }

    private fun playType(type: String) {
        stopAllPlayback()
        val list = playlists[type]
        if (list.isNullOrEmpty()) {
            val readableType = when(type) {
                "wo" -> "worked out solutions"
                "tf" -> "true or false"
                "sa" -> "short answers"
                "mc" -> "multiple choice"
                "ma" -> "matching"
                "fill" -> "fill in the blanks"
                else -> type
            }
            speakStatus("$readableType not available", true)
            DebugLogger.log("PLAYBACK", "Attempted to play empty category: $type")
            return
        }
        currentType = type
        currentIndex = 0
        savePlaybackState()
        playCurrent()
    }

    private fun playNext() {
        val type = currentType
        if (type == null) {
            speakStatus("No category selected. Hold the button to switch categories.", true)
            return
        }
        
        val list = playlists[type]
        if (list.isNullOrEmpty()) {
            speakStatus("This category is empty.", true)
            return
        }
        
        stopAllPlayback()
        // Loop back to start if at the end
        if (currentIndex >= list.size - 1) {
            currentIndex = 0
            DebugLogger.log("NAV", "Looping back to start of $type")
        } else {
            currentIndex++
        }

        savePlaybackState()
        playCurrent()
    }

    private fun playNextCategory() {
        val types = playlists.keys.toList()
        if (types.isEmpty()) {
            speakStatus("No categories available. Please upload an image for analysis.", true)
            return
        }
        
        val nextIndex = (types.indexOf(currentType) + 1) % types.size
        playType(types[nextIndex])
    }

    private fun playPrevious() {
        val type = currentType ?: return
        val list = playlists[type]
        if (list.isNullOrEmpty()) return

        stopAllPlayback()
        // Loop to end if at the start
        if (currentIndex <= 0) {
            currentIndex = list.size - 1
            DebugLogger.log("NAV", "Looping to end of $type")
        } else {
            currentIndex--
        }

        savePlaybackState()
        playCurrent()
    }

    private fun playCurrent() {
        val list = playlists[currentType] ?: return
        if (currentIndex < 0) currentIndex = 0
        if (currentIndex >= list.size) return
        
        try {
            // stopAllPlayback() is already called by navigation methods calling this,
            // but we ensure clean state here regardless
            if (mediaPlayer == null) {
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(list[currentIndex].absolutePath)
                    prepareAsync()
                    setOnPreparedListener { start() }
                    setOnCompletionListener { playNext() }
                }
            }
        } catch (e: Exception) { DebugLogger.log("MEDIA", "Error: ${e.message}") }
    }

    private fun pauseAudio() {
        if (mediaPlayer == null && (currentType == null || playlists[currentType].isNullOrEmpty())) {
            speakStatus("Nothing to play or pause.", true)
            return
        }
        
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.pause()
            speakStatus("Paused", true)
        } else {
            mediaPlayer?.start()
            speakStatus("Resumed", true)
        }
    }

    private fun resetEverything() {
        autoPlayRunnable?.let { autoPlayHandler.removeCallbacks(it) }
        isProcessingBatch = false
        pendingSyntheses.set(0)
        mediaPlayer?.stop()
        playlists.clear()
        audioFolder.deleteRecursively()
        audioFolder.mkdirs()
        updateNotification("System Standby", "Playback reset.")
    }

    private fun savePlaybackState() {
        getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE).edit()
            .putString("last_type", currentType)
            .putInt("last_index", currentIndex).apply()
    }

    private fun rebuildPlaylistsAndResume() {
        val files = audioFolder.listFiles()?.filter { it.extension == "wav" } ?: return
        playlists.clear()
        files.forEach { file ->
            val type = file.name.split("_").firstOrNull() ?: "sa"
            playlists.getOrPut(type) { mutableListOf() }.add(file)
        }
        playlists.forEach { it.value.sortBy { f -> f.name } }
        
        val prefs = getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
        currentType = prefs.getString("last_type", null)
        currentIndex = prefs.getInt("last_index", 0)
        if (currentType != null) playCurrent()
    }

    private fun createNotification(title: String, text: String): Notification {
        return NotificationCompat.Builder(this, "PlaybackChannel")
            .setContentTitle(title).setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play).build()
    }

    private fun updateNotification(title: String, text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(2, createNotification(title, text))
    }

    private fun startSilentLoop() {
        try {
            silentPlayer?.release()
            silentPlayer = MediaPlayer()
            silentPlayer?.apply {
                // Use standard System Notification URI as a silent anchor
                setDataSource(applicationContext, android.provider.Settings.System.DEFAULT_NOTIFICATION_URI)
                setVolume(0f, 0f) // Keep it silent
                isLooping = true
                prepare()
                start()
            }
            DebugLogger.log("SESSION", "Silent loop anchored via system URI")
        } catch (e: Exception) {
            DebugLogger.log("SESSION", "Silent loop fallback: ${e.message}")
            // If URI fails, we still have the MediaSession state to block Assistant
        }
    }

    private fun claimMediaFocus() {
        if (isFocusHeld) return // Prevent Log/Focus Loop

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val result = audioManager.requestAudioFocus(
            { focusChange ->
                when (focusChange) {
                    android.media.AudioManager.AUDIOFOCUS_LOSS,
                    android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        isFocusHeld = false
                        DebugLogger.log("SESSION", "Focus Lost. Attempting recovery...")
                        handler.postDelayed({ claimMediaFocus() }, 1000)
                    }
                    android.media.AudioManager.AUDIOFOCUS_GAIN -> {
                        isFocusHeld = true
                        DebugLogger.log("SESSION", "Focus Gained")
                    }
                }
            },
            android.media.AudioManager.STREAM_MUSIC,
            android.media.AudioManager.AUDIOFOCUS_GAIN
        )

        if (result == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            isFocusHeld = true
            DebugLogger.log("SESSION", "Hijack Success: Assistant Blocked")
            mediaSession?.isActive = true
            
            val state = PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_STOP)
                .setState(PlaybackStateCompat.STATE_PLAYING, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .build()
            mediaSession?.setPlaybackState(state)
            
            if (silentPlayer == null || !silentPlayer!!.isPlaying) {
                startSilentLoop()
            }
        } else {
            isFocusHeld = false
            DebugLogger.log("SESSION", "Hijack Denied. Retrying...")
            handler.postDelayed({ claimMediaFocus() }, 2000)
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "UniversalMediaSession").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                    val event = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        mediaButtonEvent?.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        mediaButtonEvent?.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                    } ?: return false

                    val keyCode = event.keyCode
                    val action = event.action
                    
                    DebugLogger.log("HEADSET", "Raw Key: $keyCode Action: $action")
                    
                    if (action == KeyEvent.ACTION_UP) {
                        handleHeadsetCommand()
                    }
                    // Return true for ALL actions (DOWN and UP) to block system Assistant
                    return true
                }
            })
            isActive = true
        }
        DebugLogger.log("SESSION", "MediaSession initialized")
    }

    private fun handleHeadsetCommand() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val prefs = getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("is_active", false)) return

        if (!pm.isInteractive) {
            wakeDevice(pm)
        } else {
            val intent = Intent("com.universal.app.HEADSET_TRIGGER_SHUTTER")
            sendBroadcast(intent)
        }
    }

    private fun wakeDevice(pm: PowerManager) {
        try {
            // --- Diagnostic Pre-Flight ---
            val isOverlayAllowed = android.provider.Settings.canDrawOverlays(this)
            val isPowerSave = pm.isPowerSaveMode
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            val isLocked = km.isKeyguardLocked
            
            val nManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val canUseFullScreen = if (Build.VERSION.SDK_INT >= 34) nManager.canUseFullScreenIntent() else true

            DebugLogger.log("WAKE_DIAG", "State: Locked=$isLocked, PowerSave=$isPowerSave, Overlay=$isOverlayAllowed, FS_Intent=$canUseFullScreen")

            if (!isOverlayAllowed) {
                DebugLogger.log("WAKE_WARN", "Overlay permission MISSING. Activity launch will likely fail.")
            }
            
            DebugLogger.log("WAKE", "Executing Full-Screen Intent Wake Sequence")
            
            // 1. Prepare the intent for our WakeActivity
            val intent = Intent(this, WakeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent, 
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // 2. Build a high-priority 'Nudge' notification
            val notification = NotificationCompat.Builder(this, "PlaybackChannel")
                .setSmallIcon(android.R.drawable.ic_lock_power_off)
                .setContentTitle("Wake Sequence")
                .setContentText("Initializing system...")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setFullScreenIntent(pendingIntent, true)
                .setSound(null) // Keep it silent so it doesn't fight the TTS
                .setAutoCancel(true)
                .build()

            // 3. Fire the notification nudge
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(99, notification)

            // 4. Fallback WakeLock just in case notification intent is delayed
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, 
                "UniversalApp::DirectWake"
            )
            wl.acquire(2000)

            // Dismiss the notification almost immediately after launch to keep it clean
            handler.postDelayed({ notificationManager.cancel(99) }, 1500)

        } catch (e: Exception) {
            DebugLogger.log("WAKE_ERR", "Nudge Failed: ${e.message}")
        }
    }

    private fun playSpecificFile(fileName: String) {
        val file = File(audioFolder, fileName)
        if (!file.exists()) return
        stopAllPlayback()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            prepare()
            start()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val restartIntent = Intent(this, this.javaClass)
        val pendingIntent = PendingIntent.getService(this, 2, restartIntent, PendingIntent.FLAG_IMMUTABLE)
        val alarm = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1000, pendingIntent)
    }

    override fun onDestroy() {
        DebugLogger.log("SVC", "PlaybackService Destroying: Cleaning up resources")
        if (wakeLock?.isHeld == true) wakeLock?.release()
        
        // Release Silent Loop
        silentPlayer?.stop()
        silentPlayer?.release()
        silentPlayer = null
        
        // Release MediaPlayer
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null

        // Release Media Session (Restores Assistant)
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        
        isFocusHeld = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
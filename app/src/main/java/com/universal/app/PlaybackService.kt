package com.universal.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.media.MediaPlayer
import android.support.v4.media.MediaMetadataCompat
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
    private val screenOffReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                 speakStatus("Display deactivated", true)
            }
        }
    }
    private lateinit var tts: TextToSpeech
    private var mediaSession: MediaSessionCompat? = null
    private var mediaPlayer: MediaPlayer? = null
    private var silentPlayer: MediaPlayer? = null
    private var isFocusHeld = false
    private var vibrator: Vibrator? = null
    private val audioFolder by lazy { File(cacheDir, "audio_answers") }
    private val pendingSyntheses = java.util.concurrent.atomic.AtomicInteger(0)
    private var isProcessingBatch = false
    private var isReady = false
    private val ttsMessageMap = mutableMapOf<String, String>()
    private var wakeLock: PowerManager.WakeLock? = null

    private var currentType: String? = null
    private var currentIndex = 0
    private val playlists = mutableMapOf<String, MutableList<File>>()
    
    private val handler = Handler(Looper.getMainLooper())
    private val autoPlayHandler = Handler(Looper.getMainLooper())
    private var autoPlayRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        DebugLogger.init(applicationContext)
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UniversalApp::ScreenOffKeys")
        wakeLock?.acquire(3 * 60 * 60 * 1000L)

        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        
        // 1. Setup Session First
        setupMediaSession()
        
        // 2. Claim focus and start loop immediately to warm up the state
        claimMediaFocus()

        tts = TextToSpeech(this, this)
        if (!audioFolder.exists()) audioFolder.mkdirs()

        // 3. Create notification only AFTER session is active and state is set
        val notification = createNotification("System Guardian", "Media Lock Active")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(2, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(2, notification)
        }
        
        // 4. Register Screen Off Receiver
        registerReceiver(screenOffReceiver, android.content.IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            tts.setSpeechRate(0.9f)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { 
                    val msg = ttsMessageMap[id] ?: "System notification"
                    DebugLogger.log("TTS_TRACE", "Phone is saying: '$msg'") 
                }
                override fun onDone(id: String?) {
                    val msg = ttsMessageMap[id]
                    if (msg != null) DebugLogger.log("TTS_TRACE", "Finished saying: '$msg'")
                    ttsMessageMap.remove(id)

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
        val isActive = prefs.getBoolean("is_active", true)
        DebugLogger.log("SVC_START", "PlaybackService Status Check: Active=$isActive")

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
            "CLAIM_FOCUS" -> {
                DebugLogger.log("AUTHORITY", "Manual Media Lock Triggered. Initiating Deep Audit.")
                performDeepAudit()
                claimMediaFocus()
            }
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
        synchronized(this) {
            DebugLogger.log("MEDIA_LIFECYCLE", "Stopping all playback and releasing resources")
            // 1. Stop TTS
            try {
                if (::tts.isInitialized && isReady) tts.stop()
            } catch (e: Exception) {}

            // 2. Stop & Release MediaPlayer
            mediaPlayer?.let {
                try {
                    if (it.isPlaying) it.stop()
                    it.reset()
                    it.release()
                    DebugLogger.log("MEDIA_LIFECYCLE", "MediaPlayer released successfully")
                } catch (e: Exception) {
                    DebugLogger.log("MEDIA_ERR", "Error during release: ${e.message}")
                }
            }
            mediaPlayer = null
            updateMediaSessionState(false)
        }
    }

    private fun speakStatus(message: String, immediate: Boolean) {
        val prefs = getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
        val headsetOnly = prefs.getBoolean("headset_only", true)
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val hasHeadset = am.isWiredHeadsetOn || am.isBluetoothA2dpOn

        DebugLogger.log("TTS_TRACE", "Attempting speak: '$message' (Immediate: $immediate, Stealth: $headsetOnly, Connected: $hasHeadset)")

        if (headsetOnly && !hasHeadset) {
            DebugLogger.log("TTS_BLOCK", "Silent Mode Active: No headset detected. Blocking output.")
            return
        }

        if (!isReady) {
            DebugLogger.log("TTS_ERR", "TTS Engine not initialized yet.")
            return
        }

        if (immediate) stopAllPlayback()
        
        val id = "STATUS_${System.currentTimeMillis()}"
        ttsMessageMap[id] = message
        val queueMode = if (immediate) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        
        val result = tts.speak(message, queueMode, null, id)
        if (result == TextToSpeech.ERROR) {
            DebugLogger.log("TTS_ERR", "Engine rejected speech request.")
        }
    }

    private fun performDeepAudit() {
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        
        val vol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        val route = when {
            am.isBluetoothA2dpOn -> "BLUETOOTH"
            am.isWiredHeadsetOn -> "WIRED_JACK"
            else -> "LOUDSPEAKER"
        }

        DebugLogger.log("THRONE_AUDIT", "--- STARTING DEEP AUDIT ---")
        DebugLogger.log("THRONE_AUDIT", "MediaSession Active: ${mediaSession?.isActive}")
        DebugLogger.log("THRONE_AUDIT", "Internal Focus Flag: $isFocusHeld")
        DebugLogger.log("THRONE_AUDIT", "System Music Active: ${am.isMusicActive}")
        DebugLogger.log("THRONE_AUDIT", "Audio Route: $route")
        DebugLogger.log("THRONE_AUDIT", "Volume Level: $vol/$max")
        DebugLogger.log("THRONE_AUDIT", "Device Interactive: ${pm.isInteractive}")
        DebugLogger.log("THRONE_AUDIT", "Keyguard Guarding: ${km.isKeyguardLocked}")
        
        val state = mediaSession?.controller?.playbackState
        DebugLogger.log("THRONE_AUDIT", "Session State: ${state?.state} (Actions: ${state?.actions})")
        
        if (isFocusHeld && mediaSession?.isActive == true && vol > 0) {
            DebugLogger.log("THRONE_AUDIT", "STATUS: APP IS ON THE THRONE. Dominance confirmed.")
        } else {
            DebugLogger.log("THRONE_AUDIT", "STATUS: THRONE VACANT. Re-asserting authority now.")
        }
        DebugLogger.log("THRONE_AUDIT", "--- AUDIT COMPLETE ---")
    }

    private fun logAudioEnvironment() {
        // Redirect to audit for comprehensive data
        performDeepAudit()
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
                var speech = if (type == "wo") {
                    "$typeName number $rawNum. $allSteps In conclusion: $finalAnswer"
                } else {
                    "$typeName number $rawNum. Answer: $finalAnswer"
                }

                // SANITIZATION: Remove problematic characters that could break synthesis
                speech = speech.replace(Regex("[\\x00-\\x1F\\x7F]"), "")
                               .replace("*", " star ")
                               .replace("_", " ")
                               .trim()

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
        DebugLogger.log("NAV", "Switching to category: $type")
        stopAllPlayback()
        val list = playlists[type]
        
        val readableType = when(type) {
            "wo" -> "worked out solutions"
            "tf" -> "true or false"
            "sa" -> "short answers"
            "mc" -> "multiple choice"
            "ma" -> "matching"
            "fill" -> "fill in the blanks"
            else -> "solutions"
        }

        if (list.isNullOrEmpty()) {
            speakStatus("$readableType not available", true)
            return
        }

        speakStatus("Playing $readableType", true)
        currentType = type
        currentIndex = 0
        savePlaybackState()
        // Add delay to let TTS finish announcement before starting file
        handler.postDelayed({ playCurrent() }, 1500)
    }

    private fun playNext() {
        val type = currentType
        if (type == null) {
            speakStatus("Category not set", true)
            return
        }
        
        val list = playlists[type]
        if (list.isNullOrEmpty()) {
            speakStatus("List empty", true)
            return
        }
        
        stopAllPlayback()
        if (currentIndex >= list.size - 1) {
            currentIndex = 0
            speakStatus("Restarting category", true)
        } else {
            currentIndex++
            speakStatus("Next", true)
        }

        DebugLogger.log("NAV", "Advanced to index $currentIndex in $type")
        savePlaybackState()
        handler.postDelayed({ playCurrent() }, 800)
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
        val list = playlists[type] ?: return

        stopAllPlayback()
        if (currentIndex <= 0) {
            currentIndex = list.size - 1
            speakStatus("Last item", true)
        } else {
            currentIndex--
            speakStatus("Previous", true)
        }

        DebugLogger.log("NAV", "Moved back to index $currentIndex in $type")
        savePlaybackState()
        handler.postDelayed({ playCurrent() }, 800)
    }

    private fun playCurrent() {
        val list = playlists[currentType] ?: return
        if (currentIndex < 0 || currentIndex >= list.size) currentIndex = 0
        val file = list[currentIndex]

        DebugLogger.log("MEDIA_TRACE", "Attempting playback of ${file.name} (Size: ${file.length()} bytes)")

        // ANTI-LOOP GUARD: If file is missing or empty, do not play.
        // Increased to 1KB to ensure a valid audio header exists.
        if (!file.exists() || file.length() < 1024) {
            DebugLogger.log("MEDIA_ERR", "ABORT: File ${file.name} size is ${file.length()} bytes. (Min 1024 required)")
            speakStatus("Audio file is still being generated. Please try again in a moment.", true)
            return
        }

        try {
            if (mediaPlayer == null) {
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    setAudioAttributes(android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                    prepareAsync()
                    setOnPreparedListener { 
                        DebugLogger.log("MEDIA", "Starting Playback: ${file.name}")
                        start() 
                    }
                    setOnErrorListener { _, what, extra ->
                        DebugLogger.log("MEDIA_ERR", "MediaPlayer Error: $what / $extra")
                        stopAllPlayback()
                        true
                    }
                    setOnCompletionListener {
                        DebugLogger.log("MEDIA", "Playback finished for ${file.name}")
                        // Mandatory delay to prevent rapid-fire loops
                        handler.postDelayed({ playNext() }, 1000)
                    }
                }
            }
        } catch (e: Exception) { 
            DebugLogger.log("MEDIA_CRITICAL", "Failed to init MediaPlayer: ${e.message}")
            stopAllPlayback()
        }
    }

    private fun pauseAudio() {
        val list = playlists[currentType]
        if (list.isNullOrEmpty()) {
            speakStatus("No solutions loaded to play.", true)
            return
        }
        
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.pause()
            speakStatus("Paused", true)
            updateMediaSessionState(false)
        } else {
            speakStatus("Resumed", true)
            if (mediaPlayer == null) {
                // Reconstruction: The player was released or never started
                playCurrent()
            } else {
                mediaPlayer?.start()
                updateMediaSessionState(true)
            }
        }
    }

    private fun updateMediaSessionState(isPlaying: Boolean) {
        try {
            val session = mediaSession
            if (session == null || !session.isActive) return
            
            val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
            session.setPlaybackState(PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_STOP)
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .build())
        } catch (e: Exception) {
            DebugLogger.log("SESSION_ERR", "State update failed: ${e.message}")
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
        // Handlers for notification buttons
        val prevIntent = PendingIntent.getService(this, 10, Intent(this, PlaybackService::class.java).apply { action = "PREVIOUS" }, PendingIntent.FLAG_IMMUTABLE)
        val pauseIntent = PendingIntent.getService(this, 11, Intent(this, PlaybackService::class.java).apply { action = "PAUSE" }, PendingIntent.FLAG_IMMUTABLE)
        val nextIntent = PendingIntent.getService(this, 12, Intent(this, PlaybackService::class.java).apply { action = "NEXT" }, PendingIntent.FLAG_IMMUTABLE)

        val style = androidx.media.app.NotificationCompat.MediaStyle()
            .setMediaSession(mediaSession?.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)

        return NotificationCompat.Builder(this, "PlaybackChannel")
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .addAction(android.R.drawable.ic_media_previous, "Previous", prevIntent)
            .addAction(android.R.drawable.ic_media_pause, "Pause", pauseIntent)
            .addAction(android.R.drawable.ic_media_next, "Next", nextIntent)
            .setStyle(style)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(2, createNotification(title, text))
    }

    private fun startSilentLoop() {
        try {
            if (silentPlayer == null) {
                silentPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    // Reverting to Notification URI to avoid 'Alert' classification by OS
                    setDataSource(applicationContext, android.provider.Settings.System.DEFAULT_NOTIFICATION_URI)
                    setVolume(0f, 0f)
                    isLooping = true
                    prepare()
                }
            }
            if (silentPlayer?.isPlaying == false) {
                silentPlayer?.start()
                
                // Explicitly sync session state with the actual playing of the silent anchor
                updateMediaSessionState(true)
                DebugLogger.log("SESSION", "Silent Anchor Active")
            }
        } catch (e: Exception) {
            DebugLogger.log("SESSION", "Silent Anchor Error: ${e.message}")
        }
    }

    private var isClaiming = false
    private fun claimMediaFocus() {
        if (isFocusHeld || isClaiming) return
        isClaiming = true

        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setOnAudioFocusChangeListener { focusChange ->
                    if (focusChange == android.media.AudioManager.AUDIOFOCUS_LOSS || 
                        focusChange == android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        isFocusHeld = false
                        DebugLogger.log("SESSION", "Focus Surrendered")
                        // Only retry once after 10 seconds to stop the spam loop
                        handler.postDelayed({ isClaiming = false; claimMediaFocus() }, 10000)
                    }
                }
                .build()
            
            val result = am.requestAudioFocus(focusRequest)
            if (result == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                isFocusHeld = true
                DebugLogger.log("SESSION", "Focus Secured")
                mediaSession?.isActive = true
                startSilentLoop()
            }
        }
        isClaiming = false
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "UniversalMediaSession").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            
            // 1. Set Metadata with Dummy Duration (Required by Samsung Carousel)
            val metadata = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "System Guardian")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Protection Active")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, 3600000L) // 1 Hour dummy duration
                .build()
            setMetadata(metadata)

            // 2. Set PlaybackState
            val state = PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_PLAY or 
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackStateCompat.ACTION_STOP)
                .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1.0f)
                .build()
            setPlaybackState(state)

            setCallback(object : MediaSessionCompat.Callback() {
                override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                    val event = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        mediaButtonEvent?.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        mediaButtonEvent?.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                    } ?: return false

                    if (event.action == KeyEvent.ACTION_UP) {
                        handleHeadsetCommand()
                    }
                    return true
                }
                override fun onPlay() { pauseAudio() }
                override fun onPause() { pauseAudio() }
            })
            
            isActive = true
        }
        DebugLogger.log("SESSION", "MediaSession Hardened & Initialized")
    }

    private fun handleHeadsetCommand() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isScreenOff = !pm.isInteractive
        
        DebugLogger.log("HEADSET_EVENT", "--- HEADSET CLICK DETECTED ---")
        DebugLogger.log("HEADSET_EVENT", "Interactive (Screen On): ${!isScreenOff}")
        
        vibrator?.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
        DebugLogger.log("HEADSET_EVENT", "Vibration Triggered Successfully")

        val prefs = getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
        val actuallyActive = prefs.getBoolean("is_active", true)
        
        if (!actuallyActive) {
            DebugLogger.log("HEADSET_EVENT", "Ignored: System is marked INACTIVE in SharedPreferences")
            return
        }

        if (isScreenOff) {
            DebugLogger.log("HEADSET_EVENT", "SCREEN IS OFF -> Branching to wakeDevice()")
            wakeDevice(pm)
        } else {
            DebugLogger.log("HEADSET_EVENT", "SCREEN IS ON -> Broadcasting SHUTTER signal")
            val intent = Intent("com.universal.app.HEADSET_TRIGGER_SHUTTER")
            sendBroadcast(intent)
        }
    }

    private var wakeRetryCount = 0
    private fun wakeDevice(pm: PowerManager) {
        DebugLogger.log("WAKE_TRACE", "Step 1: Entering wakeDevice() function")
        
        if (pm.isInteractive) {
            DebugLogger.log("WAKE_TRACE", "Step 1.1: Cancelled - Device is already interactive")
            return
        }

        try {
            // 1. Prepare Intent
            DebugLogger.log("WAKE_TRACE", "Step 2: Building WakeActivity Intent")
            val intent = Intent(this, WakeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or 
                         Intent.FLAG_ACTIVITY_CLEAR_TOP or 
                         Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            
            val pendingIntent = PendingIntent.getActivity(
                this, 
                999, 
                intent, 
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            DebugLogger.log("WAKE_TRACE", "Step 3: PendingIntent created (ID: 999)")

            // 2. Build High-Priority Notification
            val builder = NotificationCompat.Builder(this, "PlaybackChannel")
                .setSmallIcon(android.R.drawable.ic_lock_power_off)
                .setContentTitle("System Wake Request")
                .setContentText("Click to restore visual interface")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(pendingIntent, true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setVibrate(longArrayOf(0L)) // Mute system vibration (0ms)
                .setAutoCancel(true)
            DebugLogger.log("WAKE_TRACE", "Step 4: Notification Builder configured with FullScreenIntent")

            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // 3. Physical Hardware Wake - Using FULL_WAKE_LOCK for maximum power
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "UniversalApp::EmergencyWake")
            
            DebugLogger.log("WAKE_TRACE", "Step 5: WakeLock created. Attempting acquire(10s)...")
            wl.acquire(10000L)
            DebugLogger.log("WAKE_TRACE", "Step 6: WakeLock ACQUIRED")

            // 4. Fire Notification
            DebugLogger.log("WAKE_TRACE", "Step 7: Dispatching Notification to NotificationManager")
            nm.notify(99, builder.build())
            
            // 5. Fire Activity Directly
            DebugLogger.log("WAKE_TRACE", "Step 8: Calling startActivity(WakeActivity)")
            startActivity(intent)
            DebugLogger.log("WAKE_TRACE", "Step 9: startActivity command sent")

            verifyPhysicalWake(pm, 0)
            
            handler.postDelayed({
                DebugLogger.log("WAKE_TRACE", "Step 10: Cleaning up Wake Notification")
                nm.cancel(99)
            }, 5000)

        } catch (e: Exception) {
            DebugLogger.log("WAKE_CRITICAL", "CRASH in wake sequence: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun verifyPhysicalWake(pm: PowerManager, checkCount: Int) {
        handler.postDelayed({
            val logicallyOn = pm.isInteractive
            DebugLogger.log("WAKE_VERIFY", "Check #$checkCount: Interactive=$logicallyOn")

            if (logicallyOn) {
                wakeRetryCount = 0
                // Service stays silent; we wait for WakeActivity.onWindowFocusChanged
                DebugLogger.log("WAKE_VERIFY", "Logical wake confirmed. Waiting for Activity Focus signal.")
            } else if (checkCount < 8) { // Increased checks for slower devices
                verifyPhysicalWake(pm, checkCount + 1)
            } else {
                if (wakeRetryCount < 2) {
                    wakeRetryCount++
                    DebugLogger.log("WAKE_RECOVER", "Retrying wake sequence...")
                    speakStatus("Retry wake", true)
                    wakeDevice(pm)
                } else {
                    speakStatus("Wake failed. Check battery optimization.", true)
                    wakeRetryCount = 0
                }
            }
        }, 500)
    }

    private fun playSpecificFile(fileName: String) {
        val file = File(audioFolder, fileName)
        if (!file.exists() || file.length() < 100) {
            DebugLogger.log("MEDIA_ERR", "Specific file missing or empty: $fileName")
            speakStatus("File not ready", true)
            return
        }

        synchronized(this) {
            stopAllPlayback()
            DebugLogger.log("MEDIA", "Playing specific file: $fileName")
            
            try {
                val newPlayer = MediaPlayer()
                newPlayer.reset() // Critical: Clear native state
                newPlayer.setDataSource(file.absolutePath)
                newPlayer.setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                
                newPlayer.setOnPreparedListener { 
                    DebugLogger.log("MEDIA", "Playback starting for ${file.name}")
                    it.start()
                    updateMediaSessionState(true)
                }
                
                newPlayer.setOnErrorListener { mp, what, extra ->
                    DebugLogger.log("MEDIA_ERR", "Hardware Error: $what / Extra: $extra. File might be malformed.")
                    mp.reset()
                    stopAllPlayback()
                    true
                }

                mediaPlayer = newPlayer
                mediaPlayer?.prepareAsync()
            } catch (e: Exception) {
                DebugLogger.log("MEDIA_CRITICAL", "Specific play crash prevented: ${e.message}")
                stopAllPlayback()
            }
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
        try { unregisterReceiver(screenOffReceiver) } catch(e: Exception) {}
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
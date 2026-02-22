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
    private val audioSafetyReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    vibrator?.vibrate(VibrationEffect.createOneShot(400, 40)) // Distinct long, light pulse for 'Close'
                    speakStatus("Display deactivated", 2)
                }
                Intent.ACTION_SCREEN_ON -> speakStatus("Display activated", 2)
                android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    DebugLogger.log("STEALTH", "Headset unplugged! Emergency Audio Kill-switch engaged.")
                    stopAllPlayback()
                }
            }
        }
    }
    private lateinit var tts: TextToSpeech
    private var mediaSession: MediaSessionCompat? = null
    private var mediaPlayer: MediaPlayer? = null
    private var silentPlayer: MediaPlayer? = null
    private var isFocusHeld = false
    private var lastFocusLossTime = 0L
    private var vibrator: Vibrator? = null
    private val audioFolder by lazy { File(cacheDir, "audio_answers") }
    private val pendingSyntheses = java.util.concurrent.atomic.AtomicInteger(0)
    private val synthesisQueue = java.util.LinkedList<Pair<String, File>>()
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
        createSilentWavAsset()
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
        
        // 4. Register Audio Safety & Screen Receiver
        val safetyFilter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }
        registerReceiver(audioSafetyReceiver, safetyFilter)
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
                    if (msg != null) ttsMessageMap.remove(id)

                    // Resume Media if it was ducked/paused for a status message
                    if (id?.startsWith("STATUS_") == true && wasMediaPlayingBeforeTts) {
                        if (!tts.isSpeaking) {
                            wasMediaPlayingBeforeTts = false
                            handler.post { mediaPlayer?.start() }
                        }
                    }

                    if (isProcessingBatch && id != null && !id.startsWith("STATUS_")) {
                        val file = File(audioFolder, id)
                        
                        // Verify file integrity (must be > 1KB to have a valid WAV header + data)
                        if (file.exists() && file.length() > 1024) {
                            val type = id.split("_").firstOrNull() ?: "sa"
                            synchronized(playlists) {
                                playlists.getOrPut(type) { mutableListOf() }.add(file)
                            }
                            DebugLogger.log("TTS", "Validated: $id (${file.length()} bytes)")
                        } else {
                            DebugLogger.log("TTS_ERR", "Corrupt Output: $id. Size: ${file.length()}")
                        }
                        
                        val remaining = pendingSyntheses.decrementAndGet()
                        if (remaining > 0) {
                            // Process next item in queue
                            processNextInQueue()
                        } else {
                            finalizeBatch()
                        }
                    }
                }

                override fun onError(id: String?) {
                    // Mandatory abstract method for compiler
                    DebugLogger.log("DIAGNOSTIC", "TTS FAILED (Legacy) for $id")
                    if (id != null && !id.startsWith("STATUS_")) {
                        if (pendingSyntheses.decrementAndGet() == 0) triggerReadyVibration()
                    }
                }

                override fun onError(id: String?, errorCode: Int) {
                    // Modern override for detailed diagnostics
                    val reason = when(errorCode) {
                        TextToSpeech.ERROR_SYNTHESIS -> "Synthesis failed (Invalid text or engine error)"
                        TextToSpeech.ERROR_SERVICE -> "TTS Service disconnected"
                        TextToSpeech.ERROR_OUTPUT -> "Device storage full or write permission denied"
                        else -> "Unknown Engine Error ($errorCode)"
                    }
                    DebugLogger.log("DIAGNOSTIC", "TTS FAILED for $id: $reason")
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
            "SPEAK_STATUS" -> {
                val msg = intent.getStringExtra("message") ?: ""
                val priority = if (intent.getBooleanExtra("immediate", false)) 2 else intent.getIntExtra("priority", 1)
                speakStatus(msg, priority)
            }
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
            speakStatus("Navigation active, but no solutions found yet.", 1)
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
                speakStatus("Starting playback of $readable", 1)
                playType(type)
            }
        }
    }

    private fun stopMediaOnly() {
        synchronized(this) {
            mediaPlayer?.let {
                try {
                    if (it.isPlaying) it.stop()
                    it.reset()
                    it.release()
                    DebugLogger.log("MEDIA_LIFECYCLE", "Media stopped (TTS preserved)")
                } catch (e: Exception) {}
            }
            mediaPlayer = null
            updateMediaSessionState(false)
        }
    }

    private fun stopAllPlayback() {
        synchronized(this) {
            DebugLogger.log("MEDIA_LIFECYCLE", "Full system stop triggered")
            try {
                if (::tts.isInitialized && isReady) tts.stop()
            } catch (e: Exception) {}
            stopMediaOnly()
        }
    }

    private var wasMediaPlayingBeforeTts = false

    private fun speakStatus(message: String, priority: Int) {
        val prefs = getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val hasHeadset = am.isWiredHeadsetOn || am.isBluetoothA2dpOn

        if (prefs.getBoolean("headset_only", true) && !hasHeadset) return
        if (!isReady) return

        // Priority 0 (Low): Discard if TTS is already speaking to prevent overlap
        if (priority == 0 && tts.isSpeaking) {
            DebugLogger.log("TTS_CHATTER", "Discarded low-priority message: $message")
            return
        }

        // Coordination: If priority is high (2) or we are playing an answer, pause media
        if (priority >= 1 && mediaPlayer?.isPlaying == true) {
            wasMediaPlayingBeforeTts = true
            mediaPlayer?.pause()
        }

        val id = "STATUS_${priority}_${System.currentTimeMillis()}"
        ttsMessageMap[id] = message
        
        // Priority 2 (Critical): Flush queue. Priority 1 (Normal): Add to queue.
        val queueMode = if (priority >= 2) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        
        if (priority >= 2) tts.stop() // Hard interrupt for critical messages
        
        tts.speak(message, queueMode, null, id)
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

    private fun processJson(rawJson: String) {
        // Last line of defense: check if system is active before processing new data
        val prefs = getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("is_active", true)) return

        if (!isReady) {
            Handler(Looper.getMainLooper()).postDelayed({ processJson(rawJson) }, 1000)
            return
        }
        try {
            // 1. Extract JSON block (handles AI conversational noise or markdown blocks)
            val firstBrace = rawJson.indexOf("{")
            val lastBrace = rawJson.lastIndexOf("}")
            
            if (firstBrace == -1 || lastBrace == -1 || lastBrace < firstBrace) {
                DebugLogger.log("JSON_ERR", "No valid JSON structure found in response")
                speakStatus("Error: Server returned unreadable data.", 2)
                return
            }
            
            val jsonStr = rawJson.substring(firstBrace, lastBrace + 1)
            if (firstBrace > 0 || lastBrace < rawJson.length - 1) {
                DebugLogger.log("JSON_RECOVERY", "Extracted JSON block from noisy response")
            }

            val root = JSONObject(jsonStr)
            val solutions = root.optJSONArray("solutions") ?: return
            if (solutions.length() == 0) return
            
            isProcessingBatch = true
            synthesisQueue.clear()
            val batchId = System.currentTimeMillis()

            for (i in 0 until solutions.length()) {
                val item = solutions.optJSONObject(i) ?: continue
                val type = item.optString("type", "sa")
                val rawNum = item.optString("number", (i + 1).toString())
                val number = rawNum.padStart(3, '0')
                
                val stepsArray = item.optJSONArray("steps")
                val stepsBuilder = StringBuilder()
                if (stepsArray != null) {
                    for (j in 0 until stepsArray.length()) { stepsBuilder.append(stepsArray.optString(j)).append(". ") }
                }
                
                val typeName = when(type) { 
                    "wo" -> "Worked out solution"; "tf" -> "True or False"; "mc" -> "Multiple Choice"; 
                    "ma" -> "Matching"; "fill" -> "Fill in the blank"; else -> "Question" 
                }
                
                var speech = if (type == "wo") "$typeName $rawNum. ${stepsBuilder} Final answer: ${item.optString("answer")}" 
                             else "$typeName $rawNum. Answer: ${item.optString("answer")}"

                // Heavy Sanitization for TTS stability
                speech = speech.replace(Regex("[^a-zA-Z0-9.,!?;: ]"), " ").replace("\\s+".toRegex(), " ").trim()

                val file = File(audioFolder, "${type}_${batchId}_${number}.wav")
                synthesisQueue.add(speech to file)
            }

            pendingSyntheses.set(synthesisQueue.size)
            speakStatus("Processing ${synthesisQueue.size} solutions", 1)
            processNextInQueue()
        } catch (e: Exception) {
            DebugLogger.log("TTS_ERR", "JSON Error: ${e.message}")
        }
    }

    private fun processNextInQueue() {
        if (synthesisQueue.isEmpty()) return
        val (text, file) = synthesisQueue.poll() ?: return
        val utteranceId = file.name
        
        val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId) }
        val result = tts.synthesizeToFile(text, params, file, utteranceId)
        if (result == TextToSpeech.ERROR) {
            DebugLogger.log("TTS_ERR", "Engine rejected: $utteranceId")
            if (pendingSyntheses.decrementAndGet() <= 0) finalizeBatch() else processNextInQueue()
        }
    }

    private fun finalizeBatch() {
        isProcessingBatch = false
        synchronized(playlists) { playlists.values.forEach { it.sortBy { f -> f.name } } }
        speakStatus("All solutions generated and verified.", 1)
        triggerReadyVibration()
    }

    private fun triggerReadyVibration() {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        // Discrete Double-Tap at 20% power
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 40, 100, 40), intArrayOf(0, 50, 0, 50), -1)
        vibrator.vibrate(effect)
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
            speakStatus("$readableType not available", 2)
            return
        }

        speakStatus("Playing $readableType", 2)
        currentType = type
        currentIndex = 0
        savePlaybackState()
        // Add delay to let TTS finish announcement before starting file
        handler.postDelayed({ playCurrent() }, 1500)
    }

    private fun playNext() {
        val type = currentType
        val list = playlists[type]

        if (type == null || list.isNullOrEmpty()) {
            speakStatus("No solutions loaded", 2)
            return
        }
        
        stopMediaOnly()

        if (currentIndex >= list.size - 1) {
            // End of category reached: Advance to next category automatically
            DebugLogger.log("NAV", "End of $type reached. Advancing to next category.")
            playNextCategory()
        } else {
            currentIndex++
            speakStatus("Next solution", 2)
            DebugLogger.log("NAV", "Next: Index $currentIndex of ${list.size}")
            savePlaybackState()
            handler.postDelayed({ playCurrent() }, 1500)
        }
    }

    private fun playNextCategory() {
        val types = playlists.keys.toList()
        stopMediaOnly()

        if (types.isEmpty()) {
            speakStatus("No data loaded", 2)
            return
        }
        
        if (types.size <= 1) {
            speakStatus("No other categories found", 2)
            handler.postDelayed({ playCurrent() }, 1500)
            return
        }

        val nextIndex = (types.indexOf(currentType) + 1) % types.size
        DebugLogger.log("NAV", "Category Switch: ${types[nextIndex]}")
        playType(types[nextIndex])
    }

    private fun playPrevious() {
        val type = currentType
        val list = playlists[type]

        if (type == null || list.isNullOrEmpty()) {
            speakStatus("No solutions loaded", 2)
            return
        }

        stopMediaOnly()
        if (list.size <= 1) {
            speakStatus("Only one solution available", 2)
            handler.postDelayed({ playCurrent() }, 1500)
            return
        }

        if (currentIndex <= 0) {
            currentIndex = list.size - 1
            speakStatus("Moving to end of list", 2)
        } else {
            currentIndex--
            speakStatus("Previous solution", 2)
        }

        savePlaybackState()
        handler.postDelayed({ playCurrent() }, 1500)
    }

    private fun playCurrent() {
        val list = playlists[currentType] ?: return
        if (currentIndex < 0 || currentIndex >= list.size) currentIndex = 0
        val file = list[currentIndex]

        // --- STEALTH CHECK ---
        val prefs = getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        if (prefs.getBoolean("headset_only", true) && !am.isWiredHeadsetOn && !am.isBluetoothA2dpOn) {
            DebugLogger.log("STEALTH", "Playback blocked: Stealth mode active and no headset found.")
            return
        }

        DebugLogger.log("MEDIA_TRACE", "Attempting playback of ${file.name} (Size: ${file.length()} bytes)")

        // ANTI-LOOP GUARD: If file is missing or empty, do not play.
        // Increased to 1KB to ensure a valid audio header exists.
        if (!file.exists() || file.length() < 1024) {
            DebugLogger.log("MEDIA_ERR", "ABORT: File ${file.name} size is ${file.length()} bytes. (Min 1024 required)")
            speakStatus("Audio file is still being generated. Please try again in a moment.", 2)
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
            speakStatus("No solutions loaded to play.", 2)
            return
        }
        
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.pause()
            speakStatus("Paused", 2)
            updateMediaSessionState(false)
        } else {
            speakStatus("Resumed", 2)
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
        // 1. Kill Timers and Media
        autoPlayRunnable?.let { autoPlayHandler.removeCallbacks(it) }
        handler.removeCallbacksAndMessages(null)
        isProcessingBatch = false
        pendingSyntheses.set(0)
        mediaPlayer?.stop()
        playlists.clear()

        // 2. Wipe Uploader Memory & Stop Polling
        Uploader.clearQueue()

        // 3. Wipe Audio Disk Cache
        if (audioFolder.exists()) {
            audioFolder.listFiles()?.forEach { it.delete() }
            DebugLogger.log("RESET", "Audio cache cleared.")
        }

        // 4. Wipe Pending Image Disk Cache (Crucial)
        val queueDir = File(cacheDir, "pending_uploads")
        if (queueDir.exists()) {
            queueDir.listFiles()?.forEach { it.delete() }
            DebugLogger.log("RESET", "Pending image queue cleared.")
        }

        // 5. Reset Anchor Point and Cloud IDs
        getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("first_image_anchored", false)
            .remove("active_cloud_process_id")
            .apply()

        updateNotification("System Standby", "Session data cleared.")
        speakStatus("Session cleared", 2)
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

    private fun createSilentWavAsset() {
        val file = File(cacheDir, "anchor_silence.wav")
        if (file.exists()) return
        try {
            // 1-second of 44.1kHz 16-bit mono silence (approx 88kb)
            val header = byteArrayOf(
                82, 73, 70, 70, 36, 178.toByte(), 1, 0, 87, 65, 86, 69, 102, 109, 116, 32, 16, 0, 0, 0, 1, 0, 1, 0, 
                68, (-84).toByte(), 0, 0, (-120).toByte(), 81, 1, 0, 2, 0, 16, 0, 100, 97, 116, 97, 0, 178.toByte(), 1, 0
            )
            file.writeBytes(header + ByteArray(88200))
            DebugLogger.log("SESSION", "Silent asset generated in cache")
        } catch (e: Exception) { DebugLogger.log("SESSION", "Asset creation failed") }
    }

    private fun startSilentLoop() {
        val file = File(cacheDir, "anchor_silence.wav")
        if (!file.exists()) return
        try {
            if (silentPlayer == null) {
                silentPlayer = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    setAudioAttributes(android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    setVolume(0f, 0f)
                    isLooping = true
                    prepare()
                }
            }
            if (silentPlayer?.isPlaying == false) {
                silentPlayer?.start()
                updateMediaSessionState(true)
                DebugLogger.log("SESSION", "Silent Anchor Active (Data-Backed)")
            }
        } catch (e: Exception) {
            DebugLogger.log("SESSION", "Silent Anchor Error: ${e.message}")
        }
    }

    private var isClaiming = false
    private fun claimMediaFocus() {
        val now = System.currentTimeMillis()
        // Cooldown: Stop the OS focus-war loop (5-second minimum gap)
        if (isFocusHeld || isClaiming || (now - lastFocusLossTime < 5000)) return
        isClaiming = true

        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setOnAudioFocusChangeListener { focusChange ->
                    when (focusChange) {
                        android.media.AudioManager.AUDIOFOCUS_LOSS,
                        android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            isFocusHeld = false
                            lastFocusLossTime = System.currentTimeMillis()
                            DebugLogger.log("SESSION", "Focus Surrendered")
                            handler.postDelayed({ isClaiming = false }, 5000)
                        }
                        android.media.AudioManager.AUDIOFOCUS_GAIN -> {
                            isFocusHeld = true
                            DebugLogger.log("SESSION", "Focus Re-Gained")
                        }
                    }
                }
                .build()
            
            val result = am.requestAudioFocus(focusRequest)
            if (result == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                isFocusHeld = true
                isClaiming = false
                DebugLogger.log("SESSION", "Focus Secured")
                mediaSession?.isActive = true
                startSilentLoop()
            } else {
                isClaiming = false
                lastFocusLossTime = System.currentTimeMillis()
            }
        } else {
            isClaiming = false
        }
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
        
        // Stealth micro-tick (40ms at low power)
        vibrator?.vibrate(VibrationEffect.createOneShot(40, 60))
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
                    speakStatus("Retry wake", 2)
                    wakeDevice(pm)
                } else {
                    speakStatus("Wake failed. Check battery optimization.", 2)
                    wakeRetryCount = 0
                }
            }
        }, 500)
    }

    private fun playSpecificFile(fileName: String) {
        val file = File(audioFolder, fileName)
        
        // --- STEALTH CHECK ---
        val prefs = getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        if (prefs.getBoolean("headset_only", true) && !am.isWiredHeadsetOn && !am.isBluetoothA2dpOn) {
            DebugLogger.log("STEALTH", "Specific playback blocked: Stealth mode active.")
            return
        }

        // --- DIAGNOSTIC PRE-FLIGHT ---
        if (!file.exists()) {
            DebugLogger.log("DIAGNOSTIC", "FAILURE: File $fileName does not exist on disk.")
            speakStatus("Solution file not found. It may still be generating.", 2)
            return
        }

        if (file.length() == 0L) {
            DebugLogger.log("DIAGNOSTIC", "FAILURE: File $fileName is 0 bytes. TTS Engine failed to write data.")
            speakStatus("Empty audio file. TTS engine failed.", 2)
            return
        }

        if (file.length() < 1024) {
            DebugLogger.log("DIAGNOSTIC", "FAILURE: File $fileName is too small (${file.length()}b). Header is likely corrupt.")
            speakStatus("Audio file is corrupted.", 2)
            return
        }

        synchronized(this) {
            stopAllPlayback()
            DebugLogger.log("MEDIA", "Attempting playback: ${file.name}")
            
            try {
                val newPlayer = MediaPlayer()
                newPlayer.setDataSource(file.absolutePath)
                newPlayer.setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                
                newPlayer.setOnPreparedListener { 
                    it.start()
                    updateMediaSessionState(true)
                }
                
                newPlayer.setOnErrorListener { _, what, extra ->
                    val hwReason = when(extra) {
                        MediaPlayer.MEDIA_ERROR_MALFORMED -> "Malformed bitstream"
                        MediaPlayer.MEDIA_ERROR_IO -> "File IO Error"
                        MediaPlayer.MEDIA_ERROR_UNSUPPORTED -> "Unsupported codec"
                        else -> "Low-level hardware error"
                    }
                    DebugLogger.log("DIAGNOSTIC", "HARDWARE FAILURE: Code $what / Reason: $hwReason")
                    speakStatus("Hardware playback error: $hwReason", 2)
                    stopAllPlayback()
                    true
                }

                mediaPlayer = newPlayer
                mediaPlayer?.prepareAsync()
            } catch (e: Exception) {
                DebugLogger.log("DIAGNOSTIC", "CRITICAL: Exception during MediaPlayer init: ${e.message}")
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
        handler.removeCallbacksAndMessages(null)
        autoPlayHandler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(audioSafetyReceiver) } catch(e: Exception) {}
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
package com.universal.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.File
import java.util.*

class PlaybackService : Service(), TextToSpeech.OnInitListener {
    private lateinit var tts: TextToSpeech
    private var mediaPlayer: MediaPlayer? = null
    private val audioFolder by lazy { File(cacheDir, "audio_answers") }
    private var isReady = false
    private var wakeLock: PowerManager.WakeLock? = null

    private var currentType: String? = null
    private var currentIndex = 0
    private val playlists = mutableMapOf<String, MutableList<File>>()
    
    private val autoPlayHandler = Handler(Looper.getMainLooper())
    private var autoPlayRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UniversalApp::ScreenOffKeys")
        wakeLock?.acquire(3 * 60 * 60 * 1000L)

        tts = TextToSpeech(this, this)
        if (!audioFolder.exists()) audioFolder.mkdirs()
        val notification = createNotification("System Standby", "Waiting for content...")
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
            isReady = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
        val isActive = prefs.getBoolean("is_active", false)

        if (intent == null && isActive) {
            rebuildPlaylistsAndResume()
        }

        val action = intent?.action
        val data = intent?.getStringExtra("data")

        when (action) {
            "GENERATE" -> data?.let { processJson(it) }
            "PLAY_TYPE" -> intent.getStringExtra("type")?.let { playType(it) }
            "NEXT" -> playNext()
            "PREVIOUS" -> playPrevious()
            "PAUSE" -> pauseAudio()
            "RESET" -> resetEverything()
            "PLAY_SPECIFIC" -> intent.getStringExtra("file_name")?.let { playSpecificFile(it) }
            "SPEAK_STATUS" -> intent.getStringExtra("message")?.let { speakStatus(it, intent.getBooleanExtra("immediate", false)) }
        }
        return START_STICKY
    }

    private fun speakStatus(message: String, immediate: Boolean) {
        if (!isReady) return
        val queueMode = if (immediate) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts.speak(message, queueMode, null, "STATUS_\${System.currentTimeMillis()}")
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
            DebugLogger.log("TTS", "Processing ${solutions.length()} solutions")
            
            var processedCount = 0
            val totalToProcess = solutions.length()

            for (i in 0 until solutions.length()) {
                val item = solutions.optJSONObject(i) ?: continue
                val type = item.optString("type", "sa")
                val number = item.optString("number", i.toString())
                val text = item.optString("answer", "").ifEmpty { item.optJSONArray("steps")?.optString(0, "") ?: "" }
                
                val speech = "Question $number. Answer: $text"
                val file = File(audioFolder, "${type}_$number.wav")
                val utteranceId = "$type|$number"
                
                tts.synthesizeToFile(speech, Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId) }, file, utteranceId)
                playlists.getOrPut(type) { mutableListOf() }.add(file)
            }

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { DebugLogger.log("TTS", "Started synthesis: $id") }
                override fun onDone(id: String?) { 
                    processedCount++
                    DebugLogger.log("TTS", "Finished synthesis: $id ($processedCount/$totalToProcess)")
                    if (processedCount >= totalToProcess) triggerReadyVibration() 
                }
                override fun onError(id: String?) { 
                    processedCount++
                    DebugLogger.log("ERROR", "TTS Synthesis Error: $id")
                    if (processedCount >= totalToProcess) triggerReadyVibration()
                }
            })
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
        currentType = type
        currentIndex = 0
        savePlaybackState()
        playCurrent()
    }

    private fun playNext() {
        if (currentType == null) return
        currentIndex++
        savePlaybackState()
        playCurrent()
    }

    private fun playPrevious() {
        if (currentType == null) return
        currentIndex--
        savePlaybackState()
        playCurrent()
    }

    private fun playCurrent() {
        val list = playlists[currentType] ?: return
        if (currentIndex < 0) currentIndex = list.size - 1
        if (currentIndex >= list.size) currentIndex = 0
        
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(list[currentIndex].absolutePath)
                prepareAsync()
                setOnPreparedListener { start() }
                setOnCompletionListener { playNext() }
            }
        } catch (e: Exception) { DebugLogger.log("MEDIA", "Error: ${e.message}") }
    }

    private fun pauseAudio() {
        if (mediaPlayer?.isPlaying == true) mediaPlayer?.pause() else mediaPlayer?.start()
    }

    private fun resetEverything() {
        autoPlayRunnable?.let { autoPlayHandler.removeCallbacks(it) }
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
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(2, createNotification(title, text))
    }

    private fun playSpecificFile(fileName: String) {
        val file = File(audioFolder, fileName)
        if (!file.exists()) return
        mediaPlayer?.release()
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
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
package com.universal.app

import android.app.*
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

    private var currentType: String? = null
    private var currentIndex = 0
    private val playlists = mutableMapOf<String, List<File>>()

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        if (!audioFolder.exists()) audioFolder.mkdirs()
        startForeground(2, createNotification("TTS Ready", "Waiting for content..."))
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            tts.setSpeechRate(0.9f) // Slightly slower for better comprehension
            tts.setPitch(1.0f)
            isReady = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val data = intent?.getStringExtra("data")
        DebugLogger.log("PLAYBACK", "Action Received: $action")

        when (action) {
            "GENERATE" -> data?.let { 
                updateNotification("Processing", "Converting response to speech...")
                processJson(it) 
            }
            "PLAY_TYPE" -> intent.getStringExtra("type")?.let { 
                DebugLogger.log("PLAYBACK", "Triggered category: $it")
                playType(it) 
            }
            "NEXT" -> {
                DebugLogger.log("PLAYBACK", "Triggered NEXT")
                playNext()
            }
            "PAUSE" -> pauseAudio()
            "RESET" -> resetEverything()
        }
        return START_STICKY
    }

    private fun processJson(jsonStr: String) {
        val root = JSONObject(jsonStr)
        val solutions = root.getJSONArray("solutions")
        playlists.clear()

        for (i in 0 until solutions.length()) {
            val item = solutions.getJSONObject(i)
            val type = item.optString("type", "sa")
            val number = item.getString("number")
            val answer = item.getString("answer")
            
            val textToSpeak = StringBuilder("Question $number... ")
            if (item.has("steps") && !item.isNull("steps")) {
                val steps = item.getJSONArray("steps")
                for (j in 0 until steps.length()) {
                    textToSpeak.append("Step ${j + 1}: ")
                    textToSpeak.append(steps.getString(j)).append(". .. ") // Triple dots add natural pauses
                }
            } else {
                textToSpeak.append("The answer is: $answer")
            }

            val file = File(audioFolder, "${type}_$number.wav")
            val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "$type|$number") }
            val utteranceId = "$type|$number"
            tts.synthesizeToFile(textToSpeak.toString(), params, file, utteranceId)
            
            val list = playlists.getOrDefault(type, mutableListOf()).toMutableList()
            list.add(file)
            playlists[type] = list
        }

        val lastSolution = solutions.getJSONObject(solutions.length() - 1)
        val targetId = "${lastSolution.optString("type", "sa")}|${lastSolution.getString("number")}"
        
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                 if (utteranceId == targetId) {
                     triggerReadyVibration()
                 }
            }
            override fun onError(utteranceId: String?) {}
        })
    }

    private fun triggerReadyVibration() {
        DebugLogger.log("SYSTEM", "Vibrating: Audio is Ready")
        val totalCount = playlists.values.sumOf { it.size }
        updateNotification("Ready", "$totalCount answers loaded and waiting.")
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        val pattern = longArrayOf(0, 300, 200, 300, 200, 300)
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun updateNotification(title: String, text: String) {
        val notification = createNotification(title, text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(2, notification)
    }

    private fun playType(type: String) {
        currentType = type
        currentIndex = 0
        playCurrent()
    }

    private fun playNext() {
        currentIndex++
        playCurrent()
    }

    private fun playCurrent() {
        val list = playlists[currentType] ?: return
        if (list.isEmpty()) return

        // Wrap around logic
        if (currentIndex >= list.size) {
            currentIndex = 0
            DebugLogger.log("AUDIO", "Looping back to start of $currentType")
        }

        DebugLogger.log("AUDIO", "Playing $currentType index $currentIndex")

        mediaPlayer?.stop()
        mediaPlayer?.release()

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(list[currentIndex].absolutePath)
                prepare()
                start()
                setOnCompletionListener {
                    DebugLogger.log("AUDIO", "Finished index $currentIndex. Auto-advancing...")
                    playNext()
                }
            }
        } catch (e: Exception) {
            DebugLogger.log("ERROR", "Playback failed: ${e.message}")
        }
    }

    private fun pauseAudio() {
        if (mediaPlayer?.isPlaying == true) mediaPlayer?.pause() else mediaPlayer?.start()
    }

    private fun resetEverything() {
        mediaPlayer?.stop()
        playlists.clear()
        audioFolder.deleteRecursively()
        audioFolder.mkdirs()
        DebugLogger.log("SYSTEM", "Playback Reset")
    }

    private fun createNotification(title: String, text: String): Notification {
        return NotificationCompat.Builder(this, "PlaybackChannel")
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
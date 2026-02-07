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
        val notification = createNotification("TTS Ready", "Waiting for content...")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(2, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(2, notification)
        }
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
            "PLAY_SPECIFIC" -> {
                val fileName = intent.getStringExtra("file_name")
                if (fileName != null) playSpecificFile(fileName)
            }
        }
        return START_STICKY
    }

    private fun playSpecificFile(fileName: String) {
        val file = File(audioFolder, fileName)
        if (!file.exists()) return

        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                start()
            }
            updateNotification("Playing Preview", fileName)
        } catch (e: Exception) {
            DebugLogger.log("ERROR", "Specific play failed: ${e.message}")
        }
    }

    private fun processJson(jsonStr: String) {
        if (!isReady) {
            DebugLogger.log("ERROR", "TTS not ready yet. Retrying in 2s...")
            Handler(Looper.getMainLooper()).postDelayed({ processJson(jsonStr) }, 2000)
            return
        }
        
        val root = try { JSONObject(jsonStr) } catch (e: Exception) { 
            DebugLogger.log("ERROR", "Invalid JSON: ${e.message}")
            return 
        }
        val solutions = root.optJSONArray("solutions") ?: return
        playlists.clear()

        for (i in 0 until solutions.length()) {
            val item = solutions.getJSONObject(i)
            val type = item.optString("type", "sa")
            val number = item.getString("number")
            val answer = item.getString("answer")
            
            val typeLabel = when(type) {
                "mc" -> "Multiple choice question $number. "
                "tf" -> "True or false question $number. "
                "ma" -> "Matching question $number. "
                "fill" -> "Fill in the blank question $number. "
                "wo" -> "Problem $number. "
                else -> "Question $number. "
            }

            val textToSpeak = StringBuilder(typeLabel)
            
            // Only process steps for 'workout' (wo) types to prevent AI explanations in other types
            if (type == "wo" && item.has("steps") && !item.isNull("steps")) {
                val steps = item.getJSONArray("steps")
                for (j in 0 until steps.length()) {
                    textToSpeak.append("Step ${j + 1}. ").append(steps.getString(j)).append(".  . ")
                }
            } else {
                // For non-workout, prioritize the direct answer only
                if (type == "mc") textToSpeak.append("The correct option is: ") 
                else textToSpeak.append("The answer is: ")
                textToSpeak.append(answer)
            }

            val finalSpeech = textToSpeak.toString()
            DebugLogger.log("TTS_GEN", "ID $number ($type): $finalSpeech")

            val file = File(audioFolder, "${type}_$number.wav")
            val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "$type|$number") }
            val utteranceId = "$type|$number"
            tts.synthesizeToFile(finalSpeech, params, file, utteranceId)
            
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
        val list = playlists[currentType]
        
        if (list == null || list.isEmpty()) {
            DebugLogger.log("AUDIO", "Attempted to play empty category: $currentType")
            if (isReady) {
                tts.speak("There is no available answer for that category", TextToSpeech.QUEUE_FLUSH, null, "EMPTY_NOTICE")
            }
            return
        }

        // Wrap around logic
        if (currentIndex >= list.size) {
            currentIndex = 0
            DebugLogger.log("AUDIO", "Looping back to start of $currentType")
        }

        DebugLogger.log("AUDIO", "Playing $currentType index $currentIndex")

        try {
            if (mediaPlayer?.isPlaying == true) mediaPlayer?.stop()
            mediaPlayer?.reset()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            DebugLogger.log("DEBUG", "Media release swallowed: ${e.message}")
        }

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
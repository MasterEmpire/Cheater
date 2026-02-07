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
            "PREVIOUS" -> {
                DebugLogger.log("PLAYBACK", "Triggered PREVIOUS")
                playPrevious()
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
            
            // 1. Log Raw Input for Debugging
            DebugLogger.log("RAW_JSON", "Item $number: $item")

            val typeLabel = when(type) {
                "mc" -> "Multiple choice question $number. "
                "tf" -> "True or false question $number. "
                "ma" -> "Matching question $number. "
                "fill" -> "Fill in the blank question $number. "
                "wo" -> "Problem $number. "
                else -> "Question $number. "
            }

            val textToSpeak = StringBuilder(typeLabel)
            
            // 2. Extract answer with heavy fallbacks
            var answerText = item.optString("answer", "").trim()
            
            // Fallback: If answer is empty but steps exist (AI error), use first step
            if (answerText.isEmpty() && item.has("steps")) {
                val fallbackSteps = item.optJSONArray("steps")
                if (fallbackSteps != null && fallbackSteps.length() > 0) {
                    answerText = fallbackSteps.optString(0, "")
                    DebugLogger.log("WARN", "Using step as fallback for $number")
                }
            }

            if (type == "wo") {
                val steps = item.optJSONArray("steps")
                if (steps != null && steps.length() > 0) {
                    for (j in 0 until steps.length()) {
                        val rawStep = steps.optString(j)
                        // Transformation: Convert [write: xyz] into audible ", write: xyz"
                        val audibleStep = rawStep
                            .replace("[write:", ", write: ")
                            .replace("]", "")
                        
                        textToSpeak.append("Step ${j + 1}. ").append(audibleStep).append(". ")
                    }
                } else {
                    textToSpeak.append("The result is ").append(answerText.ifEmpty { "unknown" })
                }
            } else {
                val prefix = if (type == "mc") "The correct option is: " else "The answer is: "
                textToSpeak.append(prefix).append(answerText.ifEmpty { "not provided by solver" })
            }

            val finalSpeech = textToSpeak.toString()
            // 3. Log the Final Result - This is exactly what is sent to the TTS engine
            DebugLogger.log("TTS_AUDIT", "Final speech string: \"$finalSpeech\"")

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

    private fun playPrevious() {
        currentIndex--
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
        if (currentIndex < 0) {
            currentIndex = list.size - 1
            DebugLogger.log("AUDIO", "Looping to end of $currentType")
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
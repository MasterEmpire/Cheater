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

    override fun onTaskRemoved(rootIntent: Intent?) {
        val restartServiceIntent = Intent(applicationContext, this.javaClass)
        restartServiceIntent.setPackage(packageName)
        val restartServicePendingIntent = android.app.PendingIntent.getService(
            applicationContext, 2, restartServiceIntent, 
            android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val alarmService = applicationContext.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        alarmService.set(android.app.AlarmManager.ELAPSED_REALTIME, android.os.SystemClock.elapsedRealtime() + 1000, restartServicePendingIntent)
        super.onTaskRemoved(rootIntent)
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
            DebugLogger.log("WARN", "TTS not ready. Queuing retry...")
            Handler(Looper.getMainLooper()).postDelayed({ processJson(jsonStr) }, 2000)
            return
        }
        
        try {
            val root = JSONObject(jsonStr)
            val solutions = root.optJSONArray("solutions")
            if (solutions == null || solutions.length() == 0) {
                DebugLogger.log("ERROR", "JSON contains no solutions array")
                return
            }

            playlists.clear()
            var lastTargetId = ""

            for (i in 0 until solutions.length()) {
                val item = solutions.optJSONObject(i) ?: continue
                
                val type = item.optString("type", "sa")
                val number = item.optString("number", i.toString())
                val answer = item.optString("answer", "").trim()
                
                val typeLabel = when(type) {
                    "mc" -> "Multiple choice question $number. "
                    "tf" -> "True or false question $number. "
                    "ma" -> "Matching question $number. "
                    "fill" -> "Fill in the blank question $number. "
                    "wo" -> "Problem $number. "
                    else -> "Question $number. "
                }

                val textToSpeak = StringBuilder(typeLabel)
                var answerText = answer
                
                if (answerText.isEmpty()) {
                    val steps = item.optJSONArray("steps")
                    if (steps != null && steps.length() > 0) {
                        answerText = steps.optString(0, "")
                    }
                }

                if (type == "wo") {
                    val steps = item.optJSONArray("steps")
                    if (steps != null && steps.length() > 0) {
                        for (j in 0 until steps.length()) {
                            val stepText = steps.optString(j, "").replace("[write:", ", write: ").replace("]", "")
                            if (stepText.isNotBlank()) textToSpeak.append("Step ${j + 1}. $stepText. ")
                        }
                    } else {
                        textToSpeak.append("Result is ${answerText.ifEmpty { "unspecified" }}")
                    }
                } else {
                    textToSpeak.append(if (type == "mc") "Option: " else "Answer: ").append(answerText.ifEmpty { "none" })
                }

                val utteranceId = "$type|$number"
                lastTargetId = utteranceId
                val file = File(audioFolder, "${type}_$number.wav")
                
                tts.synthesizeToFile(textToSpeak.toString(), Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId) }, file, utteranceId)
                
                playlists.getOrPut(type) { mutableListOf() }.add(file)
            }

            if (lastTargetId.isNotEmpty()) {
                val finalId = lastTargetId
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { if (id == finalId) triggerReadyVibration() }
                    override fun onError(id: String?) { DebugLogger.log("TTS", "Error synthesizing: $id") }
                })
            }
        } catch (e: Exception) {
            DebugLogger.log("CRITICAL", "Parser Failure: ${e.localizedMessage}")
        }
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
        if (currentType == null) {
            DebugLogger.log("AUDIO", "Next ignored: No category selected")
            return
        }
        currentIndex++
        playCurrent()
    }

    private fun playPrevious() {
        if (currentType == null) {
            DebugLogger.log("AUDIO", "Previous ignored: No category selected")
            return
        }
        currentIndex--
        playCurrent()
    }

    private fun playCurrent() {
        if (currentType == null) return
        
        val list = playlists[currentType]
        
        if (list == null || list.isEmpty()) {
            DebugLogger.log("AUDIO", "Category empty: $currentType")
            // Removed annoying TTS 'no available' announcement for navigation
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
            val file = list[currentIndex]
            if (!file.exists()) {
                DebugLogger.log("ERROR", "Audio file missing: ${file.name}")
                return
            }
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepareAsync()
                setOnPreparedListener { start() }
                setOnErrorListener { mp, what, extra -> 
                    DebugLogger.log("MEDIA", "Error $what during playback")
                    true 
                }
                setOnCompletionListener {
                    DebugLogger.log("AUDIO", "Index $currentIndex done. Next...")
                    playNext()
                }
            }
        } catch (e: Exception) {
            DebugLogger.log("CRITICAL", "Player Init Failed: ${e.message}")
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
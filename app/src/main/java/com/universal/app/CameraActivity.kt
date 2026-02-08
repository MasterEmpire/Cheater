package com.universal.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraActivity : ComponentActivity() {
    private var imageCapture: ImageCapture? = null
    private val capturedFiles = mutableListOf<File>()
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var viewFinder: PreviewView
    private var cameraControl: CameraControl? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            km.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or 
                           android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }

        viewFinder = PreviewView(this)
        setContentView(viewFinder)
        cameraExecutor = Executors.newSingleThreadExecutor()
        startCamera()
        notifyVoice("Wide camera active", true)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(viewFinder.surfaceProvider) }
            imageCapture = ImageCapture.Builder().build()
            
            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                cameraControl = camera.cameraControl
                
                camera.cameraInfo.zoomState.observe(this) { state ->
                    cameraControl?.setZoomRatio(state.minZoomRatio)
                }
            } catch (e: Exception) {
                DebugLogger.log("CAMERA", "Init failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val cap = imageCapture ?: return
        val file = File(externalCacheDir, "cap_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()

        cap.takePicture(outputOptions, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                capturedFiles.add(file)
                notifyVoice("Captured ${capturedFiles.size}", true)
            }
            override fun onError(exc: ImageCaptureException) {
                DebugLogger.log("CAMERA", "Error: ${exc.message}")
            }
        })
    }

    private fun notifyVoice(msg: String, immediate: Boolean = false) {
        val intent = Intent(this, PlaybackService::class.java).apply {
            action = "SPEAK_STATUS"
            putExtra("message", msg)
            putExtra("immediate", immediate)
        }
        startService(intent)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                takePhoto()
                true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                notifyVoice("Sending ${capturedFiles.size} images", true)
                capturedFiles.forEach { Uploader.uploadFile(applicationContext, it) }
                finish()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
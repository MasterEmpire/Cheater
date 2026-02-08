package com.universal.app

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraActivity : AppCompatActivity() {
    private var imageCapture: ImageCapture? = null
    private val capturedFiles = mutableListOf<File>()
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var viewFinder: PreviewView
    private var cameraControl: CameraControl? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewFinder = PreviewView(this)
        setContentView(viewFinder)
        cameraExecutor = Executors.newSingleThreadExecutor()
        startCamera()
        Toast.makeText(this, "Wide-Angle Mode Active", Toast.LENGTH_SHORT).show()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(viewFinder.surfaceProvider) }
            imageCapture = ImageCapture.Builder().build()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
                cameraControl = camera.cameraControl
                
                // Force Wide Angle (Minimum Zoom)
                camera.cameraInfo.zoomState.observe(this) { state ->
                    cameraControl?.setZoomRatio(state.minZoomRatio)
                }
            } catch (e: Exception) {
                DebugLogger.log("CAMERA", "Init failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        val photoFile = File(externalCacheDir, "cap_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                capturedFiles.add(photoFile)
                val vibrator = getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(100, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                DebugLogger.log("CAMERA", "Photo captured: ${capturedFiles.size}")
            }
            override fun onError(exc: ImageCaptureException) {
                DebugLogger.log("CAMERA", "Capture failed: ${exc.message}")
            }
        })
    }

    private fun finishAndSend() {
        if (capturedFiles.isEmpty()) {
            finish()
            return
        }
        
        capturedFiles.forEach { file ->
            Uploader.uploadFile(applicationContext, file)
        }
        Toast.makeText(this, "Sending ${capturedFiles.size} images...", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                takePhoto()
                true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                finishAndSend()
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
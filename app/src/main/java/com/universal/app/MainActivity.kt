package com.universal.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import android.widget.Toast
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannels()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppDashboard()
                }
            }
        }
    }
}

@Composable
fun AppDashboard() {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(checkPermissions(context)) }
    var showLogs by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasPermission = results.values.all { it }
        if (hasPermission) startServices(context)
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (!hasPermission) {
            Text("System Permissions Required", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = {
                val permissions = mutableListOf<String>()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
                    permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
                launcher.launch(permissions.toTypedArray())
            }) { Text("GRANT ACCESS") }
        } else {
            Text("MONITORING ACTIVE", color = Color.Green)
            Spacer(modifier = Modifier.height(24.dp))
            
            Button(
                onClick = { requestBatteryOptimization(context) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("DISABLE BATTERY RESTRICTIONS") }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { showLogs = true },
                modifier = Modifier.fillMaxWidth()
            ) { 
                Icon(Icons.Default.List, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("VIEW SYSTEM LOGS") 
            }

            Spacer(modifier = Modifier.height(16.dp))

            val pickerLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.PickVisualMedia()
            ) { uri ->
                uri?.let { 
                    DebugLogger.log("TEST", "Manual selection: $it")
                    Uploader.uploadUri(context, it) 
                }
            }

            OutlinedButton(
                onClick = { 
                    pickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) 
                },
                modifier = Modifier.fillMaxWidth(),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.Cyan)
            ) {
                Text("UPLOAD TEST IMAGE", color = Color.Cyan)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { 
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE))
            ) { Text("ENABLE KEY INTERCEPTOR") }

            Spacer(modifier = Modifier.height(16.dp))

            Text("GENERATED SOLUTIONS", style = MaterialTheme.typography.labelLarge, color = Color.Gray)
            Spacer(modifier = Modifier.height(8.dp))
            
            var audioFiles by remember { mutableStateOf(listOf<File>()) }
            val audioFolder = File(context.cacheDir, "audio_answers")
            
            fun refreshFiles() {
                if (audioFolder.exists()) {
                    audioFiles = audioFolder.listFiles()?.filter { it.extension == "wav" }?.sortedByDescending { it.lastModified() } ?: emptyList()
                }
            }

            LaunchedEffect(Unit) { refreshFiles() }

            Box(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).background(Color(0xFF1A1A1A)).padding(8.dp)) {
                if (audioFiles.isEmpty()) {
                    Text("No audio generated yet", modifier = Modifier.align(Alignment.Center), style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn {
                        items(audioFiles) { file ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(file.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                IconButton(onClick = {
                                    val intent = Intent(context, PlaybackService::class.java).apply {
                                        action = "PLAY_SPECIFIC"
                                        putExtra("file_name", file.name)
                                    }
                                    context.startService(intent)
                                }) {
                                    Icon(android.graphics.drawable.Icon.createWithResource(context, android.R.drawable.ic_media_play).toIcon(), contentDescription = "Play", tint = Color.Cyan)
                                }
                            }
                        }
                    }
                }
            }
            
            TextButton(onClick = { refreshFiles() }) {
                Text("REFRESH AUDIO LIST", size = 12.sp)
            }
            
            LaunchedEffect(Unit) { startServices(context) }
        }
    }

    if (showLogs) {
        LogModal(onDismiss = { showLogs = false })
    }
}

@Composable
fun LogModal(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Internal Trace") },
        text = {
            LazyColumn(modifier = Modifier.height(400.dp)) {
                items(DebugLogger.logs) {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = {
                    val fullLog = DebugLogger.getFullLog()
                    clipboardManager.setText(AnnotatedString(fullLog))
                    Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                }) {
                    Text("COPY ALL")
                }
                TextButton(onClick = onDismiss) {
                    Text("CLOSE")
                }
            }
        }
    )
}

private fun checkPermissions(context: Context): Boolean {
    val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
}

private fun startServices(context: Context) {
    val intent = Intent(context, ImageMonitorService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
}

private fun requestBatteryOptimization(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
    intent.data = Uri.parse("package:${context.packageName}")
    context.startActivity(intent)
}

private fun MainActivity.createNotificationChannels() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel("PlaybackChannel", "Solutions Playback", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
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
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import androidx.core.content.ContextCompat
import java.io.File

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

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("PlaybackChannel", "Solutions Playback", NotificationManager.IMPORTANCE_HIGH)
            channel.setBypassDnd(true)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}

@Composable
fun AppDashboard() {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(checkPermissions(context)) }
    var showLogs by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE) }
    var isSystemActive by remember { mutableStateOf(prefs.getBoolean("is_active", false)) }
    var audioFiles by remember { mutableStateOf(listOf<File>()) }
    val audioFolder = File(context.cacheDir, "audio_answers")

    fun refreshFiles() {
        if (audioFolder.exists()) {
            audioFiles = audioFolder.listFiles()?.filter { it.extension == "wav" }?.sortedByDescending { it.lastModified() } ?: emptyList()
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        hasPermission = results.values.all { it }
        if (hasPermission) startServices(context)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (!hasPermission) Color(0xFFB71C1C) 
                                 else if (isSystemActive) Color(0xFF1B5E20) 
                                 else Color(0xFF37474F)
            )
        ) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (hasPermission) Icons.Default.CheckCircle else Icons.Default.Warning, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("System Status", style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Text(
                        text = if (!hasPermission) "Action Required: Permissions" 
                               else if (isSystemActive) "ACTIVE: Monitoring & Keys" 
                               else "STANDBY: Logic Suspended",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
                Switch(
                    checked = isSystemActive,
                    onCheckedChange = { 
                        isSystemActive = it
                        prefs.edit().putBoolean("is_active", it).apply()
                        if (!it) {
                            // Immediate stop command
                            val intent = Intent(context, PlaybackService::class.java).apply { action = "RESET" }
                            context.startService(intent)
                        }
                        if (it && hasPermission) startServices(context)
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (!hasPermission) {
            Button(
                onClick = {
                    val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) 
                        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.POST_NOTIFICATIONS)
                        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                    launcher.launch(perms)
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("GRANT SYSTEM PERMISSIONS") }
        }

        // Customization Toggles
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val keysEnabled = remember { mutableStateOf(prefs.getBoolean("keys_enabled", true)) }
            val headsetTrigger = remember { mutableStateOf(prefs.getBoolean("headset_trigger", false)) }
            val touchBlocker = remember { mutableStateOf(prefs.getBoolean("touch_blocker", false)) }
            val volShutter = remember { mutableStateOf(prefs.getBoolean("vol_shutter", false)) }

            SettingsToggle("Intercept Keys (Enable for Logic)", keysEnabled) { prefs.edit().putBoolean("keys_enabled", it).apply() }
            SettingsToggle("Earphone Double-Click Trigger", headsetTrigger) { prefs.edit().putBoolean("headset_trigger", it).apply() }
            SettingsToggle("Block Touch in Camera", touchBlocker) { prefs.edit().putBoolean("touch_blocker", it).apply() }
            SettingsToggle("Let Vol Keys Take Photo", volShutter) { prefs.edit().putBoolean("vol_shutter", it).apply() }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { requestBatteryOptimization(context) }, modifier = Modifier.weight(1f)) { Text("BATTERY", fontSize = 10.sp) }
            OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, modifier = Modifier.weight(1f)) { Text("ACCESSIBILITY", fontSize = 10.sp) }
            OutlinedButton(onClick = { showLogs = true }, modifier = Modifier.weight(1f)) { Text("LOGS", fontSize = 10.sp) }
        }
    }
}

@Composable
fun SettingsToggle(label: String, state: MutableState<Boolean>, onValueChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), color = Color.LightGray)
        Switch(checked = state.value, onCheckedChange = { state.value = it; onValueChange(it) }, modifier = Modifier.scale(0.7f))
    }
}

        Spacer(modifier = Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SOLUTION RECAP", style = MaterialTheme.typography.labelLarge, color = Color.Cyan)
            Spacer(Modifier.width(8.dp))
            Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = Color.Cyan.copy(alpha = 0.2f)
            ) {
                Text(
                    text = "${audioFiles.size}",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Cyan
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        
        Card(modifier = Modifier.fillMaxWidth().weight(1f), border = androidx.compose.foundation.BorderStroke(1.dp, Color.DarkGray)) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (audioFiles.isEmpty()) {
                    Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = Color.Gray)
                        Text("No solutions yet", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                        items(audioFiles) { file ->
                            AudioListItem(file) { 
                                val intent = Intent(context, PlaybackService::class.java).apply {
                                    action = "PLAY_SPECIFIC"
                                    putExtra("file_name", file.name)
                                }
                                context.startService(intent)
                            }
                        }
                    }
                }
                FloatingActionButton(onClick = { refreshFiles() }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp), containerColor = Color.Cyan) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color.Black)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        val pickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { Uploader.uploadUri(context, it) } }
        Button(onClick = { pickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)) { Text("MANUAL UPLOAD TEST") }
    }

    if (showLogs) {
        LogModal(onDismiss = { showLogs = false })
    }

    LaunchedEffect(Unit) {
        refreshFiles()
        if (hasPermission) startServices(context)
    }
}

@Composable
fun AudioListItem(file: File, onPlay: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Color.Black.copy(alpha = 0.2f)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.bodyMedium, color = Color.White)
            Text("${file.length() / 1024} KB", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        }
        IconButton(onClick = onPlay) { Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Cyan) }
    }
}

@Composable
fun LogModal(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Internal Trace") },
        text = { LazyColumn(modifier = Modifier.height(400.dp)) { items(DebugLogger.logs) { Text(it, style = MaterialTheme.typography.bodySmall) } } },
        confirmButton = {
            Row {
                TextButton(onClick = {
                    clipboardManager.setText(AnnotatedString(DebugLogger.getFullLog()))
                    Toast.makeText(context, "Logs copied", Toast.LENGTH_SHORT).show()
                }) { Text("COPY ALL") }
                TextButton(onClick = onDismiss) { Text("CLOSE") }
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
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
}

private fun requestBatteryOptimization(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
    intent.data = Uri.parse("package:${context.packageName}")
    context.startActivity(intent)
}
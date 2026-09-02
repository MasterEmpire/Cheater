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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState) 
        DebugLogger.init(applicationContext)
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
            channel.enableVibration(false)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}

@Composable
fun AppDashboard() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE) }
    
    var hasPermission by remember { mutableStateOf(checkPermissions(context)) }
    var hasNotificationPermission by remember { mutableStateOf(false) }
    var hasBatteryOptim by remember { mutableStateOf(checkBattery(context)) }
    var hasOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var hasAccessibility by remember { mutableStateOf(isAccessibilityEnabled(context)) }
    
    var isSystemActive by remember { mutableStateOf(prefs.getBoolean("is_active", true)) }
    var audioFiles by remember { mutableStateOf(listOf<File>()) }
    var showLogs by remember { mutableStateOf(false) }
    
    val audioFolder = File(context.cacheDir, "audio_answers")
    fun refreshFiles() { if (audioFolder.exists()) audioFiles = audioFolder.listFiles()?.filter { it.extension == "wav" }?.sortedByDescending { it.lastModified() } ?: emptyList() }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        hasPermission = results.values.all { it }
        if (hasPermission) startServices(context)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = checkPermissions(context)
                hasBatteryOptim = checkBattery(context)
                hasOverlay = Settings.canDrawOverlays(context)
                hasAccessibility = isAccessibilityEnabled(context)
                hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                } else true
                
                if (hasPermission && isSystemActive) startServices(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        floatingActionButton = {
            FloatingActionButton(onClick = { refreshFiles() }, containerColor = MaterialTheme.colorScheme.primaryContainer) {
                Icon(Icons.Default.Refresh, "Refresh")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(Modifier.height(8.dp)) }
            
            // --- Section 1: Hero Status ---
            item {
                StatusHeroCard(isSystemActive, hasPermission) { isActive ->
                    isSystemActive = isActive
                    // Use commit() to ensure the background service sees the change immediately
                    val editor = prefs.edit()
                    editor.putBoolean("is_active", isActive)
                    if (!isActive) editor.putBoolean("touch_blocker", false)
                    editor.commit()
                    
                    if (!isActive) {
                        context.stopService(Intent(context, ImageMonitorService::class.java))
                        context.stopService(Intent(context, PlaybackService::class.java))
                    } else if (hasPermission) startServices(context)
                }
            }

            // --- Section 2: Health Grid ---
            item {
                Text("SYSTEM HEALTH", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PermissionChip("Files", hasPermission, Modifier.weight(1f)) {
                        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
                        } else {
                            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                        }
                        launcher.launch(perms)
                    }
                    PermissionChip("Battery", hasBatteryOptim, Modifier.weight(1f)) { requestBatteryOptimization(context) }
                    PermissionChip("Overlay", hasOverlay, Modifier.weight(1f)) { 
                        context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                    }
                    PermissionChip("Access", hasAccessibility, Modifier.weight(1f)) {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    PermissionChip("Alerts", hasNotificationPermission, Modifier.weight(1f)) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                        }
                    }
                }
            }

            // --- Section 3: Logic Controls ---
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val keysEnabled = remember { mutableStateOf(prefs.getBoolean("keys_enabled", true)) }
                        val headsetTrigger = remember { mutableStateOf(prefs.getBoolean("headset_trigger", false)) }
                        val touchBlocker = remember { mutableStateOf(prefs.getBoolean("touch_blocker", false)) }

                        SettingsToggle("Intercept Media Keys", keysEnabled) { prefs.edit().putBoolean("keys_enabled", it).apply() }
                        SettingsToggle("Headset Shutter", headsetTrigger) { prefs.edit().putBoolean("headset_trigger", it).apply() }
                        SettingsToggle("Global Touch Blocker (Block Everything)", touchBlocker) { 
                            prefs.edit().putBoolean("touch_blocker", it).apply()
                            // Safety Logic: If blocker is enabled, hardware keys MUST be intercepted to allow emergency exit
                            if (it && !keysEnabled.value) {
                                keysEnabled.value = true
                                prefs.edit().putBoolean("keys_enabled", true).apply()
                            }
                        }
                        
                        val successiveEnabled = remember { mutableStateOf(prefs.getBoolean("successive_enabled", false)) }
                        val successiveCount = remember { mutableStateOf(prefs.getInt("successive_count", 3)) }
                        
                        SettingsToggle("Successive Capture", successiveEnabled) { prefs.edit().putBoolean("successive_enabled", it).apply() }
                        if (successiveEnabled.value) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                                Text("Amount: ${successiveCount.value}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                                Slider(
                                    value = successiveCount.value.toFloat(),
                                    onValueChange = { 
                                        successiveCount.value = it.toInt()
                                        prefs.edit().putInt("successive_count", it.toInt()).apply() 
                                    },
                                    valueRange = 2f..10f,
                                    modifier = Modifier.weight(2f)
                                )
                            }
                        }
                        val useOmniEngine = remember { mutableStateOf(prefs.getBoolean("use_omni_engine", true)) }
                        SettingsToggle("Use Omni Engine (Local AI Studio)", useOmniEngine) { prefs.edit().putBoolean("use_omni_engine", it).apply() }

                        val omniPresetTitle = remember { mutableStateOf(prefs.getString("omni_preset_title", "Exam Solver") ?: "Exam Solver") }
                        if (useOmniEngine.value) {
                            OutlinedTextField(
                                value = omniPresetTitle.value,
                                onValueChange = {
                                    omniPresetTitle.value = it
                                    prefs.edit().putString("omni_preset_title", it).apply()
                                },
                                label = { Text("Omni Preset Title (matches preset in Omni Chrome)") },
                                placeholder = { Text("e.g. Exam Solver") },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 4.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                                )
                            )

                            Button(
                                onClick = {
                                    OmniLocalBridge.runDiagnostics(context)
                                    showLogs = true
                                },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                                shape = MaterialTheme.shapes.medium,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                            ) {
                                Icon(Icons.Default.Info, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("DIAGNOSE OMNI BRIDGE (AUDIT)", style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        val headsetOnly = remember { mutableStateOf(prefs.getBoolean("headset_only", true)) }
                        SettingsToggle("Stealth Audio (Headset Only)", headsetOnly) { prefs.edit().putBoolean("headset_only", it).apply() }
                        
                        Button(
                            onClick = { 
                                DebugLogger.log("UI_ACTION", "User triggered Force Media Lock. Auditing system state...")
                                context.startService(Intent(context, PlaybackService::class.java).apply { action = "CLAIM_FOCUS" })
                                Toast.makeText(context, "Executing Deep Authority Hijack...", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            shape = MaterialTheme.shapes.medium,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                        ) { 
                            Icon(Icons.Default.Lock, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("FORCE MEDIA LOCK") 
                        }
                    }
                }
            }

            // --- Section 4: Solution Feed ---
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("SOLUTION RECAP", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = {
                        context.startService(Intent(context, PlaybackService::class.java).apply { action = "RESET" })
                        refreshFiles()
                        Toast.makeText(context, "Session Cleared", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.Delete, "Reset Session", tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
                    }
                    TextButton(onClick = { showLogs = true }) { Text("VIEW TRACE") }
                }
            }

            if (audioFiles.isEmpty()) {
                item { EmptyState() }
            } else {
                items(audioFiles) { file ->
                    SolutionFeedItem(file) { 
                        context.startService(Intent(context, PlaybackService::class.java).apply {
                            action = "PLAY_SPECIFIC"
                            putExtra("file_name", file.name)
                        })
                    }
                }
            }
            
            item { 
                // Using GetMultipleContents to allow batch selection for AI stitching
                val pickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
                    if (uris.isNotEmpty()) Uploader.uploadUris(context, uris)
                }
                OutlinedButton(
                    onClick = { pickerLauncher.launch("image/*") },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                ) { 
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("BROWSE & UPLOAD IMAGES (MULTI)") 
                }
            }
        }
    }

    if (showLogs) LogModal { showLogs = false }
    LaunchedEffect(Unit) { refreshFiles(); if (hasPermission) startServices(context) }
}

@Composable
fun StatusHeroCard(isActive: Boolean, hasPerm: Boolean, onToggle: (Boolean) -> Unit) {
    val statusColor = if (!hasPerm) MaterialTheme.colorScheme.error 
                    else if (isActive) Color(0xFF4CAF50) 
                    else MaterialTheme.colorScheme.surfaceVariant
    
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = statusColor.copy(alpha = 0.15f))
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = androidx.compose.foundation.shape.CircleShape, color = statusColor) {
                Icon(
                    if (hasPerm) (if (isActive) Icons.Default.Check else Icons.Default.Notifications) else Icons.Default.Warning, 
                    null, Modifier.padding(8.dp), tint = Color.White
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Protection Status", style = MaterialTheme.typography.labelMedium)
                Text(
                    if (!hasPerm) "Permissions Needed" else if (isActive) "System Guard Active" else "System Standby",
                    style = MaterialTheme.typography.titleLarge
                )
            }
            Switch(checked = isActive, onCheckedChange = onToggle)
        }
    }
}

@Composable
fun PermissionChip(label: String, isValid: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = if (isValid) Color(0xFF2E7D32).copy(alpha = 0.2f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (isValid) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error)
    ) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(if (isValid) Icons.Default.CheckCircle else Icons.Default.Info, null, Modifier.size(16.dp), tint = if (isValid) Color(0xFF81C784) else MaterialTheme.colorScheme.error)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun SolutionFeedItem(file: File, onPlay: () -> Unit) {
    val typePrefix = file.name.split("_").firstOrNull() ?: "sa"
    val icon = when(typePrefix) {
        "mc" -> Icons.Default.List
        "tf" -> Icons.Default.Done
        "wo" -> Icons.Default.Build
        else -> Icons.Default.Info
    }
    
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        onClick = onPlay
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(file.name.replace(".wav", ""), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Text("${file.length()/1024} KB • Downloaded", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
            Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun EmptyState() {
    Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.Search, null, Modifier.size(48.dp), tint = Color.Gray.copy(alpha = 0.3f))
        Text("Waiting for first scan...", color = Color.Gray)
    }
}

fun checkBattery(c: Context) = (c.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(c.packageName)
fun isAccessibilityEnabled(c: Context): Boolean {
    val s = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    return s.contains(c.packageName)
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
                TextButton(onClick = {
                    DebugLogger.clear()
                    Toast.makeText(context, "Logs cleared", Toast.LENGTH_SHORT).show()
                }) { Text("CLEAR", color = MaterialTheme.colorScheme.error) }
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

@Composable
fun SettingsToggle(label: String, state: MutableState<Boolean>, onValueChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), color = Color.LightGray)
        Switch(checked = state.value, onCheckedChange = { state.value = it; onValueChange(it) }, modifier = Modifier.scale(0.7f))
    }
}
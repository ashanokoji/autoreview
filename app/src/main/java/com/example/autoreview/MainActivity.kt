package com.example.autoreview

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import com.unity3d.mediation.LevelPlay
import com.unity3d.mediation.LevelPlayInitListener
import com.unity3d.mediation.LevelPlayInitRequest
import com.unity3d.mediation.LevelPlayConfiguration
import com.unity3d.mediation.LevelPlayInitError
import com.unity3d.mediation.LevelPlayAdInfo
import com.unity3d.mediation.LevelPlayAdError
import com.unity3d.mediation.interstitial.LevelPlayInterstitialAd
import com.unity3d.mediation.interstitial.LevelPlayInterstitialAdListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextDecoration
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

private enum class Screen { PERMISSIONS, MAIN, PRESET_SETTINGS, UNRECOGNIZED_QUESTION, LOGS }

class MainActivity : ComponentActivity(), LevelPlayInterstitialAdListener {

    private lateinit var mInterstitialAd: LevelPlayInterstitialAd

    private val viewModel: MainViewModel by lazy {
        androidx.lifecycle.ViewModelProvider(this)[MainViewModel::class.java]
    }

    private val unrecognizedQuestionState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LevelPlay.setMetaData("is_test_suite", "enable")
        val initRequest = LevelPlayInitRequest.Builder("271e3e6fd").build()
        LevelPlay.init(this, initRequest, object : LevelPlayInitListener {
            override fun onInitFailed(error: LevelPlayInitError) {}
            override fun onInitSuccess(configuration: LevelPlayConfiguration) {
                LevelPlay.launchTestSuite(this@MainActivity)
                
                mInterstitialAd = LevelPlayInterstitialAd("2yvp2oand17mdoke")
                mInterstitialAd.setListener(this@MainActivity)
                mInterstitialAd.loadAd()
            }
        })
        com.example.autoreview.util.AppLogger.init(applicationContext)
        handleIntent(intent)
        setContent {
            AutoReviewTheme {
                AutoReviewApp(viewModel, unrecognizedQuestionState, ::showInterstitialAd)
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

    fun showInterstitialAd() {
        if (::mInterstitialAd.isInitialized && mInterstitialAd.isAdReady) {
            mInterstitialAd.showAd(this)
        }
    }

    override fun onAdLoaded(adInfo: LevelPlayAdInfo) {}
    override fun onAdLoadFailed(error: LevelPlayAdError) {}
    override fun onAdInfoChanged(adInfo: LevelPlayAdInfo) {}
    override fun onAdDisplayed(adInfo: LevelPlayAdInfo) {}
    override fun onAdDisplayFailed(error: LevelPlayAdError, adInfo: LevelPlayAdInfo) {}
    override fun onAdClicked(adInfo: LevelPlayAdInfo) {}
    override fun onAdClosed(adInfo: LevelPlayAdInfo) {
        // Load the next ad when current one is closed
        if (::mInterstitialAd.isInitialized) {
            mInterstitialAd.loadAd()
        }
    }
}

@Composable
fun AutoReviewApp(viewModel: MainViewModel, unrecognizedQuestionState: MutableState<String?>, showInterstitialAd: () -> Unit) {
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

    var accessibilityGranted by remember { mutableStateOf(viewModel.checkAccessibilityEnabled(context)) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                accessibilityGranted = viewModel.checkAccessibilityEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
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
                onBack = { currentScreen = Screen.MAIN }
            )
        }
        Screen.LOGS -> {
            androidx.activity.compose.BackHandler { currentScreen = Screen.MAIN }
            com.example.autoreview.ui.LogsScreen(
                onBack = { currentScreen = Screen.MAIN }
            )
        }
        Screen.MAIN -> {
            MainScreen(
                config = config,
                overlayActive = overlayActive,
                disclosureAccepted = disclosureAccepted,
                accessibilityGranted = accessibilityGranted,
                onDisclosureChanged = { accepted ->
                    disclosureAccepted = accepted
                    prefs.edit { putBoolean("disclosure_accepted", accepted) }
                },
                onToggleOverlay = { 
                    viewModel.toggleOverlay(context)
                    if (!overlayActive) {
                        showInterstitialAd()
                    }
                },
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
                onOpenPermissions = { currentScreen = Screen.PERMISSIONS },
                onOpenLogs = { currentScreen = Screen.LOGS }
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
    accessibilityGranted: Boolean,
    onDisclosureChanged: (Boolean) -> Unit,
    onToggleOverlay: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenPresetSettings: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenLogs: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val snackbarHostState = remember { SnackbarHostState() }

    val overlayGranted = Settings.canDrawOverlays(context)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(title = { Text("AutoReview") })
        },
        bottomBar = {
            com.example.autoreview.ui.BannerAdView(adUnitId = "0ddc0xxzq2u7swcx")
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

            com.example.autoreview.ui.NativeAdViewComposable(adUnitId = "i31n81wo0yf37eeg")

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
                    
                    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                    Text(
                        text = "Privacy Policy",
                        modifier = Modifier
                            .clickable {
                                uriHandler.openUri("https://nawfumi.github.io/autoreview-privacy-policy/")
                            }
                            .padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall.copy(
                            textDecoration = TextDecoration.Underline
                        )
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

                    Button(
                        onClick = onOpenLogs,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("View Debug Logs")
                    }

                    Spacer(Modifier.height(4.dp))

                    val canStart = disclosureAccepted && overlayGranted && accessibilityGranted

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
                    } else if (!accessibilityGranted) {
                        Text(
                            "Enable Accessibility Service first",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            com.example.autoreview.ui.NativeAdViewComposable(adUnitId = "i31n81wo0yf37eeg")

            // Run History
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Run History", fontWeight = FontWeight.Bold)
                    if (config.runHistory.isEmpty()) {
                        Text("No runs yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        var historyExpanded by remember { mutableStateOf(false) }
                        val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault()) }
                        val visibleEntries = if (historyExpanded) config.runHistory else config.runHistory.take(3)
                        
                        visibleEntries.forEach { entry ->
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
                        
                        if (config.runHistory.size > 3) {
                            TextButton(
                                onClick = { historyExpanded = !historyExpanded },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    if (historyExpanded) "Show less" else "Show all (${config.runHistory.size})",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

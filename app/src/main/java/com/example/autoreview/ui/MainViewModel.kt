package com.example.autoreview.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.autoreview.OverlayService
import com.example.autoreview.data.PresetConfig
import com.example.autoreview.data.PresetRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PresetRepository(application)

    private val _presetConfig = MutableStateFlow(PresetConfig())
    val presetConfig: StateFlow<PresetConfig> = _presetConfig.asStateFlow()

    private val _overlayActive = MutableStateFlow(false)
    val overlayActive: StateFlow<Boolean> = _overlayActive.asStateFlow()

    init {
        viewModelScope.launch {
            repository.presetConfig.collect { config ->
                _presetConfig.value = config
            }
        }
        viewModelScope.launch {
            OverlayService.isRunningFlow.collect { isRunning ->
                _overlayActive.value = isRunning
            }
        }
    }

    fun updateDefaultStarRating(rating: Int) {
        viewModelScope.launch {
            val updated = _presetConfig.value.copy(defaultStarRating = rating)
            repository.saveConfig(updated)
        }
    }

    fun updateDefaultBinaryChoice(choice: String) {
        viewModelScope.launch {
            val updated = _presetConfig.value.copy(defaultBinaryChoice = choice)
            repository.saveConfig(updated)
        }
    }

    fun saveConfig(config: PresetConfig) {
        viewModelScope.launch {
            repository.saveConfig(config)
        }
    }

    fun addRunHistoryEntry(success: Boolean, message: String) {
        viewModelScope.launch {
            val entry = com.example.autoreview.data.RunHistoryEntry(
                timestamp = System.currentTimeMillis(),
                success = success,
                message = message
            )
            val currentHistory = _presetConfig.value.runHistory
            val updatedHistory = listOf(entry) + currentHistory.take(19) // Keep last 20
            val updatedConfig = _presetConfig.value.copy(runHistory = updatedHistory)
            repository.saveConfig(updatedConfig)
        }
    }

    fun toggleOverlay(context: Context) {
        if (_overlayActive.value) {
            context.stopService(android.content.Intent(context, OverlayService::class.java))
            _overlayActive.value = false
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(
                    android.content.Intent(context, OverlayService::class.java)
                )
            } else {
                context.startService(android.content.Intent(context, OverlayService::class.java))
            }
            _overlayActive.value = true
        }
    }

    fun refreshOverlayState() {
        // Now handled reactively by StateFlow
    }

    fun checkOverlayPermission(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    fun checkAccessibilityEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
                as? AccessibilityManager ?: return false
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_GENERIC
        )
        return enabledServices.any { it.resolveInfo.serviceInfo.packageName == context.packageName }
    }

    fun checkNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isBatteryOptimizationDisabled(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
}

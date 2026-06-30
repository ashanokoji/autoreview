package com.example.autoreview.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("autoreview_presets")

class PresetRepository(private val context: Context) {

    private val presetKey = stringPreferencesKey("preset_config")

    val presetConfig: Flow<PresetConfig> = context.dataStore.data.map { prefs ->
        val raw = prefs[presetKey] ?: return@map PresetConfig()
        PresetConfig.fromJson(raw)
    }

    suspend fun saveConfig(config: PresetConfig) {
        context.dataStore.edit { prefs ->
            prefs[presetKey] = PresetConfig.toJson(config)
        }
    }
}

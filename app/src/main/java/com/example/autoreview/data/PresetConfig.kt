package com.example.autoreview.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class UnrecognizedPolicy {
    USE_DEFAULTS,
    ASK_USER
}

@Serializable
data class QuestionPreset(
    val questionTextKey: String,
    val starValue: Int? = null,
    val yesNo: Boolean? = null
)

@Serializable
data class RunHistoryEntry(
    val timestamp: Long,
    val success: Boolean,
    val message: String
)

@Serializable
data class PresetConfig(
    val questions: List<QuestionPreset> = emptyList(),
    val defaultStarRating: Int = 4,
    val defaultBinaryChoice: String = "Yes",
    val unrecognizedPolicy: UnrecognizedPolicy = UnrecognizedPolicy.USE_DEFAULTS,
    val runHistory: List<RunHistoryEntry> = emptyList(),
    val automationSpeed: Float = 2.0f
) {
    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun fromJson(raw: String): PresetConfig = try {
            json.decodeFromString(serializer(), raw)
        } catch (_: Exception) {
            PresetConfig()
        }

        fun toJson(config: PresetConfig): String {
            return json.encodeToString(serializer(), config)
        }
    }
}

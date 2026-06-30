package com.example.autoreview.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.autoreview.data.PresetConfig
import com.example.autoreview.data.QuestionPreset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetSettingsScreen(
    config: PresetConfig,
    onConfigChanged: (PresetConfig) -> Unit,
    onSeedFromScan: () -> Unit,
    onBack: () -> Unit
) {
    var localConfig by remember(config) { mutableStateOf(config) }
    var newQuestionText by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    fun save() = onConfigChanged(localConfig)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "Preset Configuration",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        // Default star rating
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Default Star Rating (for all question blocks)", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..5).forEach { star ->
                        FilterChip(
                            selected = localConfig.defaultStarRating == star,
                            onClick = {
                                localConfig = localConfig.copy(defaultStarRating = star)
                                save()
                            },
                            label = { Text("$star") }
                        )
                    }
                }
            }
        }

        // Default binary choice
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Default Binary Selection (for final question)", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Yes", "No").forEach { choice ->
                        FilterChip(
                            selected = localConfig.defaultBinaryChoice == choice,
                            onClick = {
                                localConfig = localConfig.copy(defaultBinaryChoice = choice)
                                save()
                            },
                            label = { Text(choice) }
                        )
                    }
                }
            }
        }

        // Per-question presets
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Per-Question Overrides (optional)", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Add specific questions to override the defaults:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                localConfig.questions.forEachIndexed { index, preset ->
                    QuestionPresetRow(
                        preset = preset,
                        onUpdate = { updated ->
                            val list = localConfig.questions.toMutableList()
                            list[index] = updated
                            localConfig = localConfig.copy(questions = list)
                            save()
                        },
                        onDelete = {
                            val list = localConfig.questions.toMutableList()
                            list.removeAt(index)
                            localConfig = localConfig.copy(questions = list)
                            save()
                        }
                    )
                    if (index < localConfig.questions.lastIndex) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newQuestionText,
                        onValueChange = { newQuestionText = it },
                        label = { Text("Question text") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    FilledTonalButton(
                        onClick = {
                            if (newQuestionText.isNotBlank()) {
                                val newPreset = QuestionPreset(
                                    questionTextKey = newQuestionText.trim(),
                                    starValue = localConfig.defaultStarRating
                                )
                                localConfig = localConfig.copy(
                                    questions = localConfig.questions + newPreset
                                )
                                newQuestionText = ""
                                save()
                            }
                        },
                        enabled = newQuestionText.isNotBlank()
                    ) {
                        Text("Add")
                    }
                }

                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onSeedFromScan,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Seed from Live Scan")
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Done")
        }
    }
}

@Composable
private fun QuestionPresetRow(
    preset: QuestionPreset,
    onUpdate: (QuestionPreset) -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = preset.questionTextKey,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2
        )

        if (preset.yesNo != null) {
            // Binary question
            AssistChip(
                onClick = {
                    onUpdate(
                        preset.copy(
                            yesNo = !preset.yesNo,
                            starValue = null
                        )
                    )
                },
                label = { Text(if (preset.yesNo) "Yes" else "No") }
            )
        } else {
            // Star rating question
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                (1..5).forEach { star ->
                    FilterChip(
                        selected = preset.starValue == star,
                        onClick = {
                            onUpdate(
                                preset.copy(
                                    starValue = star,
                                    yesNo = null
                                )
                            )
                        },
                        label = { Text("$star", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.height(32.dp)
                    )
                }
            }
        }

        IconButton(onClick = onDelete) {
            Text("X", style = MaterialTheme.typography.labelSmall)
        }
    }
}

package com.example.autoreview.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.autoreview.data.PresetConfig
import com.example.autoreview.data.QuestionPreset

@Composable
fun UnrecognizedQuestionScreen(
    unrecognizedText: String,
    config: PresetConfig,
    onSave: (PresetConfig) -> Unit,
    onCancel: () -> Unit
) {
    var starValue by remember { mutableStateOf<Int?>(config.defaultStarRating) }
    var yesNo by remember { mutableStateOf<Boolean?>(null) }
    var isBinary by remember { mutableStateOf(false) }

    Scaffold(
        bottomBar = {
            BannerAdView(adUnitId = "0ddc0xxzq2u7swcx")
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
        Text(
            "Unrecognized Question",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error
        )
        Text(
            "The automation encountered a question it didn't recognize and was forced to stop to avoid guessing incorrectly.",
            style = MaterialTheme.typography.bodyMedium
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Question Text:", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.height(8.dp))
                Text(unrecognizedText, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("Map this question to a preset:", fontWeight = FontWeight.Bold)

        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = !isBinary,
                onClick = { isBinary = false; yesNo = null; starValue = config.defaultStarRating }
            )
            Text("Star Rating")
            Spacer(Modifier.width(16.dp))
            RadioButton(
                selected = isBinary,
                onClick = { isBinary = true; starValue = null; yesNo = true }
            )
            Text("Yes/No")
        }

        if (!isBinary) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..5).forEach { star ->
                    FilterChip(
                        selected = starValue == star,
                        onClick = { starValue = star },
                        label = { Text("$star") }
                    )
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = yesNo == true,
                    onClick = { yesNo = true },
                    label = { Text("Yes") }
                )
                FilterChip(
                    selected = yesNo == false,
                    onClick = { yesNo = false },
                    label = { Text("No") }
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f)
            ) {
                Text("Cancel")
            }
            Button(
                onClick = {
                    val newPreset = QuestionPreset(
                        questionTextKey = unrecognizedText,
                        starValue = starValue,
                        yesNo = yesNo
                    )
                    val newList = config.questions.toMutableList()
                    // Remove if somehow already exists
                    newList.removeAll { it.questionTextKey == unrecognizedText }
                    newList.add(newPreset)
                    
                    onSave(config.copy(questions = newList))
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Save Mapping")
            }
        }
    }
}
}

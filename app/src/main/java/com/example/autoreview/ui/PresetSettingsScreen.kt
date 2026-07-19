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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.example.autoreview.data.PresetConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetSettingsScreen(
    config: PresetConfig,
    onConfigChanged: (PresetConfig) -> Unit,
    onBack: () -> Unit
) {
    var localConfig by remember(config) { mutableStateOf(config) }
    var newQuestionText by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    fun save() = onConfigChanged(localConfig)

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .systemBarsPadding()
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
                    Text(
                        "Default Star Rating (for all question blocks)",
                        fontWeight = FontWeight.SemiBold
                    )
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
                    Text(
                        "Default Binary Selection (for final question)",
                        fontWeight = FontWeight.SemiBold
                    )
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

            // Pro Advertisement
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Box {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(12.dp))
                            ) {
                                androidx.compose.foundation.Image(
                                    painter = androidx.compose.ui.res.painterResource(id = com.example.autoreview.R.drawable.ic_pro_logo_background),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize()
                                )
                                androidx.compose.foundation.Image(
                                    painter = androidx.compose.ui.res.painterResource(id = com.example.autoreview.R.drawable.ic_pro_logo_foreground),
                                    contentDescription = "AutoReview Pro Logo",
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Spacer(Modifier.width(16.dp))
                            Text(
                                text = "AutoReview Pro",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        Text(
                            "Supercharge your review workflow with premium features designed for speed and flexibility.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )

                        Spacer(Modifier.height(16.dp))

                        val features = listOf(
                            "🚀 Highlighted Feature: Review submission in 4 sec!",
                            "⭐ Add per-question overrides for default stars",
                            "🎨 Use customized presets for different scenarios",
                            "📖 Enjoy a customized manga theme"
                        )

                        features.forEach { feature ->
                            Row(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text(
                                    text = feature,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (feature.contains("Highlighted")) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }

                        Spacer(Modifier.height(20.dp))

                        Button(
                            onClick = {
                                com.aptabase.Aptabase.instance.trackEvent("premium_link_clicked")
                                /* TODO: Open Pro link */
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Text("Learn More (example.com/pro)", fontWeight = FontWeight.Bold)
                        }
                    }

                    // PREMIUM PRO Sticker in the top right corner of the Card
                    Surface(
                        color = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary,
                        shape = RoundedCornerShape(bottomStart = 12.dp),
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Text(
                            text = "PREMIUM",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Developer Info Card (Animated & Premium)
            var devCardVisible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(150)
                devCardVisible = true
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = devCardVisible,
                enter = androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(500)) +
                        androidx.compose.animation.slideInVertically(
                            initialOffsetY = { 50 },
                            animationSpec = androidx.compose.animation.core.spring(
                                dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                                stiffness = androidx.compose.animation.core.Spring.StiffnessLow
                            )
                        )
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                androidx.compose.ui.graphics.Brush.linearGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primaryContainer,
                                        MaterialTheme.colorScheme.secondaryContainer
                                    )
                                )
                            )
                    ) {
                        Row(
                            modifier = Modifier.padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(50))
                                    .border(
                                        width = 3.dp,
                                        brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                            colors = listOf(
                                                MaterialTheme.colorScheme.primary,
                                                MaterialTheme.colorScheme.tertiary
                                            )
                                        ),
                                        shape = RoundedCornerShape(50)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                coil.compose.AsyncImage(
                                    model = "https://raw.githubusercontent.com/nawfumi/autoreview/main/app/src/main/res/dev.jpg",
                                    contentDescription = "Developer Profile",
                                    modifier = Modifier
                                        .size(66.dp)
                                        .clip(RoundedCornerShape(50)),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                )
                            }
                            
                            Spacer(Modifier.width(20.dp))
                            
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Shamim Arshad Sajid",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = "Developer & Maintainer",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                )
                                Spacer(Modifier.height(6.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "AFMC-24",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                                Spacer(Modifier.height(12.dp))
                                
                                val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                                Button(
                                    onClick = { uriHandler.openUri("https://www.facebook.com/whorshad") /* TODO: Update exact profile link */ },
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                                    modifier = Modifier.height(36.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    ),
                                    shape = RoundedCornerShape(50)
                                ) {
                                    Text("Contact on Facebook", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Done")
            }
        }
    }
}

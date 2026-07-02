package com.example.autoreview.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import android.widget.Toast
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.autoreview.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    var logsText by remember { mutableStateOf("Loading...") }
    val scrollState = rememberScrollState()

    fun loadLogs() {
        coroutineScope.launch {
            val text = withContext(Dispatchers.IO) {
                AppLogger.getLogs(context)
            }
            logsText = text
        }
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            loadLogs()
            kotlinx.coroutines.delay(2000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug Logs") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("<", style = MaterialTheme.typography.titleLarge)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        coroutineScope.launch {
                            withContext(Dispatchers.IO) {
                                AppLogger.clearLogs(context)
                            }
                            loadLogs()
                        }
                    }) {
                        Text("Clear", style = MaterialTheme.typography.labelMedium)
                    }
                    IconButton(onClick = {
                        clipboardManager.setText(AnnotatedString(logsText))
                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("Copy", style = MaterialTheme.typography.labelMedium)
                    }
                    IconButton(onClick = {
                        val file = AppLogger.getLogFile(context)
                        if (file.exists()) {
                            try {
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.provider",
                                    file
                                )
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, "Share logs"))
                            } catch (e: Exception) {
                                AppLogger.e("LogsScreen", "Failed to share logs", e)
                            }
                        }
                    }) {
                        Text("Share", style = MaterialTheme.typography.labelMedium)
                    }
                }
            )
        }
    ) { padding ->
        SelectionContainer {
            Text(
                text = logsText,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(scrollState),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            )
        }
    }
}

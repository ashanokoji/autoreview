package com.example.autoreview.util

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private const val MAX_LOG_SIZE = 2 * 1024 * 1024 // 2 MB

    fun init(context: Context) {
        if (logFile == null) {
            logFile = File(context.filesDir, "debug_logs.txt")
        }
    }

    fun d(tag: String, msg: String) {
        // Log.d is stripped in release builds, so always write to file first
        appendLog("DEBUG", tag, msg)
        // Also log to logcat for debug builds (no-op if stripped)
        try { Log.d(tag, msg) } catch (_: Exception) {}
    }

    fun i(tag: String, msg: String) {
        appendLog("INFO", tag, msg)
        try { Log.i(tag, msg) } catch (_: Exception) {}
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        val fullMsg = if (t != null) "$msg\n${Log.getStackTraceString(t)}" else msg
        appendLog("ERROR", tag, fullMsg)
        try { Log.e(tag, msg, t) } catch (_: Exception) {}
    }

    fun w(tag: String, msg: String) {
        appendLog("WARN", tag, msg)
        try { Log.w(tag, msg) } catch (_: Exception) {}
    }

    @Synchronized
    private fun appendLog(level: String, tag: String, msg: String) {
        val file = logFile ?: return
        
        try {
            // Roll over if too large
            if (file.exists() && file.length() > MAX_LOG_SIZE) {
                val oldFile = File(file.parent, "debug_logs_old.txt")
                if (oldFile.exists()) oldFile.delete()
                file.renameTo(oldFile)
            }
            
            val timestamp = dateFormat.format(Date())
            val logLine = "$timestamp [$level] $tag: $msg\n"
            file.appendText(logLine)
        } catch (e: Exception) {
            // Last resort - try logcat
            try { Log.e("AppLogger", "Failed to write log", e) } catch (_: Exception) {}
        }
    }

    /**
     * Logs device information for debugging device-specific issues.
     */
    fun logDeviceInfo(tag: String) {
        val info = buildString {
            appendLine("=== DEVICE INFO ===")
            appendLine("Manufacturer: ${Build.MANUFACTURER}")
            appendLine("Model: ${Build.MODEL}")
            appendLine("Brand: ${Build.BRAND}")
            appendLine("Device: ${Build.DEVICE}")
            appendLine("Product: ${Build.PRODUCT}")
            appendLine("Android Version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Hardware: ${Build.HARDWARE}")
            appendLine("Board: ${Build.BOARD}")
            appendLine("=== END DEVICE INFO ===")
        }
        i(tag, info)
    }

    /**
     * Dumps the full UI tree from the given root node with detailed information
     * about each node including bounds, states, class, text, description, etc.
     * This is critical for debugging why automation fails on specific devices.
     */
    fun dumpFullUiTree(tag: String, root: AccessibilityNodeInfo, screenWidth: Int, screenHeight: Int, density: Float) {
        val sb = StringBuilder()
        sb.appendLine("=== FULL UI TREE DUMP ===")
        sb.appendLine("Screen: ${screenWidth}x${screenHeight} @ ${density}x density")
        sb.appendLine("DPI: ${(density * 160).toInt()}")
        sb.appendLine("Package: ${root.packageName}")
        sb.appendLine("Window: ${root.window}")
        var nodeCount = 0

        fun dumpNode(node: AccessibilityNodeInfo, depth: Int) {
            nodeCount++
            val indent = "  ".repeat(depth)
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            
            val flags = buildString {
                if (node.isClickable) append("CLICK ")
                if (node.isScrollable) append("SCROLL ")
                if (node.isEnabled) append("ENABLED ")
                if (node.isSelected) append("SELECTED ")
                if (node.isCheckable) append("CHECKABLE ")
                if (node.isChecked) append("CHECKED ")
                if (node.isFocusable) append("FOCUSABLE ")
                if (node.isFocused) append("FOCUSED ")
                if (node.isVisibleToUser) append("VISIBLE ")
                if (node.isLongClickable) append("LONGCLICK ")
                if (node.isEditable) append("EDITABLE ")
            }.trim()
            
            val text = node.text?.toString()?.take(80) ?: ""
            val desc = node.contentDescription?.toString()?.take(80) ?: ""
            val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
            val viewId = node.viewIdResourceName ?: ""
            
            sb.appendLine("$indent[$cls] bounds=$rect text=\"$text\" desc=\"$desc\" id=\"$viewId\" flags=[$flags] children=${node.childCount}")
            
            for (i in 0 until node.childCount) {
                try {
                    node.getChild(i)?.let { child ->
                        dumpNode(child, depth + 1)
                        child.recycle()
                    }
                } catch (e: Exception) {
                    sb.appendLine("$indent  <error getting child $i: ${e.message}>")
                }
            }
        }

        try {
            dumpNode(root, 0)
        } catch (e: Exception) {
            sb.appendLine("<error during tree dump: ${e.message}>")
        }
        
        sb.appendLine("Total nodes: $nodeCount")
        sb.appendLine("=== END UI TREE DUMP ===")
        
        // Split into chunks if very large (file logger handles it, but keeps each write reasonable)
        val fullDump = sb.toString()
        if (fullDump.length > 8000) {
            val lines = fullDump.lines()
            val chunkSize = 100
            for (i in lines.indices step chunkSize) {
                val chunk = lines.subList(i, minOf(i + chunkSize, lines.size)).joinToString("\n")
                d(tag, chunk)
            }
        } else {
            d(tag, fullDump)
        }
    }

    fun getLogs(context: Context): String {
        val file = logFile ?: File(context.filesDir, "debug_logs.txt")
        return try {
            if (file.exists()) file.readText() else "No logs found."
        } catch (e: Exception) {
            "Failed to read logs: ${e.message}"
        }
    }

    fun getLogFile(context: Context): File {
        return logFile ?: File(context.filesDir, "debug_logs.txt")
    }

    fun clearLogs(context: Context) {
        val file = logFile ?: File(context.filesDir, "debug_logs.txt")
        if (file.exists()) {
            file.delete()
        }
        val oldFile = File(context.filesDir, "debug_logs_old.txt")
        if (oldFile.exists()) {
            oldFile.delete()
        }
    }
}

package com.example.autoreview

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.example.autoreview.data.PresetRepository
import com.example.autoreview.service.NodeFinder
import com.example.autoreview.service.QuestionMatcher
import com.example.autoreview.service.QuestionType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

@SuppressLint("AccessibilityPolicy")
class AutoFillAccessibilityService : AccessibilityService() {
    
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var automationJob: Job? = null

    val automationRunning = AtomicBoolean(false)
    private val shouldStop = AtomicBoolean(false)

    private lateinit var presetRepository: PresetRepository

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        presetRepository = PresetRepository(this)
        Log.d(TAG, "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { }

    override fun onInterrupt() {
        stopAutomation()
    }

    override fun onDestroy() {
        instance = null
        serviceJob.cancel()
        super.onDestroy()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun startAutomation() {
        if (automationRunning.getAndSet(true)) return

        shouldStop.set(false)
        updateState(OverlayService.AutomationState.RUNNING)
        Log.d(TAG, "Automation started")

        automationJob = serviceScope.launch {
            try {
                performAutomation()
            } catch (e: CancellationException) {
                Log.d(TAG, "Automation cancelled")
                if (!shouldStop.get()) updateState(OverlayService.AutomationState.IDLE)
                serviceScope.launch { logHistory(false, "Cancelled by user") }
            } catch (e: Exception) {
                Log.e(TAG, "Automation error", e)
                updateState(OverlayService.AutomationState.ERROR)
                serviceScope.launch { logHistory(false, "Error: ${e.message}") }
            } finally {
                automationRunning.set(false)
            }
        }
    }

    fun stopAutomation() {
        if (!automationRunning.get()) return
        shouldStop.set(true)
        automationJob?.cancel()
        updateState(OverlayService.AutomationState.IDLE)
        Log.d(TAG, "Automation stop requested")
    }

    private fun updateState(state: OverlayService.AutomationState) {
        OverlayService.updateState(this, state)
    }

    private suspend fun logHistory(success: Boolean, message: String) {
        val config = presetRepository.presetConfig.first()
        val entry = com.example.autoreview.data.RunHistoryEntry(
            timestamp = System.currentTimeMillis(),
            success = success,
            message = message
        )
        val updatedHistory = listOf(entry) + config.runHistory.take(19)
        presetRepository.saveConfig(config.copy(runHistory = updatedHistory))
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun performAutomation() = withContext(Dispatchers.IO) {
        val config = presetRepository.presetConfig.first()

        // Wait for "Write Review" button and click it if we are on schedule screen
        var root = waitForRoot()
        
        if (root.packageName?.toString() != TARGET_PACKAGE) {
            Log.e(TAG, "Not in target app: ${root.packageName}")
            updateState(OverlayService.AutomationState.ERROR)
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(this@AutoFillAccessibilityService, "Must be in My AFMC app", android.widget.Toast.LENGTH_SHORT).show()
            }
            logHistory(false, "Must be in target app")
            return@withContext
        }

        val writeReviewBtn = NodeFinder.findNodeByText(root, "Write Review", true)
            ?: NodeFinder.findNodeByDescription(root, "Write Review")
        
        if (writeReviewBtn != null) {
            Log.d(TAG, "Found Write Review button, clicking...")
            val success = NodeFinder.performClickOnNodeOrParent(writeReviewBtn, this@AutoFillAccessibilityService, TAG)
            Log.d(TAG, "Write Review click success: $success")
            writeReviewBtn.recycle()
            delay(1000.milliseconds)
            root = waitForRoot()
        }

        val answered = mutableSetOf<String>()
        var scrollAttempts = 0

        while (isActive && !shouldStop.get()) {
            root = waitForRoot()
            val discovered = NodeFinder.discoverQuestionCards(root)
            val unhandled = discovered.filter { it.questionText !in answered }
            
            if (scrollAttempts == 0 && answered.isEmpty()) {
                Log.d(TAG, "--- DUMPING UI TREE ---")
                fun dumpNode(n: AccessibilityNodeInfo, indent: String = "") {
                    Log.d(TAG, "$indent[${n.className}] text='${n.text}' desc='${n.contentDescription}' isClickable=${n.isClickable}")
                    for (i in 0 until n.childCount) {
                        n.getChild(i)?.let { 
                            dumpNode(it, "$indent  ")
                            it.recycle()
                        }
                    }
                }
                dumpNode(root)
                Log.d(TAG, "--- END UI TREE ---")
            }
            
            Log.d(TAG, "Discovered ${discovered.size} questions, ${unhandled.size} unhandled")

            // Clean up interactive nodes for questions we've already answered
            discovered.filter { it.questionText in answered }.forEach {
                it.interactiveNodes.forEach { n -> n.recycle() }
                it.cardRoot.recycle()
            }

            if (unhandled.isEmpty()) {
                Log.d(TAG, "No unhandled questions. Scrolling forward (attempt $scrollAttempts)")
                val container = NodeFinder.findScrollableContainer(root)
                val scrolled = NodeFinder.performScrollForward(container)
                container?.recycle()
                if (!scrolled || scrollAttempts > 10) {
                    Log.d(TAG, "Reached end of list or cannot scroll anymore.")
                    break // End of list
                }
                scrollAttempts++
                delay((100..250).random().toLong().milliseconds)
                continue
            }
            scrollAttempts = 0

            for (q in unhandled) {
                if (!isActive || shouldStop.get()) {
                    unhandled.forEach { uq -> 
                        uq.interactiveNodes.forEach { it.recycle() }
                        uq.cardRoot.recycle()
                    }
                    return@withContext
                }
                
                q.cardRoot.recycle()
                var preset = QuestionMatcher.bestMatch(q.questionText, config.questions)
                
                if (preset == null) {
                    Log.w(TAG, "Unrecognized question: \"${q.questionText}\". Using defaults.")
                    // Create a dummy preset with the global defaults so it can proceed
                    preset = com.example.autoreview.data.QuestionPreset(
                        questionTextKey = q.questionText,
                        starValue = config.defaultStarRating,
                        yesNo = (config.defaultBinaryChoice == "Yes")
                    )
                }

                if (q.type == QuestionType.STAR_RATING) {
                    val stars = q.interactiveNodes
                    val starVal = preset.starValue ?: config.defaultStarRating
                    if (stars.size == 5 && starVal in 1..5) {
                        Log.d(TAG, "Clicking star $starVal for question: ${q.questionText}")
                        val success = NodeFinder.performClickOnNodeOrParent(stars[starVal - 1], this@AutoFillAccessibilityService, TAG)
                        Log.d(TAG, "Star click success: $success")
                        delay((100..200).random().toLong().milliseconds)
                    }
                } else if (q.type == QuestionType.YES_NO) {
                    val yesNo = q.interactiveNodes
                    if (yesNo.size == 2) {
                        val choice = preset.yesNo ?: (config.defaultBinaryChoice == "Yes")
                        val target = if (choice) yesNo[0] else yesNo[1]
                        Log.d(TAG, "Clicking $choice for question: ${q.questionText}")
                        val success = NodeFinder.performClickOnNodeOrParent(target, this@AutoFillAccessibilityService, TAG)
                        Log.d(TAG, "Yes/No click success: $success")
                        delay((100..200).random().toLong().milliseconds)
                    }
                }
                
                q.interactiveNodes.forEach { it.recycle() }
                answered.add(q.questionText)
            }
        }
        
        if (shouldStop.get()) return@withContext

        // Poll for submit
        var submitBtn = NodeFinder.findEnabledSubmit(root)
        
        for (i in 1..20) {
            if (submitBtn != null) break
            delay(500.milliseconds)
            
            root.recycle() // Recycle old root
            root = waitForRoot()
            
            submitBtn = NodeFinder.findEnabledSubmit(root)
        }
        
        if (submitBtn != null) {
            Log.d(TAG, "Found Submit button, clicking...")
            delay((200..400).random().toLong().milliseconds)
            val success = NodeFinder.performClickOnNodeOrParent(submitBtn, this@AutoFillAccessibilityService, TAG)
            Log.d(TAG, "Submit click success: $success")
            submitBtn.recycle()
            updateState(OverlayService.AutomationState.DONE)
            Log.d(TAG, "Automation finished successfully")
            logHistory(true, "Completed review successfully")
        } else {
            Log.e(TAG, "Submit button not found or not enabled")
            updateState(OverlayService.AutomationState.ERROR)
            logHistory(false, "Submit button not found")
        }
    }

    private suspend fun waitForRoot(): AccessibilityNodeInfo {
        var root = rootInActiveWindow
        var attempts = 0
        while (root == null && attempts < 10) {
            delay(300.milliseconds)
            root = rootInActiveWindow
            attempts++
        }
        return root ?: throw IllegalStateException("Window root is null")
    }
    


    companion object {
        private const val TAG = "AutoReview"
        private const val TARGET_PACKAGE = "com.example.afmc_auto"
        
        @Volatile
        var instance: AutoFillAccessibilityService? = null
            private set
    }
}

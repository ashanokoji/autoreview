@file:Suppress("DEPRECATION")

package com.example.autoreview

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.example.autoreview.data.PresetRepository
import com.example.autoreview.service.NodeFinder
import com.example.autoreview.service.QuestionType
import com.example.autoreview.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        AppLogger.init(applicationContext)
        AppLogger.d(TAG, "Service connected")
        AppLogger.logDeviceInfo(TAG)
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

    fun startAutomation() {
        if (automationRunning.getAndSet(true)) return

        shouldStop.set(false)
        updateState(OverlayService.AutomationState.RUNNING)
        AppLogger.d(TAG, "Automation started")

        automationJob = serviceScope.launch {
            try {
                performAutomation()
            } catch (e: CancellationException) {
                AppLogger.d(TAG, "Automation cancelled")
                if (!shouldStop.get()) updateState(OverlayService.AutomationState.IDLE)
                serviceScope.launch { logHistory(false, "Cancelled by user") }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Automation error", e)
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
        AppLogger.d(TAG, "Automation stop requested")
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

    private suspend fun performAutomation() = withContext(Dispatchers.IO) {
        val config = presetRepository.presetConfig.first()
        val dm = resources.displayMetrics
        val screenHeight = dm.heightPixels
        val screenWidth = dm.widthPixels

        AppLogger.i(TAG, "Screen: ${screenWidth}x${screenHeight}, speed=${config.automationSpeed}")

        // Wait for "Write Review" button and click it if we are on schedule screen
        var root = waitForRoot()
        
        if (root.packageName?.toString() != TARGET_PACKAGE) {
            AppLogger.e(TAG, "Not in target app: ${root.packageName}")
            updateState(OverlayService.AutomationState.ERROR)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@AutoFillAccessibilityService, "Must be in My AFMC app", Toast.LENGTH_SHORT).show()
            }
            logHistory(false, "Must be in target app")
            return@withContext
        }

        val writeReviewBtn = NodeFinder.findNodeByText(root, "Write Review", true)
            ?: NodeFinder.findNodeByDescription(root, "Write Review")
        
        if (writeReviewBtn != null) {
            AppLogger.d(TAG, "Found Write Review button, clicking...")
            val success = NodeFinder.performClickOnNodeOrParent(writeReviewBtn, this@AutoFillAccessibilityService, TAG)
            AppLogger.d(TAG, "Write Review click success: $success")
            writeReviewBtn.recycle()
            delayScaled(1000L, config.automationSpeed)
            root = waitForRoot()
        } else {
            // No Write Review button — either already submitted or no review available
            AppLogger.w(TAG, "No Write Review button found")
            updateState(OverlayService.AutomationState.ERROR)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@AutoFillAccessibilityService, "No review to submit or already submitted", Toast.LENGTH_SHORT).show()
            }
            logHistory(false, "No Write Review button found")
            return@withContext
        }

        // Simple text-based tracking — strip number prefix so "1\nStarts class..." 
        // and "Starts class..." are treated as the same question
        val answered = mutableSetOf<String>()
        var scrollAttempts = 0

        // === MAIN LOOP: discover → click → scroll → repeat ===
        while (isActive && !shouldStop.get()) {
            root.recycle()
            root = waitForRoot()
            
            // Check for submit button — if visible, we're done with questions
            val earlySubmit = NodeFinder.findEnabledSubmit(root)
            if (earlySubmit != null && answered.isNotEmpty()) {
                AppLogger.d(TAG, "Submit button visible! Clicking immediately...")
                NodeFinder.performClickOnNodeOrParent(earlySubmit, this@AutoFillAccessibilityService, TAG)
                earlySubmit.recycle()
                updateState(OverlayService.AutomationState.DONE)
                AppLogger.d(TAG, "Automation finished (${answered.size} questions)")
                logHistory(true, "Completed review (${answered.size} questions)")
                return@withContext
            }
            earlySubmit?.recycle()
            
            val discovered = NodeFinder.discoverQuestionCards(root)
            val unhandled = discovered.filter { stripNumberPrefix(it.questionText) !in answered }
            
            AppLogger.d(TAG, "Discovered ${discovered.size} questions, ${unhandled.size} unhandled, scrollAttempts=$scrollAttempts")

            // Clean up already-answered
            discovered.filter { stripNumberPrefix(it.questionText) in answered }.forEach {
                it.interactiveNodes.forEach { n -> n.recycle() }
                it.cardRoot.recycle()
            }

            val container = NodeFinder.findScrollableContainer(root)
            val containerRect = android.graphics.Rect().apply { container?.getBoundsInScreen(this) }
            
            val safeUnhandled = unhandled.filter { q ->
                q.interactiveNodes.all { node ->
                    NodeFinder.isNodeVisible(node, if (containerRect.isEmpty) null else containerRect, screenHeight, screenWidth)
                }
            }

            if (safeUnhandled.isEmpty()) {
                if (unhandled.isEmpty()) {
                    AppLogger.d(TAG, "No unhandled questions. Scrolling forward (attempt $scrollAttempts)")
                } else {
                    AppLogger.d(TAG, "Questions found but not fully visible. Scrolling forward (attempt $scrollAttempts)")
                }
                var scrolled = NodeFinder.performScrollForward(container)
                if (!scrolled) {
                    scrolled = NodeFinder.performGestureScroll(this@AutoFillAccessibilityService, screenHeight, screenWidth, forward = true)
                }
                container?.recycle()
                if (!scrolled || scrollAttempts > 15) {
                    if (unhandled.isNotEmpty()) {
                        AppLogger.d(TAG, "Cannot scroll anymore, processing remaining questions as fallback.")
                    } else {
                        AppLogger.d(TAG, "Reached end of list.")
                        break
                    }
                } else {
                    scrollAttempts++
                    delayScaled(100..250, config.automationSpeed)
                    continue
                }
            } else {
                container?.recycle()
            }
            scrollAttempts = 0

            val toProcess = safeUnhandled.ifEmpty { unhandled }
            for (q in toProcess) {
                if (!isActive || shouldStop.get()) {
                    unhandled.forEach { uq -> 
                        uq.interactiveNodes.forEach { it.recycle() }
                        uq.cardRoot.recycle()
                    }
                    return@withContext
                }
                
                q.cardRoot.recycle()
                var preset = com.example.autoreview.service.QuestionMatcher.bestMatch(q.questionText, config.questions)
                
                if (preset == null) {
                    if (config.unrecognizedPolicy == com.example.autoreview.data.UnrecognizedPolicy.ASK_USER) {
                        AppLogger.w(TAG, "Unrecognized question: \"${q.questionText}\". Asking user for mapping.")
                        shouldStop.set(true)
                        val intent = Intent(this@AutoFillAccessibilityService, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            putExtra("unrecognized_question", q.questionText)
                        }
                        startActivity(intent)
                        updateState(OverlayService.AutomationState.IDLE)
                        logHistory(false, "Paused for unrecognized question")
                        
                        unhandled.forEach { uq -> 
                            uq.interactiveNodes.forEach { n -> n.recycle() }
                            uq.cardRoot.recycle()
                        }
                        return@withContext
                    } else {
                        AppLogger.w(TAG, "Unrecognized question: \"${q.questionText}\". Using defaults.")
                        preset = com.example.autoreview.data.QuestionPreset(
                            questionTextKey = q.questionText,
                            starValue = config.defaultStarRating,
                            yesNo = (config.defaultBinaryChoice == "Yes")
                        )
                    }
                }

                if (q.type == QuestionType.STAR_RATING) {
                    val stars = q.interactiveNodes
                    val starVal = preset.starValue ?: config.defaultStarRating
                    if (stars.size == 5 && starVal in 1..5) {
                        AppLogger.d(TAG, "Clicking star $starVal for: ${q.questionText.take(50)}")
                        NodeFinder.performClickOnNodeOrParent(stars[starVal - 1], this@AutoFillAccessibilityService, TAG)
                        delayScaled(100..200, config.automationSpeed)
                    }
                } else if (q.type == QuestionType.YES_NO) {
                    val yesNo = q.interactiveNodes
                    val choice = preset.yesNo ?: (config.defaultBinaryChoice == "Yes")
                    if (yesNo.size == 2) {
                        val target = if (choice) yesNo[0] else yesNo[1]
                        AppLogger.d(TAG, "Clicking ${if (choice) "Yes" else "No"} for: ${q.questionText.take(50)}")
                        NodeFinder.performClickOnNodeOrParent(target, this@AutoFillAccessibilityService, TAG)
                        delayScaled(100..200, config.automationSpeed)
                    }
                }
                
                q.interactiveNodes.forEach { it.recycle() }
                answered.add(stripNumberPrefix(q.questionText))
            }
        }
        
        if (shouldStop.get()) return@withContext

        // === SUBMIT FALLBACK: only reached if submit wasn't found during main loop ===
        AppLogger.d(TAG, "Submit not found during main loop, scrolling to find it...")
        for (i in 1..5) {
            root.recycle()
            root = waitForRoot()
            
            val submitBtn = NodeFinder.findEnabledSubmit(root)
            if (submitBtn != null) {
                AppLogger.d(TAG, "Found Submit button, clicking...")
                NodeFinder.performClickOnNodeOrParent(submitBtn, this@AutoFillAccessibilityService, TAG)
                submitBtn.recycle()
                updateState(OverlayService.AutomationState.DONE)
                AppLogger.d(TAG, "Automation finished (${answered.size} questions)")
                logHistory(true, "Completed review (${answered.size} questions)")
                return@withContext
            }
            
            val scrollContainer = NodeFinder.findScrollableContainer(root)
            NodeFinder.performScrollForward(scrollContainer)
            scrollContainer?.recycle()
            delayScaled(150L, config.automationSpeed)
        }
        
        AppLogger.e(TAG, "Submit button not found")
        updateState(OverlayService.AutomationState.ERROR)
        logHistory(false, "Submit not found (${answered.size} answered)")
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
    
    private suspend fun delayScaled(baseMs: Long, speed: Float) {
        delay((baseMs * speed).toLong().milliseconds)
    }

    private suspend fun delayScaled(range: IntRange, speed: Float) {
        delay((range.random() * speed).toLong().milliseconds)
    }

    /** Strips leading "1\n", "12\n" etc. so both variants of the same question match */
    private fun stripNumberPrefix(text: String): String {
        return text.trim().replace(Regex("^\\d+\\s*[\\n\\r]+\\s*"), "").trim()
    }


    companion object {
        private const val TAG = "AutoReview"
        private const val TARGET_PACKAGE = "com.example.afmc_auto"
        
        @Volatile
        var instance: AutoFillAccessibilityService? = null
            private set
    }
}

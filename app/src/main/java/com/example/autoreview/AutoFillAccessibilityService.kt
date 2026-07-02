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
import com.example.autoreview.service.QuestionMatcher
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
        }

        val answered = mutableSetOf<String>()
        var scrollAttempts = 0

        while (isActive && !shouldStop.get()) {
            root.recycle()
            root = waitForRoot()
            val discovered = NodeFinder.discoverQuestionCards(root)
            val unhandled = discovered.filter { it.questionText !in answered }
            
            if (scrollAttempts == 0 && answered.isEmpty()) {
                AppLogger.d(TAG, "--- DUMPING UI TREE ---")
                fun dumpNode(n: AccessibilityNodeInfo, indent: String = "") {
                    AppLogger.d(TAG, "$indent[${n.className}] text='${n.text}' desc='${n.contentDescription}' isClickable=${n.isClickable}")
                    for (i in 0 until n.childCount) {
                        n.getChild(i)?.let { 
                            dumpNode(it, "$indent  ")
                            it.recycle()
                        }
                    }
                }
                dumpNode(root)
                AppLogger.d(TAG, "--- END UI TREE ---")
            }
            
            AppLogger.d(TAG, "Discovered ${discovered.size} questions, ${unhandled.size} unhandled")

            // Clean up interactive nodes for questions we've already answered
            discovered.filter { it.questionText in answered }.forEach {
                it.interactiveNodes.forEach { n -> n.recycle() }
                it.cardRoot.recycle()
            }

            val container = NodeFinder.findScrollableContainer(root)
            val containerRect = android.graphics.Rect().apply { container?.getBoundsInScreen(this) }
            val dm = resources.displayMetrics
            
            val safeUnhandled = unhandled.filter { q ->
                q.interactiveNodes.all { node ->
                    NodeFinder.isNodeVisible(node, if (containerRect.isEmpty) null else containerRect, dm.heightPixels, dm.widthPixels)
                }
            }

            if (safeUnhandled.isEmpty()) {
                if (unhandled.isEmpty()) {
                    AppLogger.d(TAG, "No unhandled questions. Scrolling forward (attempt $scrollAttempts)")
                } else {
                    AppLogger.d(TAG, "Questions found but not fully visible. Scrolling forward (attempt $scrollAttempts)")
                }
                val scrolled = NodeFinder.performScrollForward(container)
                container?.recycle()
                if (!scrolled || scrollAttempts > 10) {
                    if (unhandled.isNotEmpty()) {
                        AppLogger.d(TAG, "Cannot scroll anymore, processing remaining partially visible questions as fallback.")
                    } else {
                        AppLogger.d(TAG, "Reached end of list or cannot scroll anymore.")
                        break // End of list
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
                var preset = QuestionMatcher.bestMatch(q.questionText, config.questions)
                
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
                        AppLogger.d(TAG, "Clicking star $starVal for question: ${q.questionText}")
                        val success = NodeFinder.performClickOnNodeOrParent(stars[starVal - 1], this@AutoFillAccessibilityService, TAG)
                        AppLogger.d(TAG, "Star click success: $success")
                        delayScaled(100..200, config.automationSpeed)
                    }
                } else if (q.type == QuestionType.YES_NO) {
                    val yesNo = q.interactiveNodes
                    val choice = preset.yesNo ?: (config.defaultBinaryChoice == "Yes")
                    if (yesNo.size == 2) {
                        val target = if (choice) yesNo[0] else yesNo[1]
                        AppLogger.d(TAG, "Clicking $choice for question: ${q.questionText}")
                        val success = NodeFinder.performClickOnNodeOrParent(target, this@AutoFillAccessibilityService, TAG)
                        AppLogger.d(TAG, "Yes/No click success: $success")
                        delayScaled(100..200, config.automationSpeed)
                    } else if (yesNo.isEmpty()) {
                        AppLogger.d(TAG, "Clicking $choice via coordinate offset for hidden question: ${q.questionText}")
                        val success = NodeFinder.performGestureClickByOffset(q.cardRoot, this@AutoFillAccessibilityService, choice)
                        AppLogger.d(TAG, "Yes/No hidden gesture click success: $success")
                        delayScaled(200..400, config.automationSpeed)
                    }
                }
                
                q.interactiveNodes.forEach { it.recycle() }
                answered.add(q.questionText)
            }
        }
        
        if (shouldStop.get()) return@withContext

        // Poll for submit and ensure it's visible by scrolling if necessary
        var submitBtn: AccessibilityNodeInfo? = null
        val dm = resources.displayMetrics
        
        for (i in 1..20) {
            submitBtn = NodeFinder.findEnabledSubmit(root)
            
            if (submitBtn != null) {
                val container = NodeFinder.findScrollableContainer(root)
                val containerRect = android.graphics.Rect().apply { container?.getBoundsInScreen(this) }
                container?.recycle()
                
                if (NodeFinder.isNodeVisible(submitBtn, if (containerRect.isEmpty) null else containerRect, dm.heightPixels, dm.widthPixels)) {
                    break
                } else {
                    AppLogger.d(TAG, "Submit button found but not fully visible, scrolling...")
                    
                    val scrollContainer = NodeFinder.findScrollableContainer(root)
                    val scrolled = NodeFinder.performScrollForward(scrollContainer)
                    scrollContainer?.recycle()
                    
                    if (!scrolled) {
                        AppLogger.d(TAG, "Cannot scroll further, using partially visible Submit button.")
                        break
                    }
                    
                    submitBtn.recycle()
                    submitBtn = null
                }
            } else {
                val scrollContainer = NodeFinder.findScrollableContainer(root)
                NodeFinder.performScrollForward(scrollContainer)
                scrollContainer?.recycle()
            }
            
            delayScaled(500L, config.automationSpeed)
            root.recycle() // Recycle old root
            root = waitForRoot()
        }
        
        if (submitBtn != null) {
            AppLogger.d(TAG, "Found Submit button, clicking...")
            delayScaled(200..400, config.automationSpeed)
            val success = NodeFinder.performClickOnNodeOrParent(submitBtn, this@AutoFillAccessibilityService, TAG)
            AppLogger.d(TAG, "Submit click success: $success")
            submitBtn.recycle()
            updateState(OverlayService.AutomationState.DONE)
            AppLogger.d(TAG, "Automation finished successfully")
            logHistory(true, "Completed review successfully")
        } else {
            AppLogger.e(TAG, "Submit button not found or not enabled")
            AppLogger.d(TAG, "--- FINAL DUMPING UI TREE ---")
            fun dumpNode(n: AccessibilityNodeInfo, indent: String = "") {
                AppLogger.d(TAG, "$indent[${n.className}] text='${n.text}' desc='${n.contentDescription}' isClickable=${n.isClickable}")
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let { 
                        dumpNode(it, "$indent  ")
                        it.recycle()
                    }
                }
            }
            dumpNode(root)
            AppLogger.d(TAG, "--- END FINAL UI TREE ---")
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
    
    private suspend fun delayScaled(baseMs: Long, speed: Float) {
        delay((baseMs * speed).toLong().milliseconds)
    }

    private suspend fun delayScaled(range: IntRange, speed: Float) {
        delay((range.random() * speed).toLong().milliseconds)
    }


    companion object {
        private const val TAG = "AutoReview"
        private const val TARGET_PACKAGE = "com.example.afmc_auto"
        
        @Volatile
        var instance: AutoFillAccessibilityService? = null
            private set
    }
}

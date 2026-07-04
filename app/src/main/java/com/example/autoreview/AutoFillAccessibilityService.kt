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
        val density = dm.density
        val screenHeight = dm.heightPixels
        val screenWidth = dm.widthPixels

        AppLogger.i(TAG, "Screen: ${screenWidth}x${screenHeight}, density=$density, speed=${config.automationSpeed}")

        // Wait for "Write Review" button and click it if we are on schedule screen
        var root = waitForRoot()
        
        val currentPackage = root.packageName?.toString() ?: ""
        if (currentPackage != TARGET_PACKAGE && currentPackage != "com.example.afmc_auto_demo") {
            AppLogger.e(TAG, "Not in target app: $currentPackage")
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
            // Increased delay for slower devices / tablets
            delayScaled(2000L, config.automationSpeed)
            root = waitForRoot()
        }

        // Dump the full UI tree on first run for debugging
        AppLogger.dumpFullUiTree(TAG, root, screenWidth, screenHeight, density)

        // Track answered questions by QUESTION NUMBER (primary) and text (fallback)
        val answeredByNumber = mutableSetOf<Int>()
        val answeredByText = mutableSetOf<String>()
        var scrollAttempts = 0
        var totalRetryPasses = 0
        val maxRetryPasses = 5
        var totalQuestionsProcessed = 0
        var consecutiveEmptyScrolls = 0

        while (isActive && !shouldStop.get()) {
            root.recycle()
            root = waitForRoot()
            val discovered = NodeFinder.discoverQuestionCards(root)
            
            // Determine which questions are unhandled using question number tracking
            val unhandled = discovered.filter { q ->
                val qNum = q.questionNumber
                if (qNum != null) {
                    qNum !in answeredByNumber
                } else {
                    // Fallback: strip number prefix and check text
                    val strippedText = NodeFinder.stripQuestionNumber(q.questionText)
                    strippedText !in answeredByText && q.questionText !in answeredByText
                }
            }
            
            AppLogger.d(TAG, "Discovered ${discovered.size} questions, ${unhandled.size} unhandled (answered: nums=$answeredByNumber, texts=${answeredByText.size}), scrollAttempts=$scrollAttempts")

            // Clean up interactive nodes for questions we've already answered
            discovered.filter { it !in unhandled }.forEach {
                it.interactiveNodes.forEach { n -> n.recycle() }
                it.cardRoot.recycle()
            }

            val container = NodeFinder.findScrollableContainer(root)
            val containerRect = android.graphics.Rect().apply { container?.getBoundsInScreen(this) }
            
            val safeUnhandled = unhandled.filter { q ->
                q.interactiveNodes.all { node ->
                    NodeFinder.isNodeVisible(node, if (containerRect.isEmpty) null else containerRect, screenHeight, screenWidth, density)
                }
            }

            // Also check for partially visible questions as fallback
            val partiallyVisible = if (safeUnhandled.isEmpty()) {
                unhandled.filter { q ->
                    q.interactiveNodes.all { node ->
                        NodeFinder.isNodePartiallyVisible(node, if (containerRect.isEmpty) null else containerRect, screenHeight, screenWidth, density)
                    }
                }
            } else emptyList()

            if (safeUnhandled.isEmpty() && partiallyVisible.isEmpty()) {
                if (unhandled.isEmpty()) {
                    consecutiveEmptyScrolls++
                    AppLogger.d(TAG, "No unhandled questions visible. Scrolling forward (attempt $scrollAttempts, empty=$consecutiveEmptyScrolls)")
                } else {
                    AppLogger.d(TAG, "Questions found but not visible. Scrolling forward (attempt $scrollAttempts)")
                    consecutiveEmptyScrolls = 0
                }
                
                // Try standard scroll first, then gesture scroll as fallback
                var scrolled = NodeFinder.performScrollForward(container)
                if (!scrolled) {
                    AppLogger.d(TAG, "Standard scroll failed, trying gesture scroll...")
                    scrolled = NodeFinder.performGestureScroll(this@AutoFillAccessibilityService, screenHeight, screenWidth, forward = true)
                }
                container?.recycle()
                
                // Break if we truly can't scroll anymore or have scrolled too many times
                if (!scrolled || scrollAttempts > 20 || consecutiveEmptyScrolls > 5) {
                    if (unhandled.isNotEmpty()) {
                        AppLogger.d(TAG, "Cannot scroll anymore, processing remaining partially visible questions as fallback.")
                        // Fall through to process unhandled directly
                    } else {
                        AppLogger.d(TAG, "Reached end of list or cannot scroll anymore.")
                        break // End of list
                    }
                } else {
                    scrollAttempts++
                    // Increased delay for UI to settle across all devices
                    delayScaled(400..600, config.automationSpeed)
                    continue
                }
            } else {
                container?.recycle()
                consecutiveEmptyScrolls = 0
            }
            scrollAttempts = 0

            val toProcess = safeUnhandled.ifEmpty { partiallyVisible.ifEmpty { unhandled } }
            
            AppLogger.d(TAG, "Processing ${toProcess.size} questions (safe=${safeUnhandled.size}, partial=${partiallyVisible.size}, total_unhandled=${unhandled.size})")
            
            for (q in toProcess) {
                if (!isActive || shouldStop.get()) {
                    unhandled.forEach { uq -> 
                        uq.interactiveNodes.forEach { it.recycle() }
                        uq.cardRoot.recycle()
                    }
                    return@withContext
                }
                
                q.cardRoot.recycle()
                
                // Use stripped text (without number prefix) for matching against presets
                val strippedText = NodeFinder.stripQuestionNumber(q.questionText)
                var preset = com.example.autoreview.service.QuestionMatcher.bestMatch(strippedText, config.questions)
                    ?: com.example.autoreview.service.QuestionMatcher.bestMatch(q.questionText, config.questions)
                
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
                        AppLogger.w(TAG, "Unrecognized question #${q.questionNumber}: \"${strippedText}\". Using defaults.")
                        preset = com.example.autoreview.data.QuestionPreset(
                            questionTextKey = q.questionText,
                            starValue = config.defaultStarRating,
                            yesNo = (config.defaultBinaryChoice == "Yes")
                        )
                    }
                }

                val clickSuccess = processQuestion(q, preset, config)
                AppLogger.d(TAG, "Question #${q.questionNumber} '${strippedText.take(40)}' processed, clickSuccess=$clickSuccess")
                
                q.interactiveNodes.forEach { it.recycle() }
                
                // Track by question number (primary) and text (fallback)
                if (q.questionNumber != null) {
                    answeredByNumber.add(q.questionNumber)
                }
                answeredByText.add(q.questionText)
                answeredByText.add(strippedText)
                totalQuestionsProcessed++
            }
            
            // Cleanup any unhandled questions that weren't processed
            unhandled.filter { it !in toProcess }.forEach { uq ->
                uq.interactiveNodes.forEach { it.recycle() }
                uq.cardRoot.recycle()
            }
        }
        
        if (shouldStop.get()) return@withContext

        AppLogger.i(TAG, "Main pass complete. Total questions processed: $totalQuestionsProcessed, answered nums: $answeredByNumber")

        // === PROGRESS VALIDATION & RETRY ===
        // Check if all questions are actually answered by reading the progress counter
        delayScaled(600L, config.automationSpeed)
        root.recycle()
        root = waitForRoot()
        
        val progress = NodeFinder.findProgressInfo(root)
        if (progress != null) {
            val (answeredCount, totalCount) = progress
            AppLogger.d(TAG, "Progress check: $answeredCount/$totalCount questions answered")
            
            val submitCheck = NodeFinder.findAnySubmit(root)
            val submitDisabled = submitCheck != null && !submitCheck.isEnabled
            submitCheck?.recycle()
            
            if ((answeredCount < totalCount || submitDisabled) && totalRetryPasses < maxRetryPasses) {
                totalRetryPasses++
                AppLogger.w(TAG, "Not all questions answered ($answeredCount/$totalCount) or submit disabled. Starting retry pass $totalRetryPasses")
                
                // Dump UI tree on retry for debugging
                AppLogger.d(TAG, "--- RETRY $totalRetryPasses UI TREE DUMP ---")
                AppLogger.dumpFullUiTree(TAG, root, screenWidth, screenHeight, density)
                
                // Scroll back to top
                val scrollContainer = NodeFinder.findScrollableContainer(root)
                NodeFinder.scrollToTop(scrollContainer, this@AutoFillAccessibilityService, screenHeight, screenWidth)
                scrollContainer?.recycle()
                delayScaled(600L, config.automationSpeed)
                
                // Clear text-based tracking but keep number-based tracking
                answeredByText.clear()
                scrollAttempts = 0
                consecutiveEmptyScrolls = 0
                
                // Re-enter the main loop approach for retry
                var retryScrollAttempts = 0
                var retryConsecutiveEmpty = 0
                
                while (isActive && !shouldStop.get()) {
                    root.recycle()
                    root = waitForRoot()
                    val discovered = NodeFinder.discoverQuestionCards(root)
                    
                    val unhandled = discovered.filter { q ->
                        val qNum = q.questionNumber
                        if (qNum != null) {
                            qNum !in answeredByNumber
                        } else {
                            val strippedText = NodeFinder.stripQuestionNumber(q.questionText)
                            strippedText !in answeredByText && q.questionText !in answeredByText
                        }
                    }
                    
                    AppLogger.d(TAG, "[RETRY $totalRetryPasses] Discovered ${discovered.size} questions, ${unhandled.size} unhandled")
                    
                    // Clean up already-answered
                    discovered.filter { it !in unhandled }.forEach {
                        it.interactiveNodes.forEach { n -> n.recycle() }
                        it.cardRoot.recycle()
                    }
                    
                    val container = NodeFinder.findScrollableContainer(root)
                    val containerRect = android.graphics.Rect().apply { container?.getBoundsInScreen(this) }
                    
                    val safeUnhandled = unhandled.filter { q ->
                        q.interactiveNodes.all { node ->
                            NodeFinder.isNodeVisible(node, if (containerRect.isEmpty) null else containerRect, screenHeight, screenWidth, density)
                        }
                    }
                    
                    val partiallyVisible = if (safeUnhandled.isEmpty()) {
                        unhandled.filter { q ->
                            q.interactiveNodes.all { node ->
                                NodeFinder.isNodePartiallyVisible(node, if (containerRect.isEmpty) null else containerRect, screenHeight, screenWidth, density)
                            }
                        }
                    } else emptyList()
                    
                    if (safeUnhandled.isEmpty() && partiallyVisible.isEmpty()) {
                        if (unhandled.isEmpty()) retryConsecutiveEmpty++
                        
                        var scrolled = NodeFinder.performScrollForward(container)
                        if (!scrolled) {
                            scrolled = NodeFinder.performGestureScroll(this@AutoFillAccessibilityService, screenHeight, screenWidth, forward = true)
                        }
                        container?.recycle()
                        
                        if (!scrolled || retryScrollAttempts > 20 || retryConsecutiveEmpty > 5) {
                            if (unhandled.isNotEmpty()) {
                                // Process remaining partially visible
                                AppLogger.d(TAG, "[RETRY] Force-processing ${unhandled.size} remaining unhandled questions")
                            } else {
                                AppLogger.d(TAG, "[RETRY $totalRetryPasses] Reached end of list.")
                                break
                            }
                        } else {
                            retryScrollAttempts++
                            delayScaled(400..600, config.automationSpeed)
                            continue
                        }
                    } else {
                        container?.recycle()
                        retryConsecutiveEmpty = 0
                    }
                    retryScrollAttempts = 0
                    
                    val toProcess = safeUnhandled.ifEmpty { partiallyVisible.ifEmpty { unhandled } }
                    for (q in toProcess) {
                        if (!isActive || shouldStop.get()) {
                            unhandled.forEach { uq ->
                                uq.interactiveNodes.forEach { it.recycle() }
                                uq.cardRoot.recycle()
                            }
                            return@withContext
                        }
                        
                        q.cardRoot.recycle()
                        val strippedText = NodeFinder.stripQuestionNumber(q.questionText)
                        var preset = com.example.autoreview.service.QuestionMatcher.bestMatch(strippedText, config.questions)
                            ?: com.example.autoreview.service.QuestionMatcher.bestMatch(q.questionText, config.questions)
                        
                        if (preset == null) {
                            preset = com.example.autoreview.data.QuestionPreset(
                                questionTextKey = q.questionText,
                                starValue = config.defaultStarRating,
                                yesNo = (config.defaultBinaryChoice == "Yes")
                            )
                        }
                        
                        AppLogger.d(TAG, "[RETRY $totalRetryPasses] Processing missed question #${q.questionNumber}: $strippedText")
                        
                        processQuestion(q, preset, config)
                        
                        q.interactiveNodes.forEach { it.recycle() }
                        if (q.questionNumber != null) {
                            answeredByNumber.add(q.questionNumber)
                        }
                        answeredByText.add(q.questionText)
                        answeredByText.add(strippedText)
                        totalQuestionsProcessed++
                    }
                    
                    // Cleanup unprocessed
                    unhandled.filter { it !in toProcess }.forEach { uq ->
                        uq.interactiveNodes.forEach { it.recycle() }
                        uq.cardRoot.recycle()
                    }
                }
                
                // After retry, re-check progress
                delayScaled(500L, config.automationSpeed)
                root.recycle()
                root = waitForRoot()
                val retryProgress = NodeFinder.findProgressInfo(root)
                if (retryProgress != null) {
                    AppLogger.d(TAG, "Post-retry progress: ${retryProgress.first}/${retryProgress.second}")
                }
            }
        } else {
            AppLogger.d(TAG, "Could not find progress counter, skipping retry validation")
        }
        
        if (shouldStop.get()) return@withContext

        // === SUBMIT PHASE ===
        AppLogger.i(TAG, "=== ENTERING SUBMIT PHASE ===")
        
        // First, scroll to bottom to make sure submit button is visible
        root.recycle()
        root = waitForRoot()
        val preScrollContainer = NodeFinder.findScrollableContainer(root)
        NodeFinder.performScrollToBottom(preScrollContainer, this@AutoFillAccessibilityService, screenHeight, screenWidth)
        preScrollContainer?.recycle()
        delayScaled(500L, config.automationSpeed)
        
        // Poll for submit and ensure it's visible by scrolling if necessary
        var submitBtn: AccessibilityNodeInfo? = null
        
        for (i in 1..30) {
            root.recycle()
            root = waitForRoot()
            submitBtn = NodeFinder.findEnabledSubmit(root)
            
            if (submitBtn != null) {
                val container = NodeFinder.findScrollableContainer(root)
                val containerRect = android.graphics.Rect().apply { container?.getBoundsInScreen(this) }
                container?.recycle()
                
                // Accept both fully and partially visible submit buttons
                if (NodeFinder.isNodeVisible(submitBtn, if (containerRect.isEmpty) null else containerRect, screenHeight, screenWidth, density) ||
                    NodeFinder.isNodePartiallyVisible(submitBtn, if (containerRect.isEmpty) null else containerRect, screenHeight, screenWidth, density)) {
                    AppLogger.d(TAG, "Submit button found and visible at attempt $i")
                    break
                } else {
                    AppLogger.d(TAG, "Submit button found but not visible, scrolling... (attempt $i)")
                    
                    val scrollContainer = NodeFinder.findScrollableContainer(root)
                    var scrolled = NodeFinder.performScrollForward(scrollContainer)
                    if (!scrolled) {
                        scrolled = NodeFinder.performGestureScroll(this@AutoFillAccessibilityService, screenHeight, screenWidth, forward = true)
                    }
                    scrollContainer?.recycle()
                    
                    if (!scrolled) {
                        AppLogger.d(TAG, "Cannot scroll further, using submit button anyway.")
                        break
                    }
                    
                    submitBtn.recycle()
                    submitBtn = null
                }
            } else {
                // No submit found, try scrolling down
                val scrollContainer = NodeFinder.findScrollableContainer(root)
                var scrolled = NodeFinder.performScrollForward(scrollContainer)
                if (!scrolled) {
                    scrolled = NodeFinder.performGestureScroll(this@AutoFillAccessibilityService, screenHeight, screenWidth, forward = true)
                }
                scrollContainer?.recycle()
                
                if (!scrolled && i > 5) {
                    AppLogger.w(TAG, "Cannot scroll further and no submit button found at attempt $i")
                    break
                }
            }
            
            delayScaled(500L, config.automationSpeed)
        }
        
        if (submitBtn != null) {
            AppLogger.d(TAG, "Found Submit button, clicking...")
            delayScaled(300..500, config.automationSpeed)
            val success = NodeFinder.performClickOnNodeOrParent(submitBtn, this@AutoFillAccessibilityService, TAG)
            AppLogger.d(TAG, "Submit click success: $success")
            
            if (!success) {
                // Fallback: gesture click on submit button
                AppLogger.w(TAG, "Submit standard click failed, trying gesture...")
                val rect = android.graphics.Rect()
                submitBtn.getBoundsInScreen(rect)
                if (!rect.isEmpty) {
                    val path = android.graphics.Path().apply {
                        moveTo(rect.centerX().toFloat(), rect.centerY().toFloat())
                    }
                    val gesture = android.accessibilityservice.GestureDescription.Builder()
                        .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 100))
                        .build()
                    val gestureResult = dispatchGesture(gesture, null, null)
                    AppLogger.d(TAG, "Submit gesture click result: $gestureResult")
                }
            }
            
            submitBtn.recycle()
            updateState(OverlayService.AutomationState.DONE)
            AppLogger.i(TAG, "Automation finished successfully! Total questions processed: $totalQuestionsProcessed")
            logHistory(true, "Completed review ($totalQuestionsProcessed questions)")
        } else {
            val checkRoot = waitForRoot()
            val anySubmit = NodeFinder.findAnySubmit(checkRoot)
            
            if (anySubmit != null && !anySubmit.isEnabled) {
                AppLogger.w(TAG, "Submit button disabled in submit phase. Last ditch effort to find missed questions...")
                
                // Dump UI tree for debugging
                AppLogger.dumpFullUiTree(TAG, checkRoot, screenWidth, screenHeight, density)
                
                val scrollContainer = NodeFinder.findScrollableContainer(checkRoot)
                NodeFinder.scrollToTop(scrollContainer, this@AutoFillAccessibilityService, screenHeight, screenWidth)
                scrollContainer?.recycle()
                delayScaled(600L, config.automationSpeed)
                
                var lastDitchRoot = waitForRoot()
                var lastDitchScrollAttempts = 0
                var didAggressiveScroll = false
                var lastDitchConsecutiveEmpty = 0
                
                while (isActive && !shouldStop.get()) {
                    val discovered = NodeFinder.discoverQuestionCards(lastDitchRoot)
                    val unhandled = discovered.filter { q ->
                        val qNum = q.questionNumber
                        if (qNum != null) {
                            qNum !in answeredByNumber
                        } else {
                            val strippedText = NodeFinder.stripQuestionNumber(q.questionText)
                            strippedText !in answeredByText && q.questionText !in answeredByText
                        }
                    }
                    
                    AppLogger.d(TAG, "[LAST_DITCH] Discovered ${discovered.size}, unhandled ${unhandled.size}")
                    
                    discovered.filter { it !in unhandled }.forEach {
                        it.interactiveNodes.forEach { n -> n.recycle() }
                        it.cardRoot.recycle()
                    }
                    
                    val container = NodeFinder.findScrollableContainer(lastDitchRoot)
                    val containerRect = android.graphics.Rect().apply { container?.getBoundsInScreen(this) }
                    
                    val safeUnhandled = unhandled.filter { q ->
                        q.interactiveNodes.all { node ->
                            NodeFinder.isNodeVisible(node, if (containerRect.isEmpty) null else containerRect, screenHeight, screenWidth, density)
                        }
                    }
                    
                    val partiallyVisible = if (safeUnhandled.isEmpty()) {
                        unhandled.filter { q ->
                            q.interactiveNodes.all { node ->
                                NodeFinder.isNodePartiallyVisible(node, if (containerRect.isEmpty) null else containerRect, screenHeight, screenWidth, density)
                            }
                        }
                    } else emptyList()
                    
                    if (safeUnhandled.isEmpty() && partiallyVisible.isEmpty()) {
                        if (unhandled.isEmpty()) lastDitchConsecutiveEmpty++
                        
                        var scrolled = NodeFinder.performScrollForward(container)
                        if (!scrolled) {
                            scrolled = NodeFinder.performGestureScroll(this@AutoFillAccessibilityService, screenHeight, screenWidth, forward = true)
                        }
                        
                        if (!scrolled || lastDitchScrollAttempts > 20 || lastDitchConsecutiveEmpty > 5) {
                            if (!didAggressiveScroll) {
                                AppLogger.d(TAG, "Last ditch: reached end, doing aggressive scroll to bottom")
                                NodeFinder.performScrollToBottom(container, this@AutoFillAccessibilityService, screenHeight, screenWidth)
                                didAggressiveScroll = true
                                lastDitchScrollAttempts = 0
                                lastDitchConsecutiveEmpty = 0
                                delayScaled(500L, config.automationSpeed)
                            } else {
                                container?.recycle()
                                break
                            }
                        }
                        container?.recycle()
                        if (!didAggressiveScroll) lastDitchScrollAttempts++
                        delayScaled(400..600, config.automationSpeed)
                        lastDitchRoot.recycle()
                        lastDitchRoot = waitForRoot()
                        continue
                    } else {
                        container?.recycle()
                        lastDitchConsecutiveEmpty = 0
                    }
                    
                    lastDitchScrollAttempts = 0
                    
                    val toProcess = safeUnhandled.ifEmpty { partiallyVisible.ifEmpty { unhandled } }
                    for (q in toProcess) {
                        if (!isActive || shouldStop.get()) return@withContext
                        q.cardRoot.recycle()
                        val strippedText = NodeFinder.stripQuestionNumber(q.questionText)
                        var preset = com.example.autoreview.service.QuestionMatcher.bestMatch(strippedText, config.questions) 
                            ?: com.example.autoreview.service.QuestionMatcher.bestMatch(q.questionText, config.questions)
                        if (preset == null) {
                            preset = com.example.autoreview.data.QuestionPreset(
                                questionTextKey = q.questionText,
                                starValue = config.defaultStarRating,
                                yesNo = (config.defaultBinaryChoice == "Yes")
                            )
                        }
                        
                        AppLogger.d(TAG, "[LAST_DITCH] Processing question #${q.questionNumber}: ${strippedText.take(40)}")
                        processQuestion(q, preset, config)
                        
                        q.interactiveNodes.forEach { it.recycle() }
                        if (q.questionNumber != null) answeredByNumber.add(q.questionNumber)
                        answeredByText.add(q.questionText)
                        answeredByText.add(strippedText)
                        totalQuestionsProcessed++
                    }
                    
                    // Cleanup unprocessed
                    unhandled.filter { it !in toProcess }.forEach { uq ->
                        uq.interactiveNodes.forEach { it.recycle() }
                        uq.cardRoot.recycle()
                    }
                    
                    lastDitchRoot.recycle()
                    lastDitchRoot = waitForRoot()
                }
                
                // Try submit one last time
                delayScaled(500L, config.automationSpeed)
                val finalRoot = waitForRoot()
                
                // Scroll to bottom first
                val finalScrollContainer = NodeFinder.findScrollableContainer(finalRoot)
                NodeFinder.performScrollToBottom(finalScrollContainer, this@AutoFillAccessibilityService, screenHeight, screenWidth)
                finalScrollContainer?.recycle()
                delayScaled(500L, config.automationSpeed)
                
                val postScrollRoot = waitForRoot()
                val finalSubmit = NodeFinder.findEnabledSubmit(postScrollRoot)
                if (finalSubmit != null) {
                    AppLogger.d(TAG, "Found Submit button after last ditch effort, clicking...")
                    delayScaled(300..500, config.automationSpeed)
                    NodeFinder.performClickOnNodeOrParent(finalSubmit, this@AutoFillAccessibilityService, TAG)
                    finalSubmit.recycle()
                    updateState(OverlayService.AutomationState.DONE)
                    AppLogger.i(TAG, "Automation finished successfully after last ditch! Total: $totalQuestionsProcessed")
                    logHistory(true, "Completed review ($totalQuestionsProcessed questions)")
                } else {
                    AppLogger.e(TAG, "Submit button still not enabled after last ditch effort")
                    // Dump tree one more time for analysis
                    AppLogger.dumpFullUiTree(TAG, postScrollRoot, screenWidth, screenHeight, density)
                    updateState(OverlayService.AutomationState.ERROR)
                    logHistory(false, "Submit disabled ($totalQuestionsProcessed/${ answeredByNumber.size} answered)")
                }
                anySubmit.recycle()
            } else {
                AppLogger.e(TAG, "Submit button not found or not enabled")
                // Dump tree for debugging
                AppLogger.dumpFullUiTree(TAG, checkRoot, screenWidth, screenHeight, density)
                updateState(OverlayService.AutomationState.ERROR)
                logHistory(false, "Submit not found ($totalQuestionsProcessed answered)")
                anySubmit?.recycle()
            }
        }
    }

    /**
     * Processes a single question by clicking the appropriate interactive element.
     * Returns true if click was successful.
     */
    private suspend fun processQuestion(
        q: com.example.autoreview.service.DiscoveredQuestion,
        preset: com.example.autoreview.data.QuestionPreset,
        config: com.example.autoreview.data.PresetConfig
    ): Boolean {
        val strippedText = NodeFinder.stripQuestionNumber(q.questionText)
        
        if (q.type == QuestionType.STAR_RATING) {
            val stars = q.interactiveNodes
            val starVal = preset.starValue ?: config.defaultStarRating
            if (stars.size == 5 && starVal in 1..5) {
                AppLogger.d(TAG, "Clicking star $starVal for question #${q.questionNumber}: ${strippedText.take(50)}")
                val success = NodeFinder.performClickOnNodeOrParent(stars[starVal - 1], this@AutoFillAccessibilityService, TAG)
                AppLogger.d(TAG, "Star click success: $success")
                // Increased delay for click to register on all devices
                delayScaled(300..500, config.automationSpeed)
                
                if (!success) {
                    // If standard click failed, try gesture click directly on the star bounds
                    AppLogger.w(TAG, "Retrying star $starVal click with gesture for question #${q.questionNumber}")
                    val rect = android.graphics.Rect()
                    stars[starVal - 1].getBoundsInScreen(rect)
                    if (!rect.isEmpty) {
                        val path = android.graphics.Path().apply {
                            moveTo(rect.centerX().toFloat(), rect.centerY().toFloat())
                        }
                        val gesture = android.accessibilityservice.GestureDescription.Builder()
                            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 150))
                            .build()
                        val gestureResult = dispatchGesture(gesture, null, null)
                        AppLogger.d(TAG, "Gesture retry result: $gestureResult")
                        delayScaled(300..500, config.automationSpeed)
                        return gestureResult
                    }
                    return false
                }
                return success
            } else {
                AppLogger.w(TAG, "Star question #${q.questionNumber} has ${stars.size} stars (expected 5), starVal=$starVal")
            }
        } else if (q.type == QuestionType.YES_NO) {
            val yesNo = q.interactiveNodes
            if (yesNo.size == 2) {
                val choice = preset.yesNo ?: (config.defaultBinaryChoice == "Yes")
                val target = if (choice) yesNo[0] else yesNo[1]
                AppLogger.d(TAG, "Clicking ${if (choice) "Yes" else "No"} for question #${q.questionNumber}: ${strippedText.take(50)}")
                val success = NodeFinder.performClickOnNodeOrParent(target, this@AutoFillAccessibilityService, TAG)
                AppLogger.d(TAG, "Yes/No click success: $success")
                // Increased delay for click to register
                delayScaled(300..500, config.automationSpeed)
                
                if (!success) {
                    // Retry with gesture click
                    AppLogger.w(TAG, "Retrying Yes/No click with gesture for question #${q.questionNumber}")
                    val rect = android.graphics.Rect()
                    target.getBoundsInScreen(rect)
                    if (!rect.isEmpty) {
                        val path = android.graphics.Path().apply {
                            moveTo(rect.centerX().toFloat(), rect.centerY().toFloat())
                        }
                        val gesture = android.accessibilityservice.GestureDescription.Builder()
                            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 150))
                            .build()
                        val gestureResult = dispatchGesture(gesture, null, null)
                        AppLogger.d(TAG, "Gesture retry result: $gestureResult")
                        delayScaled(300..500, config.automationSpeed)
                        return gestureResult
                    }
                    return false
                }
                return success
            } else {
                AppLogger.w(TAG, "Yes/No question #${q.questionNumber} has ${yesNo.size} interactive nodes instead of 2, skipping")
            }
        }
        return false
    }

    private suspend fun waitForRoot(): AccessibilityNodeInfo {
        var root = rootInActiveWindow
        var attempts = 0
        while (root == null && attempts < 15) {
            delay(300.milliseconds)
            root = rootInActiveWindow
            attempts++
        }
        if (root == null) {
            AppLogger.e(TAG, "Window root is null after $attempts attempts")
        }
        return root ?: throw IllegalStateException("Window root is null after $attempts attempts")
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

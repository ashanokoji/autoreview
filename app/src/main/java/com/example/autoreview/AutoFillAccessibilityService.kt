@file:Suppress("DEPRECATION")

package com.example.autoreview

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.example.autoreview.data.PresetConfig
import com.example.autoreview.data.PresetRepository
import com.example.autoreview.service.DiscoveredQuestion
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

        // Tracking: which questions were answered (text, number-stripped) and their assigned
        // numbers, so we can detect any number that was skipped on a given screen size and
        // recover it afterwards.
        val answered = mutableSetOf<String>()
        val answeredNums = mutableSetOf<Int>()
        var maxNumSeen = 0

        // === PASS 1: Robust gesture scan (50%) ===
        val pass1 = forwardScan(
            answered = answered,
            answeredNums = answeredNums,
            maxNumSeen = { n -> if (n > maxNumSeen) maxNumSeen = n },
            useNativeScroll = false,
            config = config,
            screenHeight = screenHeight,
            screenWidth = screenWidth
        )

        // === PASS 2: recovery — backward search ===
        val missing = if (maxNumSeen > 0) (1..maxNumSeen).filter { it !in answeredNums } else emptyList()
        val needsRecovery = !shouldStop.get() && (missing.isNotEmpty() || (!pass1.submitVisible && answered.isNotEmpty()))
        if (needsRecovery) {
            AppLogger.i(TAG, "Recovery pass: ${missing.size} question(s) missing (${missing.joinToString()}), submitVisible=${pass1.submitVisible}; searching backward.")
            reverseSearch(
                missing = missing,
                submitVisible = pass1.submitVisible,
                answered = answered,
                answeredNums = answeredNums,
                maxNumSeen = { n -> if (n > maxNumSeen) maxNumSeen = n },
                config = config,
                screenHeight = screenHeight,
                screenWidth = screenWidth
            )
        }

        if (shouldStop.get()) return@withContext

        // === SUBMIT (scroll down to locate the button) ===
        repeat(15) {
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
            val sc = NodeFinder.findScrollableContainer(root)
            val containerRect = android.graphics.Rect().apply { sc?.getBoundsInScreen(this) }
            NodeFinder.performGestureScroll(
                this@AutoFillAccessibilityService, screenHeight, screenWidth,
                forward = true, fraction = 0.5f,
                containerRect = if (containerRect.isEmpty) null else containerRect
            )
            sc?.recycle()
            delayScaled(400L, config.automationSpeed)
        }

        AppLogger.e(TAG, "Submit button not found")
        updateState(OverlayService.AutomationState.ERROR)
        logHistory(false, "Submit not found (${answered.size} answered)")
    }

    private enum class AnswerOutcome { OK, NEEDS_USER, STOP }

    private data class ScanResult(
        val submitVisible: Boolean,
        val endNoGaps: Boolean,
        val needsUser: Boolean,
        val stopped: Boolean
    )

    private fun extractQuestionNumber(text: String): Int? {
        val m = Regex("^\\s*(\\d+)").find(text)
        return m?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * Searches backward (upwards) to find any skipped questions.
     * Stops when the missing list is empty, or after a few scrolls if checking the bottom edge.
     */
    private suspend fun reverseSearch(
        missing: List<Int>,
        submitVisible: Boolean,
        answered: MutableSet<String>,
        answeredNums: MutableSet<Int>,
        maxNumSeen: (Int) -> Unit,
        config: PresetConfig,
        screenHeight: Int,
        screenWidth: Int
    ) {
        var currentMissing = missing.toMutableList()
        var stagnantCount = 0
        var lastSig = ""
        var iter = 0
        var unhandledFound = false

        // If we only miss the trailing questions (missing is empty but submit was not found), 
        // we'll scroll up a few times to see if they were hidden in the fold.
        val searchLimit = if (currentMissing.isEmpty()) 4 else 50

        while (!shouldStop.get()) {
            if (++iter > searchLimit) {
                AppLogger.d(TAG, "Reverse search reached iter limit ($searchLimit).")
                break
            }
            
            val root = waitForRoot()
            val discovered = NodeFinder.discoverQuestionCards(root)
            val sig = discovered.map { stripNumberPrefix(it.questionText) }.sorted().joinToString("|")
            
            if (sig != lastSig) { lastSig = sig; stagnantCount = 0 } else stagnantCount++
            
            val unhandled = discovered.filter { stripNumberPrefix(it.questionText) !in answered }
            
            if (unhandled.isNotEmpty()) {
                unhandledFound = true
                val container = NodeFinder.findScrollableContainer(root)
                val containerRect = android.graphics.Rect().apply { container?.getBoundsInScreen(this) }
                val safeUnhandled = unhandled.filter { q ->
                    q.interactiveNodes.all { node ->
                        NodeFinder.isNodeVisible(node, if (containerRect.isEmpty) null else containerRect, screenHeight, screenWidth)
                    }
                }
                val toProcess = safeUnhandled.ifEmpty { unhandled }
                val r = processQuestions(toProcess, answered, answeredNums, maxNumSeen, config)
                container?.recycle()
                
                if (r != AnswerOutcome.OK) {
                    root.recycle()
                    return
                }
                
                currentMissing = currentMissing.filter { it !in answeredNums }.toMutableList()
            }

            if (currentMissing.isEmpty() && (submitVisible || unhandledFound)) {
                // We found what we needed!
                root.recycle()
                AppLogger.i(TAG, "Reverse search completed successfully.")
                return
            }

            if (stagnantCount >= 3) {
                root.recycle()
                AppLogger.w(TAG, "Reverse search stagnated.")
                return
            }

            val container = NodeFinder.findScrollableContainer(root)
            val containerRect = android.graphics.Rect().apply { container?.getBoundsInScreen(this) }
            NodeFinder.performGestureScroll(
                this@AutoFillAccessibilityService, screenHeight, screenWidth,
                forward = false, fraction = 0.5f,
                containerRect = if (containerRect.isEmpty) null else containerRect
            )
            container?.recycle()
            root.recycle()
            delayScaled(400..500, config.automationSpeed)
        }
    }

    /**
     * Core discovery → click → scroll loop.
     *
     * When [overlap] is false, it scrolls a full page at a time (fast, normal behaviour used by
     * PASS 1). When [overlap] is true, it scrolls by half the container height so every card
     * becomes fully visible in at least one snapshot — this is what makes PASS 2 reliably catch
     * questions that were jumped over on a smaller screen.
     *
     * End-of-list is detected by stagnation (the visible set stops changing), which works for any
     * screen size and any scroll distance.
     */
    private suspend fun forwardScan(
        answered: MutableSet<String>,
        answeredNums: MutableSet<Int>,
        maxNumSeen: (Int) -> Unit,
        useNativeScroll: Boolean,
        config: PresetConfig,
        screenHeight: Int,
        screenWidth: Int
    ): ScanResult {
        var scrollAttempts = 0
        var stagnant = 0
        var lastSig = ""
        var iter = 0

        while (!shouldStop.get()) {
            if (++iter > 250) break
            val root = waitForRoot()

            val submit = NodeFinder.findEnabledSubmit(root)
            val submitVisible = submit != null
            submit?.recycle()
            if (submitVisible && answered.isNotEmpty()) {
                // Submit is enabled only once the app considers everything answered.
                root.recycle()
                return ScanResult(submitVisible = true, endNoGaps = true, needsUser = false, stopped = false)
            }

            val discovered = NodeFinder.discoverQuestionCards(root)
            val unhandled = discovered.filter { stripNumberPrefix(it.questionText) !in answered }

            val sig = discovered.map { stripNumberPrefix(it.questionText) }.sorted().joinToString("|")
            if (sig != lastSig) { lastSig = sig; stagnant = 0 } else stagnant++

            AppLogger.d(TAG, "Discovered ${discovered.size} questions, ${unhandled.size} unhandled, scroll=$scrollAttempts stagnant=$stagnant")

            val container = NodeFinder.findScrollableContainer(root)
            val containerRect = android.graphics.Rect().apply { container?.getBoundsInScreen(this) }

            val safeUnhandled = unhandled.filter { q ->
                q.interactiveNodes.all { node ->
                    NodeFinder.isNodeVisible(node, if (containerRect.isEmpty) null else containerRect, screenHeight, screenWidth)
                }
            }

            // Release nodes of already-answered cards.
            discovered.filter { stripNumberPrefix(it.questionText) in answered }.forEach {
                it.interactiveNodes.forEach { n -> n.recycle() }
                it.cardRoot.recycle()
            }

            if (safeUnhandled.isEmpty()) {
                if (unhandled.isEmpty()) {
                    if (submitVisible) {
                        container?.recycle(); root.recycle()
                        return ScanResult(submitVisible = true, endNoGaps = true, needsUser = false, stopped = false)
                    }
                    if (stagnant >= 4) {
                        container?.recycle(); root.recycle()
                        return ScanResult(submitVisible = false, endNoGaps = true, needsUser = false, stopped = false)
                    }
                } else if (stagnant >= 6) {
                    // Cards exist but never become fully on-screen; answer them anyway via fallback tap.
                    AppLogger.d(TAG, "Cannot bring remaining questions fully on-screen; answering via fallback.")
                    container?.recycle()
                    val r = processQuestions(unhandled, answered, answeredNums, maxNumSeen, config)
                    root.recycle()
                    if (r != AnswerOutcome.OK) {
                        return ScanResult(submitVisible = false, endNoGaps = false, needsUser = r == AnswerOutcome.NEEDS_USER, stopped = r == AnswerOutcome.STOP)
                    }
                    continue
                }

                var gestureUsed = false
                val containerRect = android.graphics.Rect().apply { container?.getBoundsInScreen(this) }
                if (useNativeScroll) {
                    val sc = NodeFinder.performScrollForward(container)
                    if (!sc) {
                        NodeFinder.performGestureScroll(
                            this@AutoFillAccessibilityService, screenHeight, screenWidth,
                            forward = true, fraction = 0.5f,
                            containerRect = if (containerRect.isEmpty) null else containerRect
                        )
                        gestureUsed = true
                    }
                } else {
                    NodeFinder.performGestureScroll(
                        this@AutoFillAccessibilityService, screenHeight, screenWidth,
                        forward = true, fraction = 0.5f,
                        containerRect = if (containerRect.isEmpty) null else containerRect
                    )
                    gestureUsed = true
                }
                container?.recycle()
                scrollAttempts++
                root.recycle()
                delayScaled(if (gestureUsed) 400..500 else 100..250, config.automationSpeed)
                continue
            } else {
                container?.recycle()
            }
            scrollAttempts = 0

            val toProcess = safeUnhandled.ifEmpty { unhandled }
            val r = processQuestions(toProcess, answered, answeredNums, maxNumSeen, config)
            root.recycle()
            if (r != AnswerOutcome.OK) {
                return ScanResult(submitVisible = false, endNoGaps = false, needsUser = r == AnswerOutcome.NEEDS_USER, stopped = r == AnswerOutcome.STOP)
            }
        }

        return ScanResult(submitVisible = false, endNoGaps = true, needsUser = false, stopped = true)
    }

    private suspend fun processQuestions(
        toProcess: List<DiscoveredQuestion>,
        answered: MutableSet<String>,
        answeredNums: MutableSet<Int>,
        maxNumSeen: (Int) -> Unit,
        config: PresetConfig
    ): AnswerOutcome {
        for (i in toProcess.indices) {
            val q = toProcess[i]
            if (shouldStop.get()) {
                for (j in i until toProcess.size) {
                    toProcess[j].interactiveNodes.forEach { it.recycle() }
                    toProcess[j].cardRoot.recycle()
                }
                return AnswerOutcome.STOP
            }

            val num = extractQuestionNumber(q.questionText)
            if (num != null) {
                answeredNums.add(num)
                maxNumSeen(num)
            }

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
                    q.interactiveNodes.forEach { it.recycle() }
                    q.cardRoot.recycle()
                    return AnswerOutcome.NEEDS_USER
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
            q.cardRoot.recycle()
            answered.add(stripNumberPrefix(q.questionText))
        }
        return AnswerOutcome.OK
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

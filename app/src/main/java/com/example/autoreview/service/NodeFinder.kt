@file:Suppress("DEPRECATION")

package com.example.autoreview.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.autoreview.util.AppLogger

enum class QuestionType { STAR_RATING, YES_NO }

data class DiscoveredQuestion(
    val questionText: String,
    val cardRoot: AccessibilityNodeInfo,
    val type: QuestionType,
    val interactiveNodes: List<AccessibilityNodeInfo> = emptyList(),
    val questionNumber: Int? = null
)

object NodeFinder {

    // Pattern to detect the rating scale legend text that should NOT be treated as a question
    private val RATING_SCALE_PATTERN = Regex(
        "(1\\s*=|never|once in a while|sometimes|most of the times|almost always)",
        RegexOption.IGNORE_CASE
    )

    /**
     * Extracts a question number from a content description like "4\nManages class time effectively"
     * or "12\nMotivates students to learn". Returns null if no number prefix is found.
     */
    fun extractQuestionNumber(text: String): Int? {
        val trimmed = text.trim()
        // Pattern: starts with a number, followed by newline or space, then question text
        val match = Regex("^(\\d+)\\s*[\\n\\r]").find(trimmed)
        if (match != null) {
            return match.groupValues[1].toIntOrNull()
        }
        // Also try: just a number at the very start followed by whitespace and text
        val match2 = Regex("^(\\d+)\\s+\\S").find(trimmed)
        if (match2 != null) {
            return match2.groupValues[1].toIntOrNull()
        }
        return null
    }

    /**
     * Strips the question number prefix from text, e.g.
     * "4\nManages class time effectively" -> "Manages class time effectively"
     */
    fun stripQuestionNumber(text: String): String {
        return text.trim().replace(Regex("^\\d+\\s*[\\n\\r]+\\s*"), "").trim()
    }

    /**
     * Checks if the text looks like the rating scale legend rather than a real question.
     */
    private fun isRatingScaleLegend(text: String): Boolean {
        // The legend contains patterns like "1 = Never" and multiple rating descriptions
        val matchCount = RATING_SCALE_PATTERN.findAll(text).count()
        return matchCount >= 3 // Legend has at least 3 of these patterns
    }

    fun findNodeByDescription(node: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        if (node.contentDescription?.toString() == target) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                val found = findNodeByDescription(child, target)
                if (found != null) {
                    if (found !== child) child.recycle()
                    return found
                }
                child.recycle()
            }
        }
        return null
    }

    fun findNodeByText(node: AccessibilityNodeInfo, target: String, contains: Boolean = false): AccessibilityNodeInfo? {
        val text = node.text?.toString() ?: ""
        if (if (contains) text.contains(target, ignoreCase = true) else text.equals(target, ignoreCase = true)) {
            return node
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                val found = findNodeByText(child, target, contains)
                if (found != null) {
                    if (found !== child) child.recycle()
                    return found
                }
                child.recycle()
            }
        }
        return null
    }
    
    fun findEnabledSubmit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        if (node.isEnabled && (text.contains("Submit", ignoreCase = true) || desc.contains("Submit", ignoreCase = true))) {
            // Exclude the "Answer all questions to submit" disabled button
            if (desc.contains("Answer all questions", ignoreCase = true)) return null
            if (text.contains("Answer all questions", ignoreCase = true)) return null
            return node
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                val found = findEnabledSubmit(child)
                if (found != null) {
                    if (found !== child) child.recycle()
                    return found
                }
                child.recycle()
            }
        }
        return null
    }

    fun findAnySubmit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        if (text.contains("Submit", ignoreCase = true) || desc.contains("Submit", ignoreCase = true)) {
            // Exclude the "Answer all questions to submit" warning text itself, unless it's the actual button
            if (!node.isClickable && (desc.contains("Answer all questions", ignoreCase = true) || text.contains("Answer all questions", ignoreCase = true))) {
                return null
            }
            return node
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                val found = findAnySubmit(child)
                if (found != null) {
                    if (found !== child) child.recycle()
                    return found
                }
                child.recycle()
            }
        }
        return null
    }

    /**
     * Finds the progress node that shows "Progress: X/Y questions" and returns (answered, total).
     * Also tries alternative progress text patterns for different device layouts.
     */
    fun findProgressInfo(root: AccessibilityNodeInfo): Pair<Int, Int>? {
        fun search(node: AccessibilityNodeInfo): Pair<Int, Int>? {
            val desc = node.contentDescription?.toString() ?: ""
            val text = node.text?.toString() ?: ""
            val content = if (desc.contains("Progress")) desc else if (text.contains("Progress")) text else ""
            
            if (content.isNotEmpty()) {
                // Parse "Progress: X/Y questions"
                val match = Regex("Progress:\\s*(\\d+)/(\\d+)").find(content)
                if (match != null) {
                    val answered = match.groupValues[1].toIntOrNull() ?: 0
                    val total = match.groupValues[2].toIntOrNull() ?: 0
                    return Pair(answered, total)
                }
            }
            
            // Also try generic "X/Y" or "X of Y" patterns in text or desc
            for (s in listOf(desc, text)) {
                if (s.isNotEmpty()) {
                    val altMatch = Regex("(\\d+)\\s*/\\s*(\\d+)\\s*question", RegexOption.IGNORE_CASE).find(s)
                    if (altMatch != null) {
                        val answered = altMatch.groupValues[1].toIntOrNull() ?: 0
                        val total = altMatch.groupValues[2].toIntOrNull() ?: 0
                        if (total > 0) return Pair(answered, total)
                    }
                    val altMatch2 = Regex("(\\d+)\\s+of\\s+(\\d+)", RegexOption.IGNORE_CASE).find(s)
                    if (altMatch2 != null) {
                        val answered = altMatch2.groupValues[1].toIntOrNull() ?: 0
                        val total = altMatch2.groupValues[2].toIntOrNull() ?: 0
                        if (total > 0) return Pair(answered, total)
                    }
                }
            }
            
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    val result = search(child)
                    child.recycle()
                    if (result != null) return result
                }
            }
            return null
        }
        return search(root)
    }

    fun discoverQuestionCards(root: AccessibilityNodeInfo): List<DiscoveredQuestion> {
        val results = mutableListOf<DiscoveredQuestion>()
        val seenRoots = mutableSetOf<AccessibilityNodeInfo>()
        
        fun walk(node: AccessibilityNodeInfo) {
            if (node in seenRoots) return
            
            val starNodes = findStarLikeChildren(node)
            val yesNo = findYesNoChildren(node)
            val questionText = findLongestTextChild(node)

            if (questionText != null && starNodes.size == 5) {
                // Filter out the rating scale legend
                if (isRatingScaleLegend(questionText)) {
                    AppLogger.d("NodeFinder", "Skipping rating scale legend: ${questionText.take(60)}...")
                    starNodes.forEach { it.recycle() }
                    yesNo?.first?.recycle()
                    yesNo?.second?.recycle()
                    // Still walk children - the actual questions are below this
                    for (i in 0 until node.childCount) {
                        node.getChild(i)?.let { walk(it) }
                    }
                    return
                }
                
                val qNum = extractQuestionNumber(questionText)
                AppLogger.d("NodeFinder", "Discovered STAR question #$qNum: $questionText")
                results.add(DiscoveredQuestion(questionText, node, QuestionType.STAR_RATING, starNodes, qNum))
                seenRoots.add(node)
                yesNo?.first?.recycle()
                yesNo?.second?.recycle()
                return 
            } else if (questionText != null && yesNo != null) {
                // Filter out rating scale legend for Yes/No too
                if (isRatingScaleLegend(questionText)) {
                    AppLogger.d("NodeFinder", "Skipping rating scale legend (yes/no): ${questionText.take(60)}...")
                    starNodes.forEach { it.recycle() }
                    yesNo.first.recycle()
                    yesNo.second.recycle()
                    for (i in 0 until node.childCount) {
                        node.getChild(i)?.let { walk(it) }
                    }
                    return
                }
                
                val qNum = extractQuestionNumber(questionText)
                AppLogger.d("NodeFinder", "Discovered YES/NO question #$qNum: $questionText")
                results.add(DiscoveredQuestion(questionText, node, QuestionType.YES_NO, listOf(yesNo.first, yesNo.second), qNum))
                seenRoots.add(node)
                starNodes.forEach { it.recycle() }
                return
            }
            
            // Clean up unused allocated collections
            starNodes.forEach { it.recycle() }
            yesNo?.first?.recycle()
            yesNo?.second?.recycle()

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { walk(it) }
            }
        }
        walk(root)
        return results
    }

    fun findStarLikeChildren(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val stars = mutableListOf<AccessibilityNodeInfo>()
        val expectedDescriptions = listOf("1", "2", "3", "4", "5")
        
        fun collect(n: AccessibilityNodeInfo) {
            val desc = n.contentDescription?.toString()?.trim()
            if (desc in expectedDescriptions) {
                stars.add(n) // Keep reference, caller must recycle
                return
            }
            // For nested view groups
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { child -> 
                    collect(child)
                    if (child !in stars) {
                        child.recycle()
                    }
                }
            }
        }
        
        collect(node)
        
        // Filter out if not exactly 5 unique star descriptions
        val uniqueDesc = stars.map { it.contentDescription?.toString()?.trim() }.toSet()
        if (uniqueDesc.size == 5 && stars.size == 5) {
            return stars.sortedBy { it.contentDescription?.toString()?.trim() }
        }
        
        // Also accept if we got more than 5 but can pick exactly 5 unique ones
        if (uniqueDesc.size == 5 && stars.size > 5) {
            val picked = mutableListOf<AccessibilityNodeInfo>()
            val usedDescs = mutableSetOf<String>()
            for (s in stars) {
                val d = s.contentDescription?.toString()?.trim() ?: ""
                if (d !in usedDescs) {
                    picked.add(s)
                    usedDescs.add(d)
                } else {
                    s.recycle()
                }
            }
            if (picked.size == 5) {
                return picked.sortedBy { it.contentDescription?.toString()?.trim() }
            }
            picked.forEach { it.recycle() }
            return emptyList()
        }
        
        // Cleanup if not a valid group
        stars.forEach { it.recycle() }
        return emptyList()
    }

    fun findYesNoChildren(node: AccessibilityNodeInfo): Pair<AccessibilityNodeInfo, AccessibilityNodeInfo>? {
        var yesNode: AccessibilityNodeInfo? = null
        var noNode: AccessibilityNodeInfo? = null

        fun collect(n: AccessibilityNodeInfo) {
            if (yesNode != null && noNode != null) return
            
            // Check text first
            val text = n.text?.toString()?.trim()
            // Check contentDescription separately
            val desc = n.contentDescription?.toString()?.trim()
            
            val isYes = text.equals("Yes", ignoreCase = true) || 
                        desc.equals("Yes", ignoreCase = true)
            val isNo = text.equals("No", ignoreCase = true) || 
                       desc.equals("No", ignoreCase = true)
            
            if (isYes && yesNode == null) {
                yesNode = n
            } else if (isNo && noNode == null) {
                noNode = n
            } else {
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let { child -> 
                        collect(child) 
                        if (child !== yesNode && child !== noNode) {
                            child.recycle()
                        }
                    }
                }
            }
        }

        collect(node)
        if (yesNode != null && noNode != null) {
            return Pair(yesNode!!, noNode!!)
        }
        
        yesNode?.recycle()
        noNode?.recycle()
        return null
    }

    private fun findLongestTextChild(node: AccessibilityNodeInfo): String? {
        var longestText: String? = null
        
        fun processText(t: String?) {
            val text = t?.trim()
            if (text != null && text.length > 2 && (longestText == null || text.length > longestText!!.length)) {
                longestText = text
            }
        }

        fun collect(n: AccessibilityNodeInfo) {
            val t = n.text?.toString()?.trim()
            if (!t.isNullOrEmpty()) {
                processText(t)
            } else {
                processText(n.contentDescription?.toString())
            }
            
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { child -> 
                    collect(child)
                    child.recycle()
                }
            }
        }
        
        collect(node)
        return longestText
    }

    /**
     * Finds ALL scrollable containers in the tree and returns the best one
     * (deepest scrollable that is likely the question list).
     */
    fun findScrollableContainer(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var bestScrollable: AccessibilityNodeInfo? = null
        var bestDepth = -1
        
        fun search(n: AccessibilityNodeInfo, depth: Int) {
            if (n.isScrollable) {
                // Prefer deeper scrollable containers (they're more likely the actual content list)
                if (depth > bestDepth) {
                    bestScrollable?.recycle()
                    bestScrollable = n
                    bestDepth = depth
                } else {
                    // Keep searching
                }
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { child ->
                    search(child, depth + 1)
                    // Only recycle if not the best scrollable
                    if (child !== bestScrollable) {
                        child.recycle()
                    }
                }
            }
        }
        
        search(node, 0)
        
        // Fallback: if no scrollable found at depth > 0, use the first one found
        if (bestScrollable == null) {
            if (node.isScrollable) return node
        }
        
        return bestScrollable
    }

    fun performScrollForward(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val scrolled = node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        return scrolled
    }

    fun performScrollBackward(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    /**
     * Gesture-based scroll fallback for devices where ACTION_SCROLL_FORWARD doesn't work.
     * Swipes from bottom to top of the container area.
     * Uses smaller scroll distance for tablets to avoid over-scrolling.
     */
    fun performGestureScroll(service: AccessibilityService, screenHeight: Int, screenWidth: Int, forward: Boolean = true): Boolean {
        val centerX = screenWidth / 2f
        val startY: Float
        val endY: Float
        
        // Determine if this is a tablet (width > 600dp equivalent)
        val density = service.resources.displayMetrics.density
        val widthDp = screenWidth / density
        val isTablet = widthDp > 600
        
        // Use smaller scroll distance on tablets to avoid skipping questions
        val scrollStart = if (isTablet) 0.65f else 0.75f
        val scrollEnd = if (isTablet) 0.35f else 0.25f
        
        if (forward) {
            startY = screenHeight * scrollStart
            endY = screenHeight * scrollEnd
        } else {
            startY = screenHeight * scrollEnd
            endY = screenHeight * scrollStart
        }
        
        val path = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }
        // Use longer duration on tablets for smoother scrolling
        val duration = if (isTablet) 400L else 300L
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        
        return service.dispatchGesture(gesture, null, null)
    }

    /**
     * Scrolls all the way back to the top of the list.
     */
    fun scrollToTop(node: AccessibilityNodeInfo?, service: AccessibilityService, screenHeight: Int, screenWidth: Int): Boolean {
        if (node == null) return false
        var scrolled = false
        for (i in 0..20) {
            val result = node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            if (!result) {
                // Try gesture scroll backward
                performGestureScroll(service, screenHeight, screenWidth, forward = false)
                break
            }
            scrolled = true
        }
        return scrolled
    }

    /**
     * Aggressively scrolls to the bottom to expose elements that might be cut off.
     */
    fun performScrollToBottom(node: AccessibilityNodeInfo?, service: AccessibilityService, screenHeight: Int, screenWidth: Int): Boolean {
        if (node == null) return false
        var scrolled = false
        // Try multiple scroll forwards aggressively
        for (i in 0..5) {
            val result = node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            if (!result) {
                performGestureScroll(service, screenHeight, screenWidth, forward = true)
            }
            scrolled = true
        }
        return scrolled
    }

    /**
     * Attempts to perform a click on the given [node] or its parents.
     * Note: This method DOES NOT recycle the original [node]. The caller is responsible for recycling it.
     * Enhanced with multiple fallback strategies for different device types.
     */
    fun performClickOnNodeOrParent(node: AccessibilityNodeInfo?, service: AccessibilityService, logTag: String = "NodeFinder"): Boolean {
        if (node == null) {
            AppLogger.e(logTag, "Cannot click: node is null")
            return false
        }
        
        AppLogger.d(logTag, "Attempting to click node with text='${node.text}' desc='${node.contentDescription}' class='${node.className}'")

        // 1. Try standard Accessibility ACTION_CLICK on the node itself first
        if (node.isClickable) {
            val directClick = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (directClick) {
                AppLogger.d(logTag, "Direct ACTION_CLICK succeeded on node")
                return true
            }
        }

        // 2. Try ACTION_CLICK walking up to clickable parents
        var current: AccessibilityNodeInfo? = node
        var first = true
        var actionClickSuccess = false
        var depth = 0
        while (current != null && depth < 10) {
            val isClickable = current.isClickable
            AppLogger.d(logTag, "  -> Checking parent depth=$depth: class='${current.className}' isClickable=$isClickable")
            
            if (isClickable) {
                actionClickSuccess = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                AppLogger.d(logTag, "  -> ACTION_CLICK performed on depth=$depth, returned: $actionClickSuccess")
                if (actionClickSuccess) {
                    if (!first) current.recycle()
                    break
                }
            }
            val parent = current.parent
            if (!first) current.recycle()
            current = parent
            first = false
            depth++
        }
        
        if (actionClickSuccess) {
            AppLogger.d(logTag, "Click succeeded using standard ACTION_CLICK")
            return true
        }

        // 3. Try ACTION_SELECT as some devices use this
        try {
            val selectResult = node.performAction(AccessibilityNodeInfo.ACTION_SELECT)
            if (selectResult) {
                AppLogger.d(logTag, "ACTION_SELECT succeeded")
                return true
            }
        } catch (_: Exception) {}

        AppLogger.d(logTag, "Standard ACTION_CLICK failed or no clickable parent found. Falling back to gesture.")

        // 4. Fallback: Attempt physical gesture click
        val rect = Rect()
        node.getBoundsInScreen(rect)
        AppLogger.d(logTag, "  -> Target screen bounds: $rect (isEmpty=${rect.isEmpty})")
        
        if (!rect.isEmpty && rect.centerX() > 0 && rect.centerY() > 0) {
            val path = Path().apply {
                moveTo(rect.centerX().toFloat(), rect.centerY().toFloat())
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()
            
            val dispatched = service.dispatchGesture(gesture, null, null)
            AppLogger.d(logTag, "  -> dispatchGesture (tap) returned: $dispatched")
            if (dispatched) return true
            
            // 5. Last resort: try a longer press gesture (some devices need this)
            val longGesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 150))
                .build()
            val longDispatched = service.dispatchGesture(longGesture, null, null)
            AppLogger.d(logTag, "  -> dispatchGesture (long-tap) returned: $longDispatched")
            return longDispatched
        } else {
            AppLogger.e(logTag, "  -> Cannot dispatch gesture: invalid bounds")
        }

        return false
    }

    /**
     * Density-aware visibility check. Uses dp-based margins to work across all screen sizes.
     * Also checks that the ENTIRE node (not just center) is within the visible area
     * to avoid clicking partially visible elements.
     * 
     * Uses a more lenient bottom margin for the last question which may be partially 
     * clipped but still interactable.
     */
    fun isNodeVisible(node: AccessibilityNodeInfo, containerRect: Rect?, screenHeight: Int, screenWidth: Int, density: Float = 1f): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return false
        
        // Check if the node reports itself as not visible
        if (!node.isVisibleToUser) return false
        
        val top = containerRect?.top ?: 0
        val bottom = containerRect?.bottom ?: screenHeight
        val left = containerRect?.left ?: 0
        val right = containerRect?.right ?: screenWidth
        
        // Use density-aware margin (8dp - slightly reduced to catch more nodes)
        val margin = (8 * density).toInt()
        
        // Check that the FULL node bounds (top and bottom) are within the visible area
        // This ensures we don't try to click partially visible elements
        return rect.top >= top + margin && 
               rect.bottom <= bottom - margin &&
               rect.left >= left && 
               rect.right <= right
    }

    /**
     * A more lenient visibility check that only requires the center point to be visible.
     * Used as a fallback when strict visibility check fails but we still want to try clicking.
     */
    fun isNodePartiallyVisible(node: AccessibilityNodeInfo, containerRect: Rect?, screenHeight: Int, screenWidth: Int, density: Float = 1f): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return false
        
        val top = containerRect?.top ?: 0
        val bottom = containerRect?.bottom ?: screenHeight
        val left = containerRect?.left ?: 0
        val right = containerRect?.right ?: screenWidth
        
        val centerY = rect.centerY()
        val centerX = rect.centerX()
        
        return centerY >= top && centerY <= bottom && centerX >= left && centerX <= right
    }
}

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
    val interactiveNodes: List<AccessibilityNodeInfo> = emptyList()
)

object NodeFinder {

    // Pattern to detect the rating scale legend text that should NOT be treated as a question
    private val RATING_SCALE_PATTERN = Regex(
        "(1\\s*=|never|once in a while|sometimes|most of the times|almost always)",
        RegexOption.IGNORE_CASE
    )

    /**
     * Checks if the text looks like the rating scale legend rather than a real question.
     */
    private fun isRatingScaleLegend(text: String): Boolean {
        val matchCount = RATING_SCALE_PATTERN.findAll(text).count()
        return matchCount >= 3
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

    fun discoverQuestionCards(root: AccessibilityNodeInfo): List<DiscoveredQuestion> {
        val results = mutableListOf<DiscoveredQuestion>()
        val seenRoots = mutableSetOf<AccessibilityNodeInfo>()
        
        fun walk(node: AccessibilityNodeInfo) {
            if (node in seenRoots) return
            
            val childQuestionsBefore = results.size
            
            // Walk children FIRST (bottom-up discovery) to prevent parent nodes
            // from swallowing interactive elements that belong to individual question cards.
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    walk(child)
                    if (child !in seenRoots) {
                        child.recycle()
                    }
                }
            }
            
            // If any children were already discovered as questions, skip this node
            if (results.size > childQuestionsBefore) return
            
            val starNodes = findStarLikeChildren(node)
            val yesNo = findYesNoChildren(node)
            val questionText = findLongestTextChild(node)

            if (questionText != null && starNodes.size == 5) {
                if (isRatingScaleLegend(questionText)) {
                    starNodes.forEach { it.recycle() }
                    yesNo?.first?.recycle()
                    yesNo?.second?.recycle()
                    return
                }
                
                results.add(DiscoveredQuestion(questionText, node, QuestionType.STAR_RATING, starNodes))
                seenRoots.add(node)
                yesNo?.first?.recycle()
                yesNo?.second?.recycle()
                return 
            } else if (questionText != null && yesNo != null) {
                if (isRatingScaleLegend(questionText)) {
                    starNodes.forEach { it.recycle() }
                    yesNo.first.recycle()
                    yesNo.second.recycle()
                    return
                }
                
                results.add(DiscoveredQuestion(questionText, node, QuestionType.YES_NO, listOf(yesNo.first, yesNo.second)))
                seenRoots.add(node)
                starNodes.forEach { it.recycle() }
                return
            }
            
            starNodes.forEach { it.recycle() }
            yesNo?.first?.recycle()
            yesNo?.second?.recycle()
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
                stars.add(n)
                return
            }
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
        
        val uniqueDesc = stars.map { it.contentDescription?.toString()?.trim() }.toSet()
        if (uniqueDesc.size == 5 && stars.size == 5) {
            return stars.sortedBy { it.contentDescription?.toString()?.trim() }
        }
        
        // Accept if we got more than 5 but can pick exactly 5 unique ones
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
        
        stars.forEach { it.recycle() }
        return emptyList()
    }

    fun findYesNoChildren(node: AccessibilityNodeInfo): Pair<AccessibilityNodeInfo, AccessibilityNodeInfo>? {
        var yesNode: AccessibilityNodeInfo? = null
        var noNode: AccessibilityNodeInfo? = null

        fun collect(n: AccessibilityNodeInfo) {
            if (yesNode != null && noNode != null) return
            
            val text = n.text?.toString()?.trim()
            val desc = n.contentDescription?.toString()?.trim()
            if (text.equals("Yes", ignoreCase = true) || desc.equals("Yes", ignoreCase = true)) {
                yesNode = n
            } else if (text.equals("No", ignoreCase = true) || desc.equals("No", ignoreCase = true)) {
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

    fun findScrollableContainer(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                val found = findScrollableContainer(child)
                if (found != null) {
                    if (found !== child) child.recycle()
                    return found
                }
                child.recycle()
            }
        }
        return null
    }

    fun performScrollForward(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    /**
     * Gesture-based scroll fallback for devices where ACTION_SCROLL_FORWARD doesn't work.
     */
    fun performGestureScroll(service: AccessibilityService, screenHeight: Int, screenWidth: Int, forward: Boolean = true): Boolean {
        val centerX = screenWidth / 2f
        val startY: Float
        val endY: Float
        
        if (forward) {
            startY = screenHeight * 0.75f
            endY = screenHeight * 0.25f
        } else {
            startY = screenHeight * 0.25f
            endY = screenHeight * 0.75f
        }
        
        val path = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        
        return service.dispatchGesture(gesture, null, null)
    }

    /**
     * Attempts to perform a click on the given [node] or its parents.
     * Does NOT recycle the node — caller is responsible.
     */
    fun performClickOnNodeOrParent(node: AccessibilityNodeInfo?, service: AccessibilityService, logTag: String = "NodeFinder"): Boolean {
        if (node == null) {
            AppLogger.e(logTag, "Cannot click: node is null")
            return false
        }
        
        AppLogger.d(logTag, "Attempting to click node with text='${node.text}' desc='${node.contentDescription}' class='${node.className}'")

        // 1. Try standard ACTION_CLICK on the node or its parents
        var current: AccessibilityNodeInfo? = node
        var first = true
        var actionClickSuccess = false
        var depth = 0
        while (current != null && depth < 10) {
            val isClickable = current.isClickable
            AppLogger.d(logTag, "  -> Checking depth=$depth: class='${current.className}' isClickable=$isClickable")
            
            if (isClickable) {
                actionClickSuccess = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                AppLogger.d(logTag, "  -> ACTION_CLICK at depth=$depth returned: $actionClickSuccess")
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
            return true
        }

        AppLogger.d(logTag, "ACTION_CLICK failed. Falling back to gesture tap.")

        // 2. Fallback: physical gesture tap
        val rect = Rect()
        node.getBoundsInScreen(rect)
        AppLogger.d(logTag, "  -> Target bounds: $rect")
        
        if (!rect.isEmpty && rect.centerX() > 0 && rect.centerY() > 0) {
            val path = Path().apply {
                moveTo(rect.centerX().toFloat(), rect.centerY().toFloat())
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 120))
                .build()
            
            val dispatched = service.dispatchGesture(gesture, null, null)
            AppLogger.d(logTag, "  -> dispatchGesture returned: $dispatched")
            return dispatched
        } else {
            AppLogger.e(logTag, "  -> Cannot gesture: invalid bounds")
        }

        return false
    }

    /**
     * Simple visibility check — center point must be within the visible container area.
     */
    fun isNodeVisible(node: AccessibilityNodeInfo, containerRect: Rect?, screenHeight: Int, screenWidth: Int): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return false
        
        val centerY = rect.centerY()
        val centerX = rect.centerX()
        
        val top = containerRect?.top ?: 0
        val bottom = containerRect?.bottom ?: screenHeight
        val left = containerRect?.left ?: 0
        val right = containerRect?.right ?: screenWidth
        
        val margin = 20
        return centerY > top + margin && 
               centerY < bottom - margin &&
               centerX >= left && 
               centerX <= right
    }
}

@file:Suppress("DEPRECATION")

package com.example.autoreview.service

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
        
        fun walk(node: AccessibilityNodeInfo): Boolean {
            if (node in seenRoots) return true
            
            var foundInSubtree = false
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child -> 
                    if (walk(child)) {
                        foundInSubtree = true
                    }
                }
            }

            if (foundInSubtree) return true
            
            val starNodes = findStarLikeChildren(node)
            val yesNo = findYesNoChildren(node)
            val questionText = findLongestTextChild(node)

            if (questionText != null && starNodes.size == 5) {
                AppLogger.d("NodeFinder", "Discovered STAR question: $questionText")
                results.add(DiscoveredQuestion(questionText, node, QuestionType.STAR_RATING, starNodes))
                seenRoots.add(node)
                yesNo?.first?.recycle()
                yesNo?.second?.recycle()
                return true
            } else if (questionText != null && yesNo != null) {
                AppLogger.d("NodeFinder", "Discovered YES/NO question: $questionText")
                results.add(DiscoveredQuestion(questionText, node, QuestionType.YES_NO, listOf(yesNo.first, yesNo.second)))
                seenRoots.add(node)
                starNodes.forEach { it.recycle() }
                return true
            } else if (questionText != null && Regex("^\\d+\\n.*role model.*", RegexOption.IGNORE_CASE).containsMatchIn(questionText)) {
                // It's the final Yes/No question but missing interactive children (hidden).
                AppLogger.d("NodeFinder", "Discovered HIDDEN YES/NO question: $questionText")
                results.add(DiscoveredQuestion(questionText, node, QuestionType.YES_NO, emptyList()))
                seenRoots.add(node)
                starNodes.forEach { it.recycle() }
                return true
            }
            
            // Clean up unused allocated collections
            starNodes.forEach { it.recycle() }
            yesNo?.first?.recycle()
            yesNo?.second?.recycle()

            return false
        }
        walk(root)
        return results
    }

    fun findStarLikeChildren(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val stars = mutableListOf<AccessibilityNodeInfo>()
        
        fun collect(n: AccessibilityNodeInfo) {
            val text = n.text?.toString()?.trim()
            val desc = n.contentDescription?.toString()?.trim()
            if ((text != null && text.matches(Regex("^[1-5]$"))) ||
                (desc != null && desc.matches(Regex("^[1-5]$")))) {
                stars.add(n)
            } else {
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let { collect(it) }
                }
            }
        }
        collect(node)
        
        // Filter out if not exactly 5 unique star descriptions
        val uniqueDesc = stars.map { it.contentDescription?.toString()?.trim() ?: it.text?.toString()?.trim() }.toSet()
        if (uniqueDesc.size == 5 && stars.size == 5) {
            return stars.sortedBy { it.contentDescription?.toString()?.trim() ?: it.text?.toString()?.trim() }
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
            return Pair(yesNode, noNode)
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
        val scrolled = node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        return scrolled
    }

    /**
     * Attempts to perform a click on the given [node] or its parents.
     * Note: This method DOES NOT recycle the original [node]. The caller is responsible for recycling it.
     */
    fun performClickOnNodeOrParent(node: AccessibilityNodeInfo?, service: android.accessibilityservice.AccessibilityService, logTag: String = "NodeFinder"): Boolean {
        if (node == null) {
            AppLogger.e(logTag, "Cannot click: node is null")
            return false
        }
        
        AppLogger.d(logTag, "Attempting to click node with text='${node.text}' desc='${node.contentDescription}' class='${node.className}'")

        // 1. Try standard Accessibility ACTION_CLICK on the node or its parents
        var current = node
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

        AppLogger.d(logTag, "Standard ACTION_CLICK failed or no clickable parent found. Falling back to gesture.")

        // 2. Fallback: Attempt physical gesture click
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        AppLogger.d(logTag, "  -> Target screen bounds: $rect (isEmpty=${rect.isEmpty})")
        
        if (!rect.isEmpty && rect.centerX() > 0 && rect.centerY() > 0) {
            val path = android.graphics.Path().apply {
                moveTo(rect.centerX().toFloat(), rect.centerY().toFloat())
            }
            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50))
                .build()
            
            val dispatched = service.dispatchGesture(gesture, null, null)
            AppLogger.d(logTag, "  -> dispatchGesture returned: $dispatched")
            return dispatched
        } else {
            AppLogger.e(logTag, "  -> Cannot dispatch gesture: invalid bounds")
        }

        return false
    }

    /**
     * Performs a physical screen tap at coordinates relative to the bounds of the given node.
     * This is used for hidden Yes/No buttons that are visually present but missing from the accessibility tree.
     */
    fun performGestureClickByOffset(
        node: AccessibilityNodeInfo,
        service: android.accessibilityservice.AccessibilityService,
        isYes: Boolean
    ): Boolean {
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        
        // Since they are side-by-side below the text:
        // We tap a bit below the text bounds. 80 pixels is a safe bet for most screen densities.
        val y = bounds.bottom + 80f
        
        // "Yes" is on the left, "No" is on the right.
        // We tap at 25% of the width for Yes, and 75% for No.
        val x = if (isYes) {
            bounds.left + (bounds.width() * 0.25f)
        } else {
            bounds.left + (bounds.width() * 0.75f)
        }
        
        AppLogger.d("NodeFinder", "Clicking hidden Yes/No at x=$x, y=$y (bounds: $bounds, isYes=$isYes)")
        
        val path = android.graphics.Path().apply {
            moveTo(x, y)
        }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
        
        return service.dispatchGesture(gesture, null, null)
    }

    fun isNodeVisible(node: AccessibilityNodeInfo, containerRect: android.graphics.Rect?, screenHeight: Int, screenWidth: Int): Boolean {
        val rect = android.graphics.Rect()
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

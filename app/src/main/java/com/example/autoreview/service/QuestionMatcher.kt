package com.example.autoreview.service

import com.example.autoreview.data.QuestionPreset
import java.util.Locale

object QuestionMatcher {

    /**
     * Finds the best matching QuestionPreset for the given live text from the screen.
     * Uses a simple token overlap (Jaccard similarity) approach.
     */
    fun bestMatch(liveText: String, presets: List<QuestionPreset>): QuestionPreset? {
        if (presets.isEmpty()) return null

        val liveTokens = tokenize(liveText)
        if (liveTokens.isEmpty()) return null

        var bestPreset: QuestionPreset? = null
        var bestScore = 0.0

        for (preset in presets) {
            val presetTokens = tokenize(preset.questionTextKey)
            if (presetTokens.isEmpty()) continue

            val intersection = liveTokens.intersect(presetTokens).size
            val union = liveTokens.union(presetTokens).size
            val score = intersection.toDouble() / union.toDouble()

            if (score > bestScore) {
                bestScore = score
                bestPreset = preset
            }
        }

        // 0.6 is a reasonable threshold for minor rewording or OCR/extraction differences
        return if (bestScore > 0.6) bestPreset else null
    }

    private fun tokenize(text: String): Set<String> {
        return text.lowercase(Locale.getDefault())
            .replace(Regex("[^a-z0-9\\s]"), "") // Remove punctuation
            .split(Regex("\\s+"))
            .filter { it.length > 2 } // Ignore very short words like "a", "is" which inflate overlap
            .toSet()
    }
}

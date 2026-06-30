package com.example.autoreview

import com.example.autoreview.data.PresetConfig
import com.example.autoreview.data.QuestionPreset
import com.example.autoreview.service.QuestionMatcher
import org.junit.Assert.*
import org.junit.Test

class QuestionMatcherTest {

    @Test
    fun `exact match returns correct preset`() {
        val preset = QuestionPreset("How would you rate the service?", 5)
        val match = QuestionMatcher.bestMatch("How would you rate the service?", listOf(preset))
        assertEquals(preset, match)
    }

    @Test
    fun `fuzzy match returns correct preset`() {
        val preset = QuestionPreset("How would you rate the service?", 5)
        val match = QuestionMatcher.bestMatch("How would you rate this service?", listOf(preset))
        assertEquals(preset, match)
    }

    @Test
    fun `below threshold returns null`() {
        val preset = QuestionPreset("How would you rate the service?", 5)
        val match = QuestionMatcher.bestMatch("What is your favorite color?", listOf(preset))
        assertNull(match)
    }
}

class PresetConfigTest {
    
    @Test
    fun `serialization works correctly`() {
        val config = PresetConfig(
            questions = listOf(QuestionPreset("Test", 5, true)),
            defaultStarRating = 3,
            defaultBinaryChoice = "No"
        )
        val json = PresetConfig.toJson(config)
        val decoded = PresetConfig.fromJson(json)
        
        assertEquals(config.defaultStarRating, decoded.defaultStarRating)
        assertEquals(config.defaultBinaryChoice, decoded.defaultBinaryChoice)
        assertEquals(config.questions.size, decoded.questions.size)
        assertEquals(config.questions[0].questionTextKey, decoded.questions[0].questionTextKey)
    }
}
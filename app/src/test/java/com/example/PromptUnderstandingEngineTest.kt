package com.example

import com.example.core.PromptUnderstandingEngine
import com.example.core.PromptUnderstandingResult
import com.example.data.local.AspectOption
import com.example.data.local.MediaMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptUnderstandingEngineTest {
    @Test
    fun restrictedPromptsBlockSilently() {
        val result = PromptUnderstandingEngine.analyze(
            rawPrompt = "Create a sexually explicit xxx video",
            selectedMode = MediaMode.VIDEO,
            selectedAspect = AspectOption.LANDSCAPE_16_9
        )
        assertTrue(result is PromptUnderstandingResult.BlockedSilently)
    }

    @Test
    fun parsesPhotoAndPortraitIntentFromPrompt() {
        val result = PromptUnderstandingEngine.analyze(
            rawPrompt = "Please generate a vertical photo of a golden retriever in neon rain",
            selectedMode = MediaMode.VIDEO,
            selectedAspect = AspectOption.LANDSCAPE_16_9
        )
        assertTrue(result is PromptUnderstandingResult.Ready)
        val intent = (result as PromptUnderstandingResult.Ready).intent
        assertEquals(MediaMode.IMAGE, intent.resolvedMode)
        assertEquals(AspectOption.PORTRAIT_9_16, intent.resolvedAspect)
        assertEquals("golden retriever in neon rain", intent.cleanedSubject)
        assertEquals("neon", intent.lightingAndMood)
        assertTrue(intent.colorMentions.contains("golden"))
    }

    @Test
    fun parsesVideoAndCameraIntentFromPrompt() {
        val result = PromptUnderstandingEngine.analyze(
            rawPrompt = "Create a widescreen drone shot video of waves crashing at sunset",
            selectedMode = MediaMode.IMAGE,
            selectedAspect = AspectOption.PORTRAIT_9_16
        )
        assertTrue(result is PromptUnderstandingResult.Ready)
        val intent = (result as PromptUnderstandingResult.Ready).intent
        assertEquals(MediaMode.VIDEO, intent.resolvedMode)
        assertEquals(AspectOption.LANDSCAPE_16_9, intent.resolvedAspect)
        assertEquals("drone shot", intent.cameraFraming)
        assertEquals("waves crashing", intent.motionCues)
    }
}

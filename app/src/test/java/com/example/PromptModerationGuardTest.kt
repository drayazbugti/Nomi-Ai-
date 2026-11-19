package com.example

import com.example.core.PromptModerationGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptModerationGuardTest {
    @Test
    fun restrictedExplicitPromptsAreBlocked() {
        assertTrue(PromptModerationGuard.isRestrictedPrompt("Create a xxx video"))
        assertTrue(PromptModerationGuard.isRestrictedPrompt("x x x scene"))
        assertTrue(PromptModerationGuard.isRestrictedPrompt("sexually explicit dance"))
        assertTrue(PromptModerationGuard.isRestrictedPrompt("nude portrait in studio"))
        assertTrue(PromptModerationGuard.isRestrictedPrompt("NSFW adult movie"))
    }

    @Test
    fun normalValidPromptsAreAllowed() {
        assertFalse(PromptModerationGuard.isRestrictedPrompt("Alpine sunset drone shot"))
        assertFalse(PromptModerationGuard.isRestrictedPrompt("Futuristic airplane cockpit at night"))
        assertFalse(PromptModerationGuard.isRestrictedPrompt("Minimalist glass perfume bottle"))
        assertFalse(PromptModerationGuard.isRestrictedPrompt("Classic neon Tokyo street in rain"))
    }
}

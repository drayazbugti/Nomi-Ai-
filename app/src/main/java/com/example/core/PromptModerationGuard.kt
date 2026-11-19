package com.example.core

import com.google.firebase.ai.type.PromptBlockedException
import com.google.firebase.ai.type.ResponseStoppedException

class SilentPromptBlockedException(
    message: String = "Restricted prompt blocked silently."
) : IllegalStateException(message)

object PromptModerationGuard {

    private val exactWordRegex = Regex(
        pattern = """\b(?:xxx|porn|pornography|pornhub|xvideos|xnxx|onlyfans|nsfw|hentai|erotic|erotica|sex|sexual|sexually|nude|nudes|nudity|naked|topless|bottomless|lingerie|fetish|bdsm|bondage|stripper|striptease|orgasm|masturbate|masturbating|masturbation|intercourse|genitals|genitalia|penis|vagina|boob|boobs|breast|breasts|nipple|nipples|buttocks|tits|dick|cock|pussy|blowjob|handjob|ecchi|lewd|r18|incest|bestiality|snuff|milf|camgirl)\b""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val spacedXxxRegex = Regex(
        pattern = """(?i)(?:^|\W)x[\s._\-*]*x[\s._\-*]*x(?:\W|$)"""
    )

    private val explicitPhraseList = listOf(
        "sexually explicit",
        "explicit sexual",
        "explicit adult",
        "adult video",
        "adult film",
        "adult movie",
        "adult photo",
        "18+ video",
        "18+ image",
        "without clothes",
        "no clothes",
        "take off clothes",
        "remove clothes",
        "undress",
        "making love",
        "sexual act",
        "hardcore sex",
        "softcore",
        "p0rn",
        "s3x"
    )

    fun isRestrictedPrompt(rawPrompt: String): Boolean {
        val trimmed = rawPrompt.trim()
        if (trimmed.isEmpty()) return false

        val normalized = trimmed.lowercase()
        if (exactWordRegex.containsMatchIn(normalized)) return true
        if (spacedXxxRegex.containsMatchIn(normalized)) return true

        val collapsed = normalized.replace(Regex("""\s+"""), " ")
        return explicitPhraseList.any { phrase -> collapsed.contains(phrase) }
    }

    fun isSafetyBlockException(throwable: Throwable?): Boolean {
        var cursor = throwable
        while (cursor != null) {
            if (cursor is SilentPromptBlockedException ||
                cursor is PromptBlockedException ||
                cursor is ResponseStoppedException
            ) {
                return true
            }
            val msg = cursor.message?.lowercase().orEmpty()
            if (msg.contains("safety") && (msg.contains("block") || msg.contains("finish_reason"))) {
                return true
            }
            cursor = cursor.cause
        }
        return false
    }
}

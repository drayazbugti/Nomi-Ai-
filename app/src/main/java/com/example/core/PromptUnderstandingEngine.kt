package com.example.core

import com.example.data.local.AspectOption
import com.example.data.local.MediaMode

data class ParsedPromptIntent(
    val originalPrompt: String,
    val cleanedSubject: String,
    val resolvedMode: MediaMode,
    val resolvedAspect: AspectOption,
    val visualStyle: String?,
    val lightingAndMood: String?,
    val cameraFraming: String?,
    val motionCues: String?,
    val colorMentions: List<String>,
    val directiveSummary: String
)

sealed interface PromptUnderstandingResult {
    data object Empty : PromptUnderstandingResult
    data object BlockedSilently : PromptUnderstandingResult
    data class Ready(val intent: ParsedPromptIntent) : PromptUnderstandingResult
}

/**
 * Parses and validates user prompts before AI generation:
 * - Silently blocks inappropriate, explicit, XXX, or sexually explicit requests.
 * - Extracts exact visual subject, medium intent (photo vs video), aspect ratio cues,
 *   camera framing, lighting/mood, motion cues, and colors so the AI produces media
 *   matching the user's description.
 */
object PromptUnderstandingEngine {

    private val leadingFillerRegex = Regex(
        pattern = """^(?:please\s+)?(?:can\s+you\s+)?(?:generate|create|make|render|draw|paint|design|produce|build|show\s+me)\s+(?:a|an|the|me\s+a|me\s+an)?\s*(?:(?:vertical|portrait|landscape|widescreen|cinematic|realistic|short)\s+)*(?:photo|photograph|picture|image|artwork|illustration|portrait|wallpaper|video|clip|reel|animation|movie|scene)?\s*(?:of|about|showing|with|featuring)?\s*(?:a|an|the)?\s*""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val explicitVideoCues = Regex(
        pattern = """\b(?:video|reel|clip|animation|animated|motion|timelapse|time-lapse|drone\s+shot|tracking\s+shot|panning|slow\s+motion|cinematic\s+scene|moving)\b""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val explicitImageCues = Regex(
        pattern = """\b(?:photo|photograph|picture|image|still|wallpaper|poster|logo|icon|portrait|illustration|painting|sketch|drawing|render\s+still)\b""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val portraitAspectCues = Regex(
        pattern = """\b(?:9\s*:\s*16|vertical|portrait|mobile\s+wallpaper|phone\s+wallpaper|stories|story|reels|shorts|tiktok)\b""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val landscapeAspectCues = Regex(
        pattern = """\b(?:16\s*:\s*9|widescreen|wide\s+shot|horizontal|landscape|desktop\s+wallpaper|cinema\s+wide|panoramic)\b""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val knownStyles = listOf(
        "photorealistic", "cinematic", "minimalist", "cyberpunk", "anime", "watercolor",
        "oil painting", "3d render", "isometric", "vintage", "retro", "noir",
        "futuristic", "abstract", "studio", "editorial", "pixel art", "vector",
        "illustration", "surreal", "fantasy", "sci-fi"
    )

    private val knownLightingAndMood = listOf(
        "golden hour", "sunset", "sunrise", "night", "moonlight", "neon",
        "rainy", "foggy", "misty", "dramatic lighting", "soft light", "studio lighting",
        "backlit", "warm glow", "dark moody", "sunny", "bright", "twilight", "stormy"
    )

    private val knownCameraCues = listOf(
        "drone shot", "aerial view", "close-up", "closeup", "macro", "wide shot",
        "low angle", "high angle", "first person", "cockpit view", "panning shot",
        "tracking shot", "zoom in", "slow motion", "over the shoulder", "symmetrical"
    )

    private val knownMotionVerbs = listOf(
        "running", "walking", "flying", "driving", "drifting", "falling",
        "flowing", "dancing", "spinning", "gliding", "racing", "floating",
        "waves crashing", "rain falling", "glowing", "exploding", "zooming"
    )

    private val knownColors = listOf(
        "red", "crimson", "blue", "cobalt", "cyan", "teal", "green", "emerald",
        "gold", "golden", "yellow", "amber", "orange", "purple", "violet",
        "pink", "magenta", "black", "white", "silver", "monochrome", "pastel"
    )

    fun analyze(
        rawPrompt: String,
        selectedMode: MediaMode,
        selectedAspect: AspectOption
    ): PromptUnderstandingResult {
        val normalized = rawPrompt.trim().replace(Regex("""\s+"""), " ")
        if (normalized.isEmpty()) {
            return PromptUnderstandingResult.Empty
        }

        // Strict silent moderation check before any intent parsing or network dispatch
        if (PromptModerationGuard.isRestrictedPrompt(normalized)) {
            return PromptUnderstandingResult.BlockedSilently
        }

        val cleanedSubject = leadingFillerRegex.replaceFirst(normalized, "").trim()
            .ifBlank { normalized }

        val resolvedMode = resolveMediaMode(normalized, selectedMode)
        val resolvedAspect = resolveAspectOption(normalized, selectedAspect)

        val lower = normalized.lowercase()
        val style = knownStyles.firstOrNull { lower.contains(it) }
        val lighting = knownLightingAndMood.firstOrNull { lower.contains(it) }
        val camera = knownCameraCues.firstOrNull { lower.contains(it) }
        val motion = knownMotionVerbs.firstOrNull { lower.contains(it) }
        val colors = knownColors.filter { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(lower) }

        val directiveParts = buildList {
            add("Primary subject: $cleanedSubject")
            style?.let { add("Visual style: $it") }
            lighting?.let { add("Lighting and atmosphere: $it") }
            camera?.let { add("Camera framing: $it") }
            motion?.let { add("Motion and action: $it") }
            if (colors.isNotEmpty()) {
                add("Explicit color cues: ${colors.joinToString(", ")}")
            }
            add("Framing ratio: ${resolvedAspect.label}")
        }

        return PromptUnderstandingResult.Ready(
            ParsedPromptIntent(
                originalPrompt = normalized,
                cleanedSubject = cleanedSubject,
                resolvedMode = resolvedMode,
                resolvedAspect = resolvedAspect,
                visualStyle = style,
                lightingAndMood = lighting,
                cameraFraming = camera,
                motionCues = motion,
                colorMentions = colors,
                directiveSummary = directiveParts.joinToString("\n- ", prefix = "- ")
            )
        )
    }

    private fun resolveMediaMode(prompt: String, selectedMode: MediaMode): MediaMode {
        val wantsVideo = explicitVideoCues.containsMatchIn(prompt)
        val wantsImage = explicitImageCues.containsMatchIn(prompt)
        return when {
            wantsVideo && !wantsImage -> MediaMode.VIDEO
            wantsImage && !wantsVideo -> MediaMode.IMAGE
            else -> selectedMode
        }
    }

    private fun resolveAspectOption(prompt: String, selectedAspect: AspectOption): AspectOption {
        val wantsPortrait = portraitAspectCues.containsMatchIn(prompt)
        val wantsLandscape = landscapeAspectCues.containsMatchIn(prompt)
        return when {
            wantsPortrait && !wantsLandscape -> AspectOption.PORTRAIT_9_16
            wantsLandscape && !wantsPortrait -> AspectOption.LANDSCAPE_16_9
            else -> selectedAspect
        }
    }
}

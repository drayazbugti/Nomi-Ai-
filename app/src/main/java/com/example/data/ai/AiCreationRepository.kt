package com.example.data.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import com.caverock.androidsvg.SVG
import com.example.core.FirebaseSafety
import com.example.core.GenerationControlCoordinator
import com.example.core.GenerationPhase
import com.example.core.ParsedPromptIntent
import com.example.core.PromptModerationGuard
import com.example.core.PromptUnderstandingEngine
import com.example.core.PromptUnderstandingResult
import com.example.core.SilentPromptBlockedException
import com.example.data.local.AspectOption
import com.example.data.local.MediaMode
import com.example.data.local.MediaStorageHelper
import com.example.data.local.StoryboardScene
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.HarmBlockThreshold
import com.google.firebase.ai.type.HarmCategory
import com.google.firebase.ai.type.SafetySetting
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "AiCreationRepository"
private const val REQUEST_TIMEOUT_MS = 60_000L

class FirebaseNotConfiguredException(
    message: String = "Firebase is not configured in this build.",
    cause: Throwable? = null
) : IllegalStateException(message, cause)

class AppCheckUnavailableException(
    message: String = "App attestation could not be verified. Please ensure network access and Google Play services are available, or launch with a registered App Check debug token.",
    cause: Throwable? = null
) : IllegalStateException(message, cause)

data class PlannedScene(
    val sceneNumber: Int,
    val title: String,
    val cameraDirection: String,
    val visualDescription: String,
    val durationSeconds: Int
)

class AiCreationRepository(
    context: Context,
    private val mediaStorage: MediaStorageHelper
) {
    private val appContext = context.applicationContext ?: context
    private val svgArtworkModelCache = ConcurrentHashMap<String, GenerativeModel>()
    private val storyboardModelCache = ConcurrentHashMap<String, GenerativeModel>()

    private val svgArtworkSchema by lazy {
        Schema.obj(
            properties = mapOf(
                "svg" to Schema.string(
                    description = "Complete valid standalone SVG 1.1 document starting with <svg and ending with </svg>"
                ),
                "title" to Schema.string(
                    description = "Concise visual summary of the generated artwork"
                )
            )
        )
    }

    private val storyboardSchema by lazy {
        val sceneSchema = Schema.obj(
            properties = mapOf(
                "sceneNumber" to Schema.integer(description = "1-based scene index"),
                "title" to Schema.string(description = "Short punchy 2-4 word scene title"),
                "cameraDirection" to Schema.string(description = "Camera movement and framing cue"),
                "visualDescription" to Schema.string(description = "Detailed visual prompt for rendering this frame"),
                "durationSeconds" to Schema.integer(description = "Shot duration in seconds (2-5)")
            )
        )
        Schema.obj(
            properties = mapOf(
                "scenes" to Schema.array(
                    items = sceneSchema,
                    description = "Exactly 3 sequential cinematic scenes forming a cohesive short video storyboard"
                )
            )
        )
    }

    private val strictSafetySettings = listOf(
        SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, HarmBlockThreshold.LOW_AND_ABOVE)
    )

    private fun getSvgArtworkModelOrNull(modelId: String): GenerativeModel? {
        if (!FirebaseSafety.isInitialized(appContext)) {
            Log.w(TAG, "FirebaseApp is not initialized; skipping SVG model creation.")
            return null
        }
        return try {
            svgArtworkModelCache.getOrPut(modelId) {
                Firebase.ai.generativeModel(
                    modelName = modelId,
                    generationConfig = generationConfig {
                        responseMimeType = "application/json"
                        responseSchema = svgArtworkSchema
                    },
                    safetySettings = strictSafetySettings
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to create Firebase AI model ($modelId)", e)
            null
        }
    }

    private fun getStoryboardPlannerModelOrNull(modelId: String): GenerativeModel? {
        if (!FirebaseSafety.isInitialized(appContext)) {
            Log.w(TAG, "FirebaseApp is not initialized; skipping storyboard model creation.")
            return null
        }
        return try {
            storyboardModelCache.getOrPut(modelId) {
                Firebase.ai.generativeModel(
                    modelName = modelId,
                    generationConfig = generationConfig {
                        responseMimeType = "application/json"
                        responseSchema = storyboardSchema
                    },
                    safetySettings = strictSafetySettings
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to create storyboard planner model ($modelId)", e)
            null
        }
    }

    private fun sanitizeSvgDocument(svgRaw: String, width: Int, height: Int): String {
        val startIdx = svgRaw.indexOf("<svg", ignoreCase = true)
        val endIdx = svgRaw.lastIndexOf("</svg>", ignoreCase = true)
        var cleaned = if (startIdx >= 0 && endIdx > startIdx) {
            svgRaw.substring(startIdx, endIdx + "</svg>".length)
        } else {
            svgRaw.trim()
        }

        // Escape bare ampersands that break XML/SVG parsing
        cleaned = cleaned.replace(
            Regex("""&(?!(?:amp|lt|gt|quot|apos|#\d+|#x[0-9a-fA-F]+);)"""),
            "&amp;"
        )

        // Ensure xmlns is present on root <svg> tag
        val openTagEnd = cleaned.indexOf('>')
        if (openTagEnd > 0) {
            val openTag = cleaned.substring(0, openTagEnd)
            val additions = buildString {
                if (!openTag.contains("xmlns=", ignoreCase = true)) {
                    append(""" xmlns="http://www.w3.org/2000/svg"""")
                }
                if (!openTag.contains("viewBox=", ignoreCase = true)) {
                    append(""" viewBox="0 0 $width $height"""")
                }
            }
            if (additions.isNotEmpty()) {
                cleaned = openTag + additions + cleaned.substring(openTagEnd)
            }
        }

        return cleaned
    }

    private suspend fun renderSvgToPng(
        svgRaw: String,
        width: Int,
        height: Int
    ): String = withContext(Dispatchers.Default) {
        val sanitizedSvg = sanitizeSvgDocument(svgRaw, width, height)
        val svg = SVG.getFromString(sanitizedSvg)
        svg.documentWidth = width.toFloat()
        svg.documentHeight = height.toFloat()
        if (svg.documentViewBox == null) {
            svg.setDocumentViewBox(0f, 0f, width.toFloat(), height.toFloat())
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        svg.renderToCanvas(canvas)
        mediaStorage.saveBitmapAsPng(bitmap, prefix = "img")
    }

    suspend fun generateImageAsset(
        prompt: String,
        modelOption: ImageModelOption,
        aspect: AspectOption,
        referenceBitmap: Bitmap? = null,
        variationIndex: Int = 0,
        parsedIntent: ParsedPromptIntent? = null,
        onPhaseUpdate: (GenerationPhase, String) -> Unit = { _, _ -> }
    ): String {
        val intent = parsedIntent ?: when (val analyzed = PromptUnderstandingEngine.analyze(prompt, MediaMode.IMAGE, aspect)) {
            PromptUnderstandingResult.BlockedSilently -> throw SilentPromptBlockedException()
            PromptUnderstandingResult.Empty -> throw IllegalArgumentException("Prompt cannot be empty.")
            is PromptUnderstandingResult.Ready -> analyzed.intent
        }
        if (PromptModerationGuard.isRestrictedPrompt(intent.originalPrompt)) {
            throw SilentPromptBlockedException()
        }
        if (!FirebaseSafety.isInitialized(appContext)) {
            throw FirebaseNotConfiguredException()
        }

        val effectiveAspect = intent.resolvedAspect
        val (width, height) = when (effectiveAspect) {
            AspectOption.LANDSCAPE_16_9 -> 1280 to 720
            AspectOption.PORTRAIT_9_16 -> 720 to 1280
        }

        val variationHint = if (variationIndex > 0) {
            "Create distinct visual variation #${variationIndex + 1} with fresh framing, lighting, and palette."
        } else {
            ""
        }

        val generationPrompt = """
            You are a visual artist and SVG illustrator. Create a rich, detailed, cinematic SVG 1.1 illustration that faithfully depicts the user's exact request:
            User request: "${intent.originalPrompt}"
            Parsed creative intent:
            ${intent.directiveSummary}
            $variationHint
            Requirements:
            - Match the user's exact subject, environment, action, lighting, colors, and style cues faithfully without substituting unrelated subjects.
            - Output an <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $width $height" width="$width" height="$height"> document.
            - Fill the entire canvas with atmospheric background gradients (<linearGradient>, <radialGradient>), depth layers, lighting/glow effects, shadows, architectural/natural silhouettes, and detailed focal subject geometry using <rect>, <circle>, <ellipse>, <polygon>, <polyline>, and <path>.
            - Use pure SVG 1.1 attributes (fill, stroke, stroke-width, opacity, stop-color, stop-opacity). Do NOT use <foreignObject>, <script>, <image>, external URLs, or CSS filter:url().
            - Make it visually striking, cohesive, and free of placeholder watermarks or text unless requested by the user.
        """.trimIndent()

        val modelSequence = listOf(
            modelOption,
            ImageModelOption.GEMINI_4_HYDROGEN,
            ImageModelOption.GEMINI_3_8_FLASH,
            ImageModelOption.GEMINI_3_5_FLASH_LITE
        ).distinct()

        val svgXml = GenerationControlCoordinator.executeWithModelFallback(
            appContext = appContext,
            models = modelSequence,
            modelDisplayName = { it.displayName },
            timeoutMs = REQUEST_TIMEOUT_MS,
            onPhaseUpdate = onPhaseUpdate
        ) { candidateModel ->
            val model = getSvgArtworkModelOrNull(candidateModel.modelId)
                ?: throw FirebaseNotConfiguredException()
            val response = if (referenceBitmap != null) {
                model.generateContent(
                    content {
                        image(referenceBitmap)
                        text("Use the visual style, colors, and composition of this reference image while illustrating: $generationPrompt")
                    }
                )
            } else {
                model.generateContent(generationPrompt)
            }

            val jsonString = response.text
                ?: throw IllegalStateException("${candidateModel.displayName} returned an empty response.")
            val root = JSONObject(jsonString)
            val svg = root.optString("svg", "").trim()
            if (!svg.contains("<svg", ignoreCase = true)) {
                throw IllegalStateException("${candidateModel.displayName} returned invalid SVG artwork.")
            }
            svg
        }

        onPhaseUpdate(GenerationPhase.RENDERING_ASSET, modelOption.displayName)
        return renderSvgToPng(svgXml, width, height)
    }

    suspend fun generateStoryboardReel(
        prompt: String,
        aspect: AspectOption,
        preferredModel: ImageModelOption = ImageModelOption.GEMINI_4_HYDROGEN,
        referenceBitmap: Bitmap? = null,
        parsedIntent: ParsedPromptIntent? = null,
        onSceneProgress: (Int, Int, String) -> Unit
    ): List<StoryboardScene> {
        val intent = parsedIntent ?: when (val analyzed = PromptUnderstandingEngine.analyze(prompt, MediaMode.VIDEO, aspect)) {
            PromptUnderstandingResult.BlockedSilently -> throw SilentPromptBlockedException()
            PromptUnderstandingResult.Empty -> throw IllegalArgumentException("Prompt cannot be empty.")
            is PromptUnderstandingResult.Ready -> analyzed.intent
        }
        if (PromptModerationGuard.isRestrictedPrompt(intent.originalPrompt)) {
            throw SilentPromptBlockedException()
        }
        if (!FirebaseSafety.isInitialized(appContext)) {
            throw FirebaseNotConfiguredException()
        }

        val effectiveAspect = intent.resolvedAspect
        onSceneProgress(0, 3, "Understanding prompt & planning 3-scene reel with ${preferredModel.displayName}…")

        val plannerModels = listOf(
            preferredModel,
            ImageModelOption.GEMINI_4_HYDROGEN,
            ImageModelOption.GEMINI_3_8_FLASH,
            ImageModelOption.GEMINI_3_5_FLASH_LITE
        ).distinct()

        val plannedScenes = GenerationControlCoordinator.executeWithModelFallback(
            appContext = appContext,
            models = plannerModels,
            modelDisplayName = { it.displayName },
            timeoutMs = REQUEST_TIMEOUT_MS,
            onPhaseUpdate = { phase, label ->
                when (phase) {
                    GenerationPhase.RECOVERING_ATTESTATION ->
                        onSceneProgress(0, 3, "Verifying service connection…")
                    GenerationPhase.RETRYING_FALLBACK ->
                        onSceneProgress(0, 3, "Retrying storyboard with $label…")
                    else -> Unit
                }
            }
        ) { candidateModel ->
            val plannerModel = getStoryboardPlannerModelOrNull(candidateModel.modelId)
                ?: throw FirebaseNotConfiguredException()
            val plannerPrompt = """
                Create a cohesive 3-scene cinematic video storyboard in ${effectiveAspect.label} framing that brings the user's exact idea to life:
                User request: "${intent.originalPrompt}"
                Parsed creative intent:
                ${intent.directiveSummary}
                Keep subjects, colors, environment, and visual continuity consistent across all 3 scenes so each visualDescription works as a vivid standalone frame matching the user's description.
            """.trimIndent()

            val response = if (referenceBitmap != null) {
                plannerModel.generateContent(
                    content {
                        image(referenceBitmap)
                        text(plannerPrompt)
                    }
                )
            } else {
                plannerModel.generateContent(plannerPrompt)
            }

            val jsonString = response.text
                ?: throw IllegalStateException("Storyboard planner returned an empty response.")
            val root = JSONObject(jsonString)
            val scenesArray = root.getJSONArray("scenes")
            val parsed = (0 until minOf(3, scenesArray.length())).map { i ->
                val obj = scenesArray.getJSONObject(i)
                PlannedScene(
                    sceneNumber = obj.optInt("sceneNumber", i + 1),
                    title = obj.optString("title", "Scene ${i + 1}"),
                    cameraDirection = obj.optString("cameraDirection", intent.cameraFraming ?: "Smooth tracking shot"),
                    visualDescription = obj.optString("visualDescription", intent.cleanedSubject),
                    durationSeconds = obj.optInt("durationSeconds", 3).coerceIn(2, 6)
                )
            }
            if (parsed.isEmpty()) {
                throw IllegalStateException("Could not plan storyboard scenes for this prompt.")
            }
            parsed
        }

        val renderedScenes = mutableListOf<StoryboardScene>()
        plannedScenes.forEachIndexed { idx, planned ->
            onSceneProgress(
                idx,
                plannedScenes.size,
                "Rendering keyframe ${idx + 1}/${plannedScenes.size}: ${planned.title}…"
            )
            val framePrompt = "${planned.visualDescription}. Cinematic framing, ${planned.cameraDirection}, cohesive lighting."
            val frameIntent = intent.copy(
                originalPrompt = framePrompt,
                cleanedSubject = planned.visualDescription,
                cameraFraming = planned.cameraDirection
            )
            val framePath = generateImageAsset(
                prompt = framePrompt,
                modelOption = preferredModel,
                aspect = effectiveAspect,
                referenceBitmap = if (idx == 0) referenceBitmap else null,
                variationIndex = 0,
                parsedIntent = frameIntent
            )
            renderedScenes.add(
                StoryboardScene(
                    sceneNumber = planned.sceneNumber,
                    title = planned.title,
                    cameraDirection = planned.cameraDirection,
                    visualDescription = planned.visualDescription,
                    durationSeconds = planned.durationSeconds,
                    imageFilePath = framePath
                )
            )
        }
        onSceneProgress(plannedScenes.size, plannedScenes.size, "Storyboard reel complete")
        return renderedScenes
    }
}

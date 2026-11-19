package com.example.data.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import com.caverock.androidsvg.SVG
import com.example.core.FirebaseSafety
import com.example.core.PromptModerationGuard
import com.example.core.SilentPromptBlockedException
import com.example.data.local.AspectOption
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "AiCreationRepository"
private const val REQUEST_TIMEOUT_MS = 60_000L

class FirebaseNotConfiguredException(
    message: String = "Firebase is not configured in this build.",
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

    private fun isOfflineCause(throwable: Throwable): Boolean {
        var cursor: Throwable? = throwable
        while (cursor != null) {
            if (cursor is UnknownHostException || cursor is ConnectException || cursor is SocketException) {
                return true
            }
            val msg = cursor.message?.lowercase().orEmpty()
            if (msg.contains("unable to resolve host") ||
                msg.contains("failed to connect") ||
                msg.contains("network is unreachable") ||
                msg.contains("no address associated with hostname")
            ) {
                return true
            }
            cursor = cursor.cause
        }
        return false
    }

    private suspend fun renderSvgToPng(
        svgRaw: String,
        width: Int,
        height: Int
    ): String = withContext(Dispatchers.Default) {
        val startIdx = svgRaw.indexOf("<svg", ignoreCase = true)
        val endIdx = svgRaw.lastIndexOf("</svg>", ignoreCase = true)
        val cleanedSvg = if (startIdx >= 0 && endIdx > startIdx) {
            svgRaw.substring(startIdx, endIdx + "</svg>".length)
        } else {
            svgRaw.trim()
        }

        val svg = SVG.getFromString(cleanedSvg)
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
        variationIndex: Int = 0
    ): String {
        if (PromptModerationGuard.isRestrictedPrompt(prompt)) {
            throw SilentPromptBlockedException()
        }
        if (!FirebaseSafety.isInitialized(appContext)) {
            throw FirebaseNotConfiguredException()
        }

        val (width, height) = when (aspect) {
            AspectOption.LANDSCAPE_16_9 -> 1280 to 720
            AspectOption.PORTRAIT_9_16 -> 720 to 1280
        }

        val variationHint = if (variationIndex > 0) {
            "Create distinct visual variation #${variationIndex + 1} with fresh framing, lighting, and palette."
        } else {
            ""
        }

        val generationPrompt = """
            You are a visual artist and SVG illustrator. Create a rich, detailed, cinematic SVG 1.1 illustration for:
            "$prompt"
            $variationHint
            Requirements:
            - Output an <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $width $height" width="$width" height="$height"> document.
            - Fill the entire canvas with atmospheric background gradients (<linearGradient>, <radialGradient>), depth layers, lighting/glow effects, shadows, architectural/natural silhouettes, and detailed focal subject geometry using <rect>, <circle>, <ellipse>, <polygon>, <polyline>, and <path>.
            - Use pure SVG 1.1 attributes (fill, stroke, stroke-width, opacity, stop-color, stop-opacity). Do NOT use <foreignObject>, <script>, <image>, external URLs, or CSS filter:url().
            - Make it visually striking, cohesive, and faithful to the prompt without placeholder watermarks.
        """.trimIndent()

        val modelSequence = listOf(
            modelOption,
            ImageModelOption.GEMINI_4_HYDROGEN,
            ImageModelOption.GEMINI_3_8_FLASH,
            ImageModelOption.GEMINI_3_5_FLASH_LITE
        ).distinct()

        var lastError: Exception? = null
        for ((attempt, candidateModel) in modelSequence.withIndex()) {
            val model = getSvgArtworkModelOrNull(candidateModel.modelId)
                ?: throw FirebaseNotConfiguredException()
            try {
                val svgXml = withTimeout(REQUEST_TIMEOUT_MS) {
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
                return renderSvgToPng(svgXml, width, height)
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "Generation timed out on ${candidateModel.displayName}", e)
                lastError = IllegalStateException("Request timed out on ${candidateModel.displayName}. Please try again.")
                if (attempt < modelSequence.lastIndex) {
                    delay(350L)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (PromptModerationGuard.isSafetyBlockException(e)) {
                    throw SilentPromptBlockedException()
                }
                if (FirebaseSafety.isFirebaseNotInitializedError(e)) {
                    throw FirebaseNotConfiguredException(cause = e)
                }
                if (isOfflineCause(e)) {
                    Log.e(TAG, "Offline network error during image generation", e)
                    throw IOException("No internet connection. Please check the internet connection.", e)
                }
                Log.w(TAG, "Attempt failed on ${candidateModel.displayName}, trying fallback if available", e)
                lastError = e
                if (attempt < modelSequence.lastIndex) {
                    delay(400L)
                }
            }
        }

        throw lastError ?: IllegalStateException("Image generation failed.")
    }

    suspend fun generateStoryboardReel(
        prompt: String,
        aspect: AspectOption,
        preferredModel: ImageModelOption = ImageModelOption.GEMINI_4_HYDROGEN,
        referenceBitmap: Bitmap? = null,
        onSceneProgress: (Int, Int, String) -> Unit
    ): List<StoryboardScene> {
        if (PromptModerationGuard.isRestrictedPrompt(prompt)) {
            throw SilentPromptBlockedException()
        }
        if (!FirebaseSafety.isInitialized(appContext)) {
            throw FirebaseNotConfiguredException()
        }
        onSceneProgress(0, 3, "Writing 3-scene storyboard with ${preferredModel.displayName}…")

        val plannerModelIds = listOf(
            preferredModel.modelId,
            ImageModelOption.GEMINI_4_HYDROGEN.modelId,
            ImageModelOption.GEMINI_3_8_FLASH.modelId,
            ImageModelOption.GEMINI_3_5_FLASH_LITE.modelId
        ).distinct()

        var plannedScenes: List<PlannedScene> = emptyList()
        var plannerError: Exception? = null

        for ((attempt, modelId) in plannerModelIds.withIndex()) {
            val plannerModel = getStoryboardPlannerModelOrNull(modelId)
                ?: throw FirebaseNotConfiguredException()
            try {
                plannedScenes = withTimeout(REQUEST_TIMEOUT_MS) {
                    val plannerPrompt = """
                        Create a cohesive 3-scene cinematic video storyboard in ${aspect.label} framing for this concept:
                        "$prompt"
                        Keep visual continuity consistent across all 3 scenes so each visualDescription works as a rich standalone visual frame prompt.
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
                    (0 until minOf(3, scenesArray.length())).map { i ->
                        val obj = scenesArray.getJSONObject(i)
                        PlannedScene(
                            sceneNumber = obj.optInt("sceneNumber", i + 1),
                            title = obj.optString("title", "Scene ${i + 1}"),
                            cameraDirection = obj.optString("cameraDirection", "Smooth tracking shot"),
                            visualDescription = obj.optString("visualDescription", prompt),
                            durationSeconds = obj.optInt("durationSeconds", 3).coerceIn(2, 6)
                        )
                    }
                }
                if (plannedScenes.isNotEmpty()) break
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "Storyboard planner timed out on $modelId", e)
                plannerError = IllegalStateException("Storyboard planning timed out. Please try again.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (PromptModerationGuard.isSafetyBlockException(e)) {
                    throw SilentPromptBlockedException()
                }
                if (FirebaseSafety.isFirebaseNotInitializedError(e)) {
                    throw FirebaseNotConfiguredException(cause = e)
                }
                if (isOfflineCause(e)) {
                    Log.e(TAG, "Offline network error during storyboard planning", e)
                    throw IOException("No internet connection. Please check the internet connection.", e)
                }
                Log.w(TAG, "Storyboard planner failed on $modelId", e)
                plannerError = e
                if (attempt < plannerModelIds.lastIndex) {
                    delay(400L)
                }
            }
        }

        if (plannedScenes.isEmpty()) {
            throw plannerError ?: IllegalStateException("Could not plan storyboard scenes for this prompt.")
        }

        val renderedScenes = mutableListOf<StoryboardScene>()
        plannedScenes.forEachIndexed { idx, planned ->
            onSceneProgress(
                idx,
                plannedScenes.size,
                "Rendering keyframe ${idx + 1}/${plannedScenes.size}: ${planned.title}…"
            )
            val framePrompt = "${planned.visualDescription}. Cinematic framing, ${planned.cameraDirection}, cohesive lighting."
            val framePath = generateImageAsset(
                prompt = framePrompt,
                modelOption = preferredModel,
                aspect = aspect,
                referenceBitmap = if (idx == 0) referenceBitmap else null,
                variationIndex = 0
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

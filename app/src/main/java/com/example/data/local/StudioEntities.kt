package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.json.JSONArray
import org.json.JSONObject

enum class MediaMode(val label: String) {
    IMAGE("Image"),
    VIDEO("Video")
}

enum class AspectOption(val label: String) {
    LANDSCAPE_16_9("16:9"),
    PORTRAIT_9_16("9:16")
}

enum class AssetKind {
    AI_IMAGE,
    AI_STORYBOARD_REEL,
    UPLOADED_IMAGE,
    UPLOADED_VIDEO
}

enum class AssetFilter(val label: String) {
    ALL("All"),
    IMAGES("Images"),
    VIDEOS("Videos")
}

@Entity(tableName = "studio_sessions")
data class StudioSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long
)

@Entity(tableName = "studio_assets")
data class StudioAssetEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val kind: String,
    val prompt: String,
    val modelLabel: String,
    val aspectRatio: String,
    val primaryFilePath: String,
    val storyboardJson: String? = null,
    val createdAt: Long,
    val isTrashed: Boolean = false,
    val trashedAt: Long? = null,
    val generationDurationMs: Long? = null,
    val folderTag: String? = null
) {
    val assetKind: AssetKind
        get() = runCatching { AssetKind.valueOf(kind) }.getOrDefault(AssetKind.AI_IMAGE)

    fun parseStoryboardScenes(): List<StoryboardScene> {
        val raw = storyboardJson ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { idx ->
                val obj = array.getJSONObject(idx)
                StoryboardScene(
                    sceneNumber = obj.optInt("sceneNumber", idx + 1),
                    title = obj.optString("title", "Scene ${idx + 1}"),
                    cameraDirection = obj.optString("cameraDirection", "Smooth tracking shot"),
                    visualDescription = obj.optString("visualDescription", ""),
                    durationSeconds = obj.optInt("durationSeconds", 3),
                    imageFilePath = obj.optString("imageFilePath", primaryFilePath)
                )
            }
        }.getOrDefault(emptyList())
    }
}

@Entity(tableName = "prompt_history")
data class PromptHistoryEntity(
    @PrimaryKey val id: String,
    val promptText: String,
    val mediaMode: String,
    val aspectRatio: String,
    val modelLabel: String,
    val createdAt: Long
)

data class StoryboardScene(
    val sceneNumber: Int,
    val title: String,
    val cameraDirection: String,
    val visualDescription: String,
    val durationSeconds: Int,
    val imageFilePath: String
) {
    companion object {
        fun toJsonString(scenes: List<StoryboardScene>): String {
            val array = JSONArray()
            scenes.forEach { scene ->
                val obj = JSONObject().apply {
                    put("sceneNumber", scene.sceneNumber)
                    put("title", scene.title)
                    put("cameraDirection", scene.cameraDirection)
                    put("visualDescription", scene.visualDescription)
                    put("durationSeconds", scene.durationSeconds)
                    put("imageFilePath", scene.imageFilePath)
                }
                array.put(obj)
            }
            return array.toString()
        }
    }
}

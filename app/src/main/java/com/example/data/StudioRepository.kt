package com.example.data

import com.example.data.local.AssetKind
import com.example.data.local.MediaStorageHelper
import com.example.data.local.PromptHistoryDao
import com.example.data.local.PromptHistoryEntity
import com.example.data.local.StudioAssetDao
import com.example.data.local.StudioAssetEntity
import com.example.data.local.StudioSessionDao
import com.example.data.local.StudioSessionEntity
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class StudioRepository(
    private val sessionDao: StudioSessionDao,
    private val assetDao: StudioAssetDao,
    private val promptHistoryDao: PromptHistoryDao,
    private val mediaStorage: MediaStorageHelper
) {
    val sessions: Flow<List<StudioSessionEntity>> = sessionDao.getAllSessions()
    val trashedAssets: Flow<List<StudioAssetEntity>> = assetDao.getTrashedAssets()
    val promptHistory: Flow<List<PromptHistoryEntity>> = promptHistoryDao.getAllHistory()
    val distinctFolderTags: Flow<List<String>> = assetDao.getDistinctFolderTags()

    fun observeAssetsForSession(sessionId: String): Flow<List<StudioAssetEntity>> =
        assetDao.getAssetsForSession(sessionId)

    suspend fun updateAssetFolderTag(assetId: String, folderTag: String?) {
        val normalized = folderTag?.trim()?.takeIf { it.isNotEmpty() }
        assetDao.updateAssetFolderTag(assetId, normalized)
    }

    suspend fun createSession(customTitle: String? = null): StudioSessionEntity {
        val now = System.currentTimeMillis()
        val formatter = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
        val title = customTitle ?: formatter.format(Date(now))
        val session = StudioSessionEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            createdAt = now
        )
        sessionDao.insertSession(session)
        return session
    }

    suspend fun renameSession(sessionId: String, title: String) {
        if (title.isNotBlank()) {
            sessionDao.updateSessionTitle(sessionId, title.trim())
        }
    }

    suspend fun deleteSession(session: StudioSessionEntity) {
        val sessionAssets = assetDao.getAllAssetsSnapshotForSession(session.id)
        sessionAssets.forEach { deleteBackingFiles(it) }
        assetDao.deleteAssetsForSession(session.id)
        sessionDao.deleteSessionById(session.id)
    }

    suspend fun saveAsset(asset: StudioAssetEntity) {
        assetDao.insertAsset(asset)
    }

    suspend fun moveAssetToTrash(asset: StudioAssetEntity) {
        assetDao.moveToTrash(asset.id, System.currentTimeMillis())
    }

    suspend fun moveAssetsToTrash(assetIds: Collection<String>) {
        if (assetIds.isEmpty()) return
        assetDao.moveMultipleToTrash(assetIds.toList(), System.currentTimeMillis())
    }

    suspend fun restoreAssetFromTrash(asset: StudioAssetEntity) {
        assetDao.restoreFromTrash(asset.id)
    }

    suspend fun permanentlyDeleteAsset(asset: StudioAssetEntity) {
        deleteBackingFiles(asset)
        assetDao.deleteAssetById(asset.id)
    }

    suspend fun emptyTrash(trashed: List<StudioAssetEntity>) {
        trashed.forEach { deleteBackingFiles(it) }
        assetDao.deleteAllTrashed()
    }

    suspend fun recordPromptHistory(
        promptText: String,
        mediaMode: String,
        aspectRatio: String,
        modelLabel: String
    ) {
        val trimmed = promptText.trim()
        if (trimmed.isEmpty()) return
        promptHistoryDao.insertHistory(
            PromptHistoryEntity(
                id = UUID.randomUUID().toString(),
                promptText = trimmed,
                mediaMode = mediaMode,
                aspectRatio = aspectRatio,
                modelLabel = modelLabel,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun deleteHistoryItem(id: String) {
        promptHistoryDao.deleteHistoryById(id)
    }

    suspend fun clearPromptHistory() {
        promptHistoryDao.clearAllHistory()
    }

    private fun deleteBackingFiles(asset: StudioAssetEntity) {
        mediaStorage.deleteFileQuietly(asset.primaryFilePath)
        if (asset.assetKind == AssetKind.AI_STORYBOARD_REEL) {
            asset.parseStoryboardScenes().forEach { scene ->
                mediaStorage.deleteFileQuietly(scene.imageFilePath)
            }
        }
    }
}

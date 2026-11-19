package com.example.data

import com.example.data.local.AssetKind
import com.example.data.local.MediaStorageHelper
import com.example.data.local.PromptHistoryDao
import com.example.data.local.PromptHistoryEntity
import com.example.data.local.StudioAssetDao
import com.example.data.local.StudioAssetEntity
import com.example.data.local.StudioSessionDao
import com.example.data.local.StudioSessionEntity
import com.example.data.remote.FirestorePromptHistory
import com.example.data.remote.FirestoreStudioAsset
import com.example.data.remote.FirestoreStudioRepository
import com.example.data.remote.FirestoreStudioSession
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class StudioRepository(
    private val sessionDao: StudioSessionDao,
    private val assetDao: StudioAssetDao,
    private val promptHistoryDao: PromptHistoryDao,
    private val mediaStorage: MediaStorageHelper,
    private val firestoreRepository: FirestoreStudioRepository? = null,
    private val currentUserId: String? = null
) {
    val sessions: Flow<List<StudioSessionEntity>> = sessionDao.getAllSessions()
    val trashedAssets: Flow<List<StudioAssetEntity>> = assetDao.getTrashedAssets()
    val promptHistory: Flow<List<PromptHistoryEntity>> = promptHistoryDao.getAllHistory()
    val distinctFolderTags: Flow<List<String>> = assetDao.getDistinctFolderTags()

    fun observeAssetsForSession(sessionId: String): Flow<List<StudioAssetEntity>> =
        assetDao.getAssetsForSession(sessionId)

    suspend fun syncSessionsFromRemote(remoteSessions: List<FirestoreStudioSession>) {
        val uid = currentUserId ?: return
        val remoteIds = remoteSessions.map { it.id }.filter { it.isNotBlank() }.toSet()
        val localSessions = sessionDao.getAllSessionsSnapshot()

        val mapped = remoteSessions
            .filter { it.id.isNotBlank() }
            .map { remote ->
                StudioSessionEntity(
                    id = remote.id,
                    title = remote.title.ifBlank { "Studio Project" },
                    createdAt = remote.createdAt?.toDate()?.time ?: System.currentTimeMillis()
                )
            }
        if (mapped.isNotEmpty()) {
            sessionDao.insertSessions(mapped)
        }

        // Push any local-only sessions created prior to sign-in up to Firestore
        localSessions.filter { !remoteIds.contains(it.id) }.forEach { localOnly ->
            firestoreRepository?.createSession(uid, localOnly.id, localOnly.title)
        }
    }

    suspend fun syncAssetsFromRemote(remoteAssets: List<FirestoreStudioAsset>) {
        val uid = currentUserId ?: return
        val localMap = assetDao.getAllAssetsSnapshot().associateBy { it.id }
        val remoteIds = remoteAssets.map { it.id }.filter { it.isNotBlank() }.toSet()

        val mapped = remoteAssets
            .filter { it.id.isNotBlank() }
            .map { remote ->
                val existingLocal = localMap[remote.id]
                val resolvedPath = if (existingLocal != null && File(existingLocal.primaryFilePath).exists()) {
                    existingLocal.primaryFilePath
                } else {
                    remote.primaryFilePath
                }
                val resolvedStoryboard = if (existingLocal?.storyboardJson != null) {
                    existingLocal.storyboardJson
                } else {
                    remote.storyboardJson
                }
                StudioAssetEntity(
                    id = remote.id,
                    sessionId = remote.sessionId,
                    kind = remote.kind,
                    prompt = remote.prompt,
                    modelLabel = remote.modelLabel,
                    aspectRatio = remote.aspectRatio,
                    primaryFilePath = resolvedPath,
                    storyboardJson = resolvedStoryboard,
                    createdAt = remote.createdAt?.toDate()?.time
                        ?: existingLocal?.createdAt
                        ?: System.currentTimeMillis(),
                    isTrashed = remote.trashed,
                    trashedAt = remote.trashedAtMs,
                    generationDurationMs = remote.generationDurationMs,
                    folderTag = remote.folderTag
                )
            }
        if (mapped.isNotEmpty()) {
            assetDao.insertAssets(mapped)
        }

        // Push any local-only assets created prior to sign-in up to Firestore
        val sessionTitleMap = sessionDao.getAllSessionsSnapshot().associate { it.id to it.title }
        localMap.values.filter { !remoteIds.contains(it.id) }.forEach { localOnly ->
            firestoreRepository?.saveAsset(
                userId = uid,
                asset = localOnly,
                fallbackSessionTitle = sessionTitleMap[localOnly.sessionId] ?: "Studio Project"
            )
        }
    }

    suspend fun syncPromptHistoryFromRemote(remoteHistory: List<FirestorePromptHistory>) {
        val uid = currentUserId ?: return
        val remoteIds = remoteHistory.map { it.id }.filter { it.isNotBlank() }.toSet()
        val localHistory = promptHistoryDao.getAllHistorySnapshot()

        val mapped = remoteHistory
            .filter { it.id.isNotBlank() }
            .map { remote ->
                PromptHistoryEntity(
                    id = remote.id,
                    promptText = remote.promptText,
                    mediaMode = remote.mediaMode,
                    aspectRatio = remote.aspectRatio,
                    modelLabel = remote.modelLabel,
                    createdAt = remote.createdAt?.toDate()?.time ?: System.currentTimeMillis()
                )
            }
        if (mapped.isNotEmpty()) {
            promptHistoryDao.insertHistoryList(mapped)
        }

        localHistory.filter { !remoteIds.contains(it.id) }.forEach { localOnly ->
            firestoreRepository?.recordPromptHistory(uid, localOnly)
        }
    }

    suspend fun updateAssetFolderTag(assetId: String, folderTag: String?) {
        val normalized = folderTag?.trim()?.takeIf { it.isNotEmpty() }
        assetDao.updateAssetFolderTag(assetId, normalized)
        val uid = currentUserId ?: return
        firestoreRepository?.updateAssetFolderTag(uid, assetId, normalized)
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
        val uid = currentUserId
        if (uid != null) {
            firestoreRepository?.createSession(uid, session.id, session.title)
        }
        return session
    }

    suspend fun renameSession(sessionId: String, title: String) {
        if (title.isNotBlank()) {
            val trimmed = title.trim()
            sessionDao.updateSessionTitle(sessionId, trimmed)
            val uid = currentUserId
            if (uid != null) {
                firestoreRepository?.renameSession(uid, sessionId, trimmed)
            }
        }
    }

    suspend fun deleteSession(session: StudioSessionEntity) {
        val sessionAssets = assetDao.getAllAssetsSnapshotForSession(session.id)
        sessionAssets.forEach { deleteBackingFiles(it) }
        assetDao.deleteAssetsForSession(session.id)
        sessionDao.deleteSessionById(session.id)
        val uid = currentUserId
        if (uid != null) {
            firestoreRepository?.deleteSessionAndAssets(
                userId = uid,
                sessionId = session.id,
                assetIds = sessionAssets.map { it.id }
            )
        }
    }

    suspend fun saveAsset(asset: StudioAssetEntity, sessionTitle: String = "Studio Project") {
        assetDao.insertAsset(asset)
        val uid = currentUserId
        if (uid != null) {
            firestoreRepository?.saveAsset(uid, asset, fallbackSessionTitle = sessionTitle)
        }
    }

    suspend fun moveAssetToTrash(asset: StudioAssetEntity) {
        val trashedAt = System.currentTimeMillis()
        assetDao.moveToTrash(asset.id, trashedAt)
        val uid = currentUserId
        if (uid != null) {
            firestoreRepository?.updateAssetTrashState(
                userId = uid,
                assetIds = listOf(asset.id),
                trashed = true,
                trashedAtMs = trashedAt
            )
        }
    }

    suspend fun moveAssetsToTrash(assetIds: Collection<String>) {
        if (assetIds.isEmpty()) return
        val trashedAt = System.currentTimeMillis()
        assetDao.moveMultipleToTrash(assetIds.toList(), trashedAt)
        val uid = currentUserId
        if (uid != null) {
            firestoreRepository?.updateAssetTrashState(
                userId = uid,
                assetIds = assetIds,
                trashed = true,
                trashedAtMs = trashedAt
            )
        }
    }

    suspend fun restoreAssetFromTrash(asset: StudioAssetEntity) {
        assetDao.restoreFromTrash(asset.id)
        val uid = currentUserId
        if (uid != null) {
            firestoreRepository?.updateAssetTrashState(
                userId = uid,
                assetIds = listOf(asset.id),
                trashed = false,
                trashedAtMs = null
            )
        }
    }

    suspend fun permanentlyDeleteAsset(asset: StudioAssetEntity) {
        deleteBackingFiles(asset)
        assetDao.deleteAssetById(asset.id)
        val uid = currentUserId
        if (uid != null) {
            firestoreRepository?.deleteAssets(uid, listOf(asset.id))
        }
    }

    suspend fun emptyTrash(trashed: List<StudioAssetEntity>) {
        trashed.forEach { deleteBackingFiles(it) }
        assetDao.deleteAllTrashed()
        val uid = currentUserId
        if (uid != null && trashed.isNotEmpty()) {
            firestoreRepository?.deleteAssets(uid, trashed.map { it.id })
        }
    }

    suspend fun recordPromptHistory(
        promptText: String,
        mediaMode: String,
        aspectRatio: String,
        modelLabel: String
    ) {
        val trimmed = promptText.trim()
        if (trimmed.isEmpty()) return
        val entry = PromptHistoryEntity(
            id = UUID.randomUUID().toString(),
            promptText = trimmed,
            mediaMode = mediaMode,
            aspectRatio = aspectRatio,
            modelLabel = modelLabel,
            createdAt = System.currentTimeMillis()
        )
        promptHistoryDao.insertHistory(entry)
        val uid = currentUserId
        if (uid != null) {
            firestoreRepository?.recordPromptHistory(uid, entry)
        }
    }

    suspend fun deleteHistoryItem(id: String) {
        promptHistoryDao.deleteHistoryById(id)
        val uid = currentUserId
        if (uid != null) {
            firestoreRepository?.deletePromptHistory(uid, id)
        }
    }

    suspend fun clearPromptHistory() {
        val existingIds = promptHistoryDao.getAllHistorySnapshot().map { it.id }
        promptHistoryDao.clearAllHistory()
        val uid = currentUserId
        if (uid != null && existingIds.isNotEmpty()) {
            firestoreRepository?.clearPromptHistory(uid, existingIds)
        }
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

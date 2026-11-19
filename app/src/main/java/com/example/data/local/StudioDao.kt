package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface StudioSessionDao {
    @Query("SELECT * FROM studio_sessions ORDER BY createdAt DESC")
    fun getAllSessions(): Flow<List<StudioSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: StudioSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSessions(sessions: List<StudioSessionEntity>)

    @Query("SELECT * FROM studio_sessions")
    suspend fun getAllSessionsSnapshot(): List<StudioSessionEntity>

    @Query("UPDATE studio_sessions SET title = :title WHERE id = :sessionId")
    suspend fun updateSessionTitle(sessionId: String, title: String)

    @Query("DELETE FROM studio_sessions WHERE id = :sessionId")
    suspend fun deleteSessionById(sessionId: String)
}

@Dao
interface StudioAssetDao {
    @Query("SELECT * FROM studio_assets WHERE sessionId = :sessionId AND isTrashed = 0 ORDER BY createdAt DESC")
    fun getAssetsForSession(sessionId: String): Flow<List<StudioAssetEntity>>

    @Query("SELECT * FROM studio_assets WHERE isTrashed = 1 ORDER BY trashedAt DESC")
    fun getTrashedAssets(): Flow<List<StudioAssetEntity>>

    @Query("SELECT * FROM studio_assets")
    suspend fun getAllAssetsSnapshot(): List<StudioAssetEntity>

    @Query("SELECT * FROM studio_assets WHERE sessionId = :sessionId")
    suspend fun getAllAssetsSnapshotForSession(sessionId: String): List<StudioAssetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAsset(asset: StudioAssetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAssets(assets: List<StudioAssetEntity>)

    @Query("UPDATE studio_assets SET isTrashed = 1, trashedAt = :trashedAt WHERE id = :assetId")
    suspend fun moveToTrash(assetId: String, trashedAt: Long)

    @Query("UPDATE studio_assets SET isTrashed = 1, trashedAt = :trashedAt WHERE id IN (:assetIds)")
    suspend fun moveMultipleToTrash(assetIds: List<String>, trashedAt: Long)

    @Query("UPDATE studio_assets SET isTrashed = 0, trashedAt = NULL WHERE id = :assetId")
    suspend fun restoreFromTrash(assetId: String)

    @Query("DELETE FROM studio_assets WHERE id = :assetId")
    suspend fun deleteAssetById(assetId: String)

    @Query("DELETE FROM studio_assets WHERE sessionId = :sessionId")
    suspend fun deleteAssetsForSession(sessionId: String)

    @Query("DELETE FROM studio_assets WHERE isTrashed = 1")
    suspend fun deleteAllTrashed()

    @Query("UPDATE studio_assets SET folderTag = :folderTag WHERE id = :assetId")
    suspend fun updateAssetFolderTag(assetId: String, folderTag: String?)

    @Query("SELECT DISTINCT folderTag FROM studio_assets WHERE folderTag IS NOT NULL AND folderTag != '' AND isTrashed = 0 ORDER BY folderTag ASC")
    fun getDistinctFolderTags(): Flow<List<String>>
}

@Dao
interface PromptHistoryDao {
    @Query("SELECT * FROM prompt_history ORDER BY createdAt DESC")
    fun getAllHistory(): Flow<List<PromptHistoryEntity>>

    @Query("SELECT * FROM prompt_history")
    suspend fun getAllHistorySnapshot(): List<PromptHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(entry: PromptHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistoryList(entries: List<PromptHistoryEntity>)

    @Query("DELETE FROM prompt_history WHERE id = :id")
    suspend fun deleteHistoryById(id: String)

    @Query("DELETE FROM prompt_history")
    suspend fun clearAllHistory()
}

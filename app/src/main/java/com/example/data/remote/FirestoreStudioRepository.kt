package com.example.data.remote

import android.content.Context
import com.example.R
import com.example.data.local.PromptHistoryEntity
import com.example.data.local.StudioAssetEntity
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class FirestoreStudioRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    private fun userDoc(userId: String) = db.collection("users").document(userId)

    private fun sessionsCollection(userId: String) = userDoc(userId).collection("sessions")

    private fun assetsCollection(userId: String) = userDoc(userId).collection("assets")

    private fun promptHistoryCollection(userId: String) = userDoc(userId).collection("prompt_history")

    fun observeSessions(userId: String): Flow<List<FirestoreStudioSession>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Signed-in user mismatch for sessions listener." }
        val path = "users/$userId/sessions"
        emitAll(
            sessionsCollection(userId)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        FirestoreStudioSession::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeAssets(userId: String): Flow<List<FirestoreStudioAsset>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Signed-in user mismatch for assets listener." }
        val path = "users/$userId/assets"
        emitAll(
            assetsCollection(userId)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        FirestoreStudioAsset::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observePromptHistory(userId: String): Flow<List<FirestorePromptHistory>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Signed-in user mismatch for prompt history listener." }
        val path = "users/$userId/prompt_history"
        emitAll(
            promptHistoryCollection(userId)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        FirestorePromptHistory::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun upsertUserProfile(user: FirebaseUser): Result<Unit> {
        val path = "users/${user.uid}"
        return try {
            val uid = requireUserId()
            check(uid == user.uid) { "Signed-in user changed; refusing cross-user profile write." }
            val displayName = (user.displayName?.takeIf { it.isNotBlank() }
                ?: user.email?.substringBefore("@")?.takeIf { it.isNotBlank() }
                ?: "Noomi Creator").take(120)
            val email = user.email?.trim()?.takeIf { it.isNotEmpty() }?.take(320)
            val photoUrl = user.photoUrl?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.take(1024)

            val docRef = userDoc(uid)
            val existingSnap = docRef.get().await()
            if (existingSnap.exists()) {
                val updatePayload = buildMap<String, Any> {
                    put("displayName", displayName)
                    if (email != null) put("email", email)
                    if (photoUrl != null) put("photoUrl", photoUrl)
                    put("updatedAt", FieldValue.serverTimestamp())
                }
                docRef.update(updatePayload).await()
            } else {
                val createPayload = mapOf(
                    "userId" to uid,
                    "displayName" to displayName,
                    "email" to email,
                    "photoUrl" to photoUrl,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                ).filterValues { it != null }
                docRef.set(createPayload).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun ensureSessionExists(userId: String, sessionId: String, title: String): Result<Unit> {
        val path = "users/$userId/sessions/$sessionId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user changed; refusing cross-user session write." }
            require(sessionId.isNotBlank()) { "Session ID must not be blank." }
            val safeTitle = title.trim().ifBlank { "Studio Project" }.take(120)
            val docRef = sessionsCollection(uid).document(sessionId)
            val snapshot = docRef.get().await()
            if (!snapshot.exists()) {
                val payload = mapOf(
                    "userId" to uid,
                    "title" to safeTitle,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(payload).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun createSession(userId: String, sessionId: String, title: String): Result<Unit> {
        val path = "users/$userId/sessions/$sessionId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user changed; refusing cross-user session write." }
            require(sessionId.isNotBlank()) { "Session ID must not be blank." }
            val safeTitle = title.trim().ifBlank { "Studio Project" }.take(120)
            val payload = mapOf(
                "userId" to uid,
                "title" to safeTitle,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            sessionsCollection(uid).document(sessionId).set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun renameSession(userId: String, sessionId: String, title: String): Result<Unit> {
        val path = "users/$userId/sessions/$sessionId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user changed; refusing cross-user session rename." }
            require(sessionId.isNotBlank()) { "Session ID must not be blank." }
            val safeTitle = title.trim().take(120)
            require(safeTitle.isNotEmpty()) { "Session title must not be blank." }
            sessionsCollection(uid).document(sessionId).update(
                mapOf(
                    "title" to safeTitle,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun deleteSessionAndAssets(
        userId: String,
        sessionId: String,
        assetIds: List<String>
    ): Result<Unit> {
        val path = "users/$userId/sessions/$sessionId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user changed; refusing cross-user session delete." }
            require(sessionId.isNotBlank()) { "Session ID must not be blank." }
            val batch = db.batch()
            assetIds.filter { it.isNotBlank() }.forEach { assetId ->
                batch.delete(assetsCollection(uid).document(assetId))
            }
            batch.delete(sessionsCollection(uid).document(sessionId))
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    suspend fun saveAsset(
        userId: String,
        asset: StudioAssetEntity,
        fallbackSessionTitle: String = "Studio Project"
    ): Result<Unit> {
        val path = "users/$userId/assets/${asset.id}"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user changed; refusing cross-user asset write." }
            require(asset.id.isNotBlank()) { "Asset ID must not be blank." }
            require(asset.sessionId.isNotBlank()) { "Asset sessionId must not be blank." }

            // Ensure parent session exists in Firestore to satisfy exists() rule
            ensureSessionExists(uid, asset.sessionId, fallbackSessionTitle).getOrThrow()

            val payload = mapOf(
                "userId" to uid,
                "sessionId" to asset.sessionId,
                "kind" to asset.kind,
                "prompt" to asset.prompt.trim().ifBlank { "Untitled creation" }.take(2000),
                "modelLabel" to asset.modelLabel.trim().ifBlank { "Gemini 4 Hydrogen" }.take(120),
                "aspectRatio" to asset.aspectRatio.trim().ifBlank { "16:9" }.take(32),
                "primaryFilePath" to asset.primaryFilePath.trim().ifBlank { "local://pending" }.take(1024),
                "storyboardJson" to asset.storyboardJson?.takeIf { it.isNotBlank() && it.length <= 20000 },
                "trashed" to asset.isTrashed,
                "trashedAtMs" to asset.trashedAt?.takeIf { it >= 0L },
                "generationDurationMs" to asset.generationDurationMs?.takeIf { it >= 0L },
                "folderTag" to asset.folderTag?.trim()?.takeIf { it.isNotEmpty() }?.take(60),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null }

            assetsCollection(uid).document(asset.id).set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun updateAssetFolderTag(
        userId: String,
        assetId: String,
        folderTag: String?
    ): Result<Unit> {
        val path = "users/$userId/assets/$assetId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user changed; refusing cross-user tag update." }
            require(assetId.isNotBlank()) { "Asset ID must not be blank." }
            val normalized = folderTag?.trim()?.takeIf { it.isNotEmpty() }?.take(60)
            val updates = mapOf<String, Any>(
                "folderTag" to (normalized ?: FieldValue.delete()),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            assetsCollection(uid).document(assetId).update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun updateAssetTrashState(
        userId: String,
        assetIds: Collection<String>,
        trashed: Boolean,
        trashedAtMs: Long?
    ): Result<Unit> {
        val path = "users/$userId/assets"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user changed; refusing cross-user trash update." }
            val validIds = assetIds.filter { it.isNotBlank() }
            if (validIds.isEmpty()) return Result.success(Unit)
            val batch = db.batch()
            validIds.forEach { assetId ->
                val updates = mapOf<String, Any>(
                    "trashed" to trashed,
                    "trashedAtMs" to if (trashed && trashedAtMs != null && trashedAtMs >= 0L) {
                        trashedAtMs
                    } else {
                        FieldValue.delete()
                    },
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                batch.update(assetsCollection(uid).document(assetId), updates)
            }
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun deleteAssets(userId: String, assetIds: Collection<String>): Result<Unit> {
        val path = "users/$userId/assets"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user changed; refusing cross-user asset delete." }
            val validIds = assetIds.filter { it.isNotBlank() }
            if (validIds.isEmpty()) return Result.success(Unit)
            val batch = db.batch()
            validIds.forEach { assetId ->
                batch.delete(assetsCollection(uid).document(assetId))
            }
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    suspend fun recordPromptHistory(userId: String, entry: PromptHistoryEntity): Result<Unit> {
        val path = "users/$userId/prompt_history/${entry.id}"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user changed; refusing cross-user history write." }
            require(entry.id.isNotBlank()) { "History ID must not be blank." }
            val safeMode = if (entry.mediaMode == "VIDEO") "VIDEO" else "IMAGE"
            val payload = mapOf(
                "userId" to uid,
                "promptText" to entry.promptText.trim().ifBlank { "Untitled prompt" }.take(2000),
                "mediaMode" to safeMode,
                "aspectRatio" to entry.aspectRatio.trim().ifBlank { "16:9" }.take(32),
                "modelLabel" to entry.modelLabel.trim().ifBlank { "Gemini 4 Hydrogen" }.take(120),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            promptHistoryCollection(uid).document(entry.id).set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun deletePromptHistory(userId: String, historyId: String): Result<Unit> {
        val path = "users/$userId/prompt_history/$historyId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user changed; refusing cross-user history delete." }
            require(historyId.isNotBlank()) { "History ID must not be blank." }
            promptHistoryCollection(uid).document(historyId).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    suspend fun clearPromptHistory(userId: String, historyIds: List<String>): Result<Unit> {
        val path = "users/$userId/prompt_history"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user changed; refusing cross-user history clear." }
            val validIds = historyIds.filter { it.isNotBlank() }
            if (validIds.isEmpty()) return Result.success(Unit)
            val batch = db.batch()
            validIds.forEach { historyId ->
                batch.delete(promptHistoryCollection(uid).document(historyId))
            }
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }
}

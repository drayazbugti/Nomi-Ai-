package com.example.data.remote

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@Keep
@IgnoreExtraProperties
data class FirestoreUserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val email: String? = null,
    val photoUrl: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class FirestoreStudioSession(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class FirestoreStudioAsset(
    @DocumentId val id: String = "",
    val userId: String = "",
    val sessionId: String = "",
    val kind: String = "AI_IMAGE",
    val prompt: String = "",
    val modelLabel: String = "",
    val aspectRatio: String = "16:9",
    val primaryFilePath: String = "",
    val storyboardJson: String? = null,
    val trashed: Boolean = false,
    val trashedAtMs: Long? = null,
    val generationDurationMs: Long? = null,
    val folderTag: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class FirestorePromptHistory(
    @DocumentId val id: String = "",
    val userId: String = "",
    val promptText: String = "",
    val mediaMode: String = "IMAGE",
    val aspectRatio: String = "16:9",
    val modelLabel: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

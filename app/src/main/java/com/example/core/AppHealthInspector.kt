package com.example.core

import android.content.Context
import com.example.data.local.AspectOption
import com.example.data.local.MediaMode
import java.util.concurrent.ConcurrentHashMap

enum class ServiceReadinessStatus {
    READY,
    RECOVERED_AND_READY,
    BLOCKED_OFFLINE,
    BLOCKED_UNCONFIGURED,
    SKIPPED_DUPLICATE
}

data class AppHealthReport(
    val isOnline: Boolean,
    val isFirebaseReady: Boolean,
    val hasActiveSession: Boolean,
    val activeGenerationCount: Int,
    val readinessStatus: ServiceReadinessStatus,
    val diagnosticNote: String? = null
)

/**
 * Runtime self-check algorithm that verifies connectivity, Firebase initialization,
 * session availability, and duplicate/error cooldown state before dispatching generation requests.
 */
class AppHealthInspector(
    context: Context,
    private val networkObserver: NetworkConnectivityObserver
) {
    private val appContext = context.applicationContext ?: context
    private val recentSubmissionTimes = ConcurrentHashMap<String, Long>()

    @Volatile
    private var lastErrorSignature: String? = null

    @Volatile
    private var lastErrorTimestampMs: Long = 0L

    companion object {
        private const val DUPLICATE_SUBMIT_WINDOW_MS = 1_200L
        private const val ERROR_DEDUP_WINDOW_MS = 3_500L
    }

    suspend fun inspectAndRecoverBeforeGeneration(
        prompt: String,
        mode: MediaMode,
        aspect: AspectOption,
        activeSessionId: String?,
        activeGenerationCount: Int,
        ensureSessionReady: suspend () -> String?
    ): AppHealthReport {
        val now = System.currentTimeMillis()
        val requestKey = "${mode.name}|${aspect.name}|${prompt.trim().lowercase()}"
        val previousSubmitAt = recentSubmissionTimes[requestKey]
        if (previousSubmitAt != null && (now - previousSubmitAt) < DUPLICATE_SUBMIT_WINDOW_MS) {
            return AppHealthReport(
                isOnline = networkObserver.isCurrentlyOnline(),
                isFirebaseReady = FirebaseSafety.isInitialized(appContext),
                hasActiveSession = !activeSessionId.isNullOrBlank(),
                activeGenerationCount = activeGenerationCount,
                readinessStatus = ServiceReadinessStatus.SKIPPED_DUPLICATE,
                diagnosticNote = "Duplicate submission ignored within debounce window."
            )
        }
        recentSubmissionTimes[requestKey] = now
        if (recentSubmissionTimes.size > 32) {
            recentSubmissionTimes.entries.removeIf { (now - it.value) > 30_000L }
        }

        // 1. Check live internet connectivity first
        val online = networkObserver.isCurrentlyOnline()
        if (!online) {
            return AppHealthReport(
                isOnline = false,
                isFirebaseReady = FirebaseSafety.isInitialized(appContext),
                hasActiveSession = !activeSessionId.isNullOrBlank(),
                activeGenerationCount = activeGenerationCount,
                readinessStatus = ServiceReadinessStatus.BLOCKED_OFFLINE,
                diagnosticNote = "Device is offline."
            )
        }

        // 2. Check & self-heal Firebase initialization
        var firebaseRecovered = false
        val firebaseReady = if (FirebaseSafety.isInitialized(appContext)) {
            true
        } else {
            val initializedNow = FirebaseSafety.ensureInitialized(appContext)
            if (initializedNow) {
                firebaseRecovered = true
            }
            initializedNow
        }

        if (!firebaseReady) {
            return AppHealthReport(
                isOnline = true,
                isFirebaseReady = false,
                hasActiveSession = !activeSessionId.isNullOrBlank(),
                activeGenerationCount = activeGenerationCount,
                readinessStatus = ServiceReadinessStatus.BLOCKED_UNCONFIGURED,
                diagnosticNote = "Firebase options unavailable in this build."
            )
        }

        // 3. Ensure active project session is available
        val resolvedSessionId = activeSessionId ?: runCatching { ensureSessionReady() }.getOrNull()
        val hasSession = !resolvedSessionId.isNullOrBlank()

        val status = if (firebaseRecovered) {
            ServiceReadinessStatus.RECOVERED_AND_READY
        } else {
            ServiceReadinessStatus.READY
        }

        return AppHealthReport(
            isOnline = true,
            isFirebaseReady = true,
            hasActiveSession = hasSession,
            activeGenerationCount = activeGenerationCount,
            readinessStatus = status
        )
    }

    fun shouldEmitErrorMessage(message: String): Boolean {
        val normalized = message.trim().lowercase()
        if (normalized.isEmpty()) return false
        val now = System.currentTimeMillis()
        val isDuplicate = lastErrorSignature == normalized && (now - lastErrorTimestampMs) < ERROR_DEDUP_WINDOW_MS
        lastErrorSignature = normalized
        lastErrorTimestampMs = now
        return !isDuplicate
    }

    fun resetErrorTracking() {
        lastErrorSignature = null
        lastErrorTimestampMs = 0L
    }
}

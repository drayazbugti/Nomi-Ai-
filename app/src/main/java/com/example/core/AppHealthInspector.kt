package com.example.core

import android.content.Context
import android.util.Log
import com.example.data.local.AspectOption
import com.example.data.local.MediaMode
import com.google.firebase.appcheck.FirebaseAppCheck
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap

enum class AppCheckReadiness {
    READY_DEBUG_INTENT,
    READY_RECAPTCHA,
    RECOVERED,
    UNAVAILABLE
}

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
    val appCheckReadiness: AppCheckReadiness,
    val hasActiveSession: Boolean,
    val activeGenerationCount: Int,
    val readinessStatus: ServiceReadinessStatus,
    val diagnosticNote: String? = null
)

/**
 * Runtime self-check algorithm that verifies connectivity, Firebase initialization,
 * App Check readiness, session availability, and duplicate/error cooldown state
 * before dispatching generation requests.
 */
class AppHealthInspector(
    private val context: Context,
    private val networkObserver: NetworkConnectivityObserver
) {
    private val appContext = context.applicationContext ?: context
    private val recentSubmissionTimes = ConcurrentHashMap<String, Long>()

    @Volatile
    private var lastErrorSignature: String? = null

    @Volatile
    private var lastErrorTimestampMs: Long = 0L

    companion object {
        private const val TAG = "AppHealthInspector"
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
                appCheckReadiness = AppCheckReadiness.UNAVAILABLE,
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
                appCheckReadiness = AppCheckReadiness.UNAVAILABLE,
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
                appCheckReadiness = AppCheckReadiness.UNAVAILABLE,
                hasActiveSession = !activeSessionId.isNullOrBlank(),
                activeGenerationCount = activeGenerationCount,
                readinessStatus = ServiceReadinessStatus.BLOCKED_UNCONFIGURED,
                diagnosticNote = "Firebase options unavailable in this build."
            )
        }

        // 3. Check App Check readiness and proactively recover if token exchange fails
        val appCheckState = inspectAndRecoverAppCheck()

        // 4. Ensure active project session is available
        val resolvedSessionId = activeSessionId ?: runCatching { ensureSessionReady() }.getOrNull()
        val hasSession = !resolvedSessionId.isNullOrBlank()

        val status = if (firebaseRecovered || appCheckState == AppCheckReadiness.RECOVERED) {
            ServiceReadinessStatus.RECOVERED_AND_READY
        } else {
            ServiceReadinessStatus.READY
        }

        return AppHealthReport(
            isOnline = true,
            isFirebaseReady = true,
            appCheckReadiness = appCheckState,
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

    private suspend fun inspectAndRecoverAppCheck(): AppCheckReadiness {
        val firebaseApp = FirebaseSafety.getDefaultAppOrNull(appContext) ?: return AppCheckReadiness.UNAVAILABLE
        val appCheck = runCatching { FirebaseAppCheck.getInstance(firebaseApp) }.getOrNull()
            ?: return AppCheckReadiness.UNAVAILABLE

        val tokenResult = runCatching { appCheck.getAppCheckToken(false).await() }.getOrNull()
        if (tokenResult != null && tokenResult.token.isNotBlank()) {
            return AppCheckReadiness.READY_RECAPTCHA
        }

        Log.w(TAG, "App Check token unavailable or empty during preflight; attempting proactive recovery.")
        val recovered = recoverAppCheckOnInvalidToken(appContext)
        return if (recovered) AppCheckReadiness.RECOVERED else AppCheckReadiness.UNAVAILABLE
    }
}

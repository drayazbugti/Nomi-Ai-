package com.example.core

import android.content.Context
import android.util.Log
import com.example.data.ai.AppCheckUnavailableException
import com.example.data.ai.FirebaseNotConfiguredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.UnknownHostException

enum class GenerationPhase {
    SELF_CHECKING,
    UNDERSTANDING_PROMPT,
    REQUESTING_MODEL,
    RECOVERING_ATTESTATION,
    RETRYING_FALLBACK,
    RENDERING_ASSET,
    COMPLETED
}

/**
 * Coordinates model selection, bounded retries, automatic App Check recovery,
 * offline short-circuiting, and fallback model progression so valid requests
 * complete smoothly without cascading repeated errors.
 */
object GenerationControlCoordinator {

    private const val TAG = "GenerationController"
    private const val DEFAULT_REQUEST_TIMEOUT_MS = 60_000L

    fun isOfflineCause(throwable: Throwable?): Boolean {
        var cursor = throwable
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

    suspend fun <M, R> executeWithModelFallback(
        appContext: Context,
        models: List<M>,
        modelDisplayName: (M) -> String,
        timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
        onPhaseUpdate: (GenerationPhase, String) -> Unit = { _, _ -> },
        block: suspend (M) -> R
    ): R {
        var lastError: Exception? = null
        var attemptedAppCheckRecovery = false

        for ((index, candidate) in models.withIndex()) {
            val label = modelDisplayName(candidate)
            var retriedAppCheckForCandidate = false
            var transientAttemptForCandidate = 0

            while (true) {
                try {
                    val phase = if (index == 0 && transientAttemptForCandidate == 0 && !retriedAppCheckForCandidate) {
                        GenerationPhase.REQUESTING_MODEL
                    } else {
                        GenerationPhase.RETRYING_FALLBACK
                    }
                    onPhaseUpdate(phase, label)
                    return withTimeout(timeoutMs) {
                        block(candidate)
                    }
                } catch (e: TimeoutCancellationException) {
                    Log.w(TAG, "Request timed out on $label (attempt ${transientAttemptForCandidate + 1})", e)
                    lastError = IllegalStateException("Request timed out on $label. Please try again.")
                    if (transientAttemptForCandidate == 0 && index < models.lastIndex) {
                        delay(300L)
                    }
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: FirebaseNotConfiguredException) {
                    throw e
                } catch (e: SilentPromptBlockedException) {
                    throw e
                } catch (e: Exception) {
                    if (PromptModerationGuard.isSafetyBlockException(e)) {
                        throw SilentPromptBlockedException()
                    }
                    if (FirebaseSafety.isFirebaseNotInitializedError(e)) {
                        throw FirebaseNotConfiguredException(cause = e)
                    }
                    if (isOfflineCause(e)) {
                        Log.e(TAG, "Offline network error during generation", e)
                        throw IOException("No internet connection. Please check the internet connection.", e)
                    }
                    if (isAppCheckTokenError(e)) {
                        Log.w(TAG, "App Check token rejected on $label", e)
                        if (!attemptedAppCheckRecovery && !retriedAppCheckForCandidate) {
                            attemptedAppCheckRecovery = true
                            retriedAppCheckForCandidate = true
                            onPhaseUpdate(GenerationPhase.RECOVERING_ATTESTATION, label)
                            val recovered = recoverAppCheckOnInvalidToken(appContext)
                            if (recovered) {
                                delay(250L)
                                continue
                            }
                        }
                        throw AppCheckUnavailableException(cause = e)
                    }

                    Log.w(TAG, "Generation attempt failed on $label", e)
                    lastError = e
                    transientAttemptForCandidate++

                    // Retry once on the primary model for transient format glitches, then advance to fallback model
                    if (index == 0 && transientAttemptForCandidate == 1) {
                        delay(250L)
                        continue
                    }
                    if (index < models.lastIndex) {
                        delay(350L)
                    }
                    break
                }
            }
        }

        throw lastError ?: IllegalStateException("Generation failed across available models.")
    }
}

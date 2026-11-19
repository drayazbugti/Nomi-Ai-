package com.example.core

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory
import kotlinx.coroutines.tasks.await

private const val TAG = "AppCheckInit"

// App Check contract keys used by Firebase SDK and the runtime harness.
private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"

private const val NOOMI_APPCHECK_PREFS = "noomi_appcheck_state"
private const val KEY_INTENT_TOKEN_CONFIGURED = "intent_debug_token_configured"

fun configureAppCheck(context: Context, intent: Intent?) {
    try {
        val firebaseApp = FirebaseSafety.getDefaultAppOrNull(context)
        if (firebaseApp == null) {
            Log.w(TAG, "Firebase configuration unavailable; skipping App Check initialization.")
            return
        }

        val appCheck = runCatching { FirebaseAppCheck.getInstance(firebaseApp) }.getOrNull()
        if (appCheck == null) {
            Log.w(TAG, "FirebaseAppCheck instance unavailable; skipping provider installation.")
            return
        }

        val statePrefs = context.getSharedPreferences(NOOMI_APPCHECK_PREFS, Context.MODE_PRIVATE)
        val debugPrefs = context.getSharedPreferences(
            "$DEBUG_STORE_PREFIX${firebaseApp.persistenceKey}",
            Context.MODE_PRIVATE
        )

        if (BuildConfig.DEBUG) {
            val intentDebugToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)
                ?: intent?.extras?.getString(DEBUG_TOKEN_EXTRA)

            if (!intentDebugToken.isNullOrBlank()) {
                // Synchronous commit() guarantees the debug secret is persisted before DebugAppCheckProviderFactory reads it.
                debugPrefs.edit()
                    .putString(DEBUG_SECRET_KEY, intentDebugToken.trim())
                    .commit()
                statePrefs.edit()
                    .putBoolean(KEY_INTENT_TOKEN_CONFIGURED, true)
                    .commit()
                appCheck.installAppCheckProviderFactory(
                    DebugAppCheckProviderFactory.getInstance()
                )
                Log.d(TAG, "DebugAppCheckProviderFactory installed with launch Intent token.")
                return
            }

            val hasVerifiedIntentToken = statePrefs.getBoolean(KEY_INTENT_TOKEN_CONFIGURED, false)
            val savedDebugSecret = debugPrefs.getString(DEBUG_SECRET_KEY, null)
            if (hasVerifiedIntentToken && !savedDebugSecret.isNullOrBlank()) {
                appCheck.installAppCheckProviderFactory(
                    DebugAppCheckProviderFactory.getInstance()
                )
                Log.d(TAG, "DebugAppCheckProviderFactory installed with persisted Intent debug secret.")
                return
            }

            // No debug token was provided by a test/emulator harness (e.g. direct APK install on a device).
            // Clear any self-generated unregistered secret and use the provisioned reCAPTCHA Enterprise provider if present.
            if (!savedDebugSecret.isNullOrBlank()) {
                debugPrefs.edit().remove(DEBUG_SECRET_KEY).commit()
            }

            if (installRecaptchaProviderIfConfigured(context, appCheck)) {
                Log.d(TAG, "Debug build without Intent token: installed RecaptchaAppCheckProviderFactory fallback.")
            } else {
                Log.w(TAG, "Debug build without Intent token or recaptcha_site_key; skipping DebugAppCheckProviderFactory.")
            }
        } else {
            installRecaptchaProviderIfConfigured(context, appCheck)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize Firebase App Check", e)
    }
}

suspend fun recoverAppCheckOnInvalidToken(context: Context): Boolean {
    return try {
        val firebaseApp = FirebaseSafety.getDefaultAppOrNull(context) ?: return false
        val appCheck = runCatching { FirebaseAppCheck.getInstance(firebaseApp) }.getOrNull() ?: return false

        val statePrefs = context.getSharedPreferences(NOOMI_APPCHECK_PREFS, Context.MODE_PRIVATE)
        val debugPrefs = context.getSharedPreferences(
            "$DEBUG_STORE_PREFIX${firebaseApp.persistenceKey}",
            Context.MODE_PRIVATE
        )

        // Clear any stale or invalid debug secret before switching to reCAPTCHA Enterprise attestation.
        statePrefs.edit().putBoolean(KEY_INTENT_TOKEN_CONFIGURED, false).commit()
        debugPrefs.edit().remove(DEBUG_SECRET_KEY).commit()

        val installed = installRecaptchaProviderIfConfigured(context, appCheck)
        if (!installed) {
            return false
        }

        val refreshedToken = runCatching { appCheck.getAppCheckToken(true).await() }.getOrNull()
        !refreshedToken?.token.isNullOrBlank()
    } catch (e: Exception) {
        Log.w(TAG, "App Check recovery fallback could not obtain a fresh token", e)
        false
    }
}

fun isAppCheckTokenError(throwable: Throwable?): Boolean {
    var cursor = throwable
    while (cursor != null) {
        val msg = cursor.message?.lowercase().orEmpty()
        if (msg.contains("app check token") ||
            msg.contains("firebase app check") ||
            msg.contains("appcheck")
        ) {
            return true
        }
        cursor = cursor.cause
    }
    return false
}

private fun installRecaptchaProviderIfConfigured(
    context: Context,
    appCheck: FirebaseAppCheck
): Boolean {
    val resId = context.resources.getIdentifier(
        RECAPTCHA_SITE_KEY_RES,
        "string",
        context.packageName
    )
    if (resId == 0) {
        Log.w(TAG, "recaptcha_site_key string resource not found in package.")
        return false
    }
    val siteKey = context.getString(resId)
    if (siteKey.isBlank()) {
        Log.w(TAG, "recaptcha_site_key resource string is blank.")
        return false
    }
    return try {
        appCheck.installAppCheckProviderFactory(
            RecaptchaAppCheckProviderFactory.getInstance(siteKey)
        )
        Log.d(TAG, "RecaptchaAppCheckProviderFactory installed.")
        true
    } catch (e: Exception) {
        Log.w(TAG, "Unable to install RecaptchaAppCheckProviderFactory", e)
        false
    }
}

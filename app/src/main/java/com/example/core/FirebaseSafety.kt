package com.example.core

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

object FirebaseSafety {
    private const val TAG = "FirebaseSafety"
    private val initLock = Any()

    fun ensureInitialized(context: Context): Boolean {
        val appContext = context.applicationContext ?: context
        synchronized(initLock) {
            return try {
                if (FirebaseApp.getApps(appContext).isNotEmpty()) {
                    true
                } else {
                    val options = runCatching { FirebaseOptions.fromResource(appContext) }.getOrNull()
                    if (options == null) {
                        Log.w(TAG, "FirebaseOptions not available in resources; skipping Firebase initialization.")
                        false
                    } else {
                        val initializedApp = FirebaseApp.initializeApp(appContext, options)
                            ?: FirebaseApp.initializeApp(appContext)
                        initializedApp != null || FirebaseApp.getApps(appContext).isNotEmpty()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Firebase initialization unavailable in this environment", e)
                false
            }
        }
    }

    fun isInitialized(context: Context): Boolean {
        val appContext = context.applicationContext ?: context
        return try {
            FirebaseApp.getApps(appContext).isNotEmpty() || ensureInitialized(appContext)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to verify Firebase initialization state", e)
            false
        }
    }

    fun getDefaultAppOrNull(context: Context): FirebaseApp? {
        if (!ensureInitialized(context)) return null
        return try {
            FirebaseApp.getInstance()
        } catch (e: Exception) {
            Log.w(TAG, "Default FirebaseApp instance unavailable", e)
            null
        }
    }

    fun isFirebaseNotInitializedError(throwable: Throwable?): Boolean {
        var cursor = throwable
        while (cursor != null) {
            val msg = cursor.message?.lowercase().orEmpty()
            if (msg.contains("default firebaseapp is not initialized") ||
                msg.contains("firebaseapp.initializeapp")
            ) {
                return true
            }
            cursor = cursor.cause
        }
        return false
    }
}

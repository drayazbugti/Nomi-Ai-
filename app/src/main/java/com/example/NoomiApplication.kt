package com.example

import android.app.Application
import android.util.Log
import com.example.core.FirebaseSafety

class NoomiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            // Safely initialize FirebaseApp at startup only when FirebaseOptions are present.
            FirebaseSafety.ensureInitialized(this)
        } catch (e: Exception) {
            Log.w("NoomiApplication", "Deferred Firebase startup initialization safely", e)
        }
    }
}

package com.example

import android.app.Application
import android.util.Log
import com.example.core.FirebaseSafety

class NoomiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            // Safely initialize FirebaseApp at startup only when FirebaseOptions are present.
            // App Check remains in MainActivity.onCreate() so the launch Intent debug token is preserved.
            FirebaseSafety.ensureInitialized(this)
        } catch (e: Exception) {
            Log.w("NoomiApplication", "Deferred Firebase startup initialization safely", e)
        }
    }
}

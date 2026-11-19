package com.example

import android.app.Application
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.core.FirebaseSafety
import com.example.core.safeAuthStateFlow
import com.example.core.safeCurrentUser
import com.example.core.signOutUser
import com.example.ui.auth.AuthScreen
import com.example.ui.studio.StudioScreen
import com.example.ui.studio.StudioViewModel
import com.example.ui.theme.NoomiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        setContent {
            val context = LocalContext.current
            val app = context.applicationContext as Application
            val firebaseConfigured = remember { FirebaseSafety.isInitialized(context) }

            if (firebaseConfigured) {
                val currentUser by remember {
                    safeAuthStateFlow(context)
                }.collectAsStateWithLifecycle(initialValue = safeCurrentUser(context))

                val user = currentUser
                if (user == null) {
                    LaunchedEffect(Unit) {
                        enableEdgeToEdge(
                            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
                        )
                    }
                    NoomiTheme(darkTheme = true) {
                        AuthScreen(onAuthSuccess = { })
                    }
                } else {
                    val uid = user.uid
                    val scope = rememberCoroutineScope()
                    val credentialManager = remember(context) { CredentialManager.create(context) }
                    key(uid) {
                        val studioViewModel: StudioViewModel = viewModel(
                            key = uid,
                            factory = StudioViewModel.provideFactory(app, uid)
                        )
                        val uiState by studioViewModel.uiState.collectAsStateWithLifecycle()

                        LaunchedEffect(uiState.isDarkTheme) {
                            if (uiState.isDarkTheme) {
                                enableEdgeToEdge(
                                    statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                                    navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
                                )
                            } else {
                                enableEdgeToEdge(
                                    statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                                    navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                                )
                            }
                        }

                        NoomiTheme(darkTheme = uiState.isDarkTheme) {
                            StudioScreen(
                                uiState = uiState,
                                viewModel = studioViewModel,
                                onSignOut = {
                                    signOutUser(
                                        context = context,
                                        credentialManager = credentialManager,
                                        scope = scope
                                    )
                                }
                            )
                        }
                    }
                }
            } else {
                val studioViewModel: StudioViewModel = viewModel(
                    factory = StudioViewModel.provideFactory(app, null)
                )
                val uiState by studioViewModel.uiState.collectAsStateWithLifecycle()

                LaunchedEffect(uiState.isDarkTheme) {
                    if (uiState.isDarkTheme) {
                        enableEdgeToEdge(
                            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
                        )
                    } else {
                        enableEdgeToEdge(
                            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                        )
                    }
                }

                NoomiTheme(darkTheme = uiState.isDarkTheme) {
                    StudioScreen(
                        uiState = uiState,
                        viewModel = studioViewModel
                    )
                }
            }
        }
    }
}

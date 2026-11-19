package com.example.core

import com.example.BuildConfig

object AppReleaseConfig {
    const val GITHUB_OWNER = "drayazbugti"
    const val GITHUB_REPO = "nomi-ai-video-and-image-generate"
    const val APK_FILE_NAME = "NoomiAI-v1.0.apk"

    const val LATEST_RELEASE_URL =
        "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    const val APK_DOWNLOAD_URL =
        "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/latest/download/$APK_FILE_NAME"

    val VERSION_NAME: String = BuildConfig.VERSION_NAME
    val VERSION_CODE: Int = BuildConfig.VERSION_CODE
    val PACKAGE_ID: String = BuildConfig.APPLICATION_ID
}

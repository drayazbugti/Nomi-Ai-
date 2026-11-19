package com.example.data.ai

enum class ImageModelOption(
    val modelId: String,
    val displayName: String,
    val subtitle: String
) {
    GEMINI_4_HYDROGEN(
        modelId = "gemini-4-hydrogen",
        displayName = "Gemini 4 Hydrogen",
        subtitle = "High-speed visual synthesis"
    ),
    GEMINI_3_8_FLASH(
        modelId = "gemini-3.8-flash",
        displayName = "Gemini 3.8 Flash",
        subtitle = "Balanced creative studio"
    ),
    GEMINI_3_5_FLASH_LITE(
        modelId = "gemini-3.5-flash-lite",
        displayName = "Gemini 3.5 Flash-Lite",
        subtitle = "Ultra-low latency drafts"
    )
}

enum class VideoModelOption(
    val modelId: String,
    val displayName: String,
    val subtitle: String
) {
    GEMINI_STORYBOARD_REEL(
        modelId = "gemini-4-hydrogen",
        displayName = "Gemini 4 Hydrogen • Reel",
        subtitle = "Director script + 3 AI keyframes"
    )
}

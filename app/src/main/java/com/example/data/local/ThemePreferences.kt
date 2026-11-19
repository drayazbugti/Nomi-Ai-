package com.example.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.ai.ImageModelOption
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "noomi_studio_prefs")

data class PersistedStudioSettings(
    val isDarkTheme: Boolean = true,
    val isLargeGrid: Boolean = false,
    val activeSessionId: String? = null,
    val selectedMode: MediaMode = MediaMode.VIDEO,
    val selectedAspect: AspectOption = AspectOption.LANDSCAPE_16_9,
    val selectedCount: Int = 1,
    val selectedImageModel: ImageModelOption = ImageModelOption.GEMINI_4_HYDROGEN,
    val selectedFilter: AssetFilter = AssetFilter.ALL
)

class ThemePreferences(private val context: Context) {
    private val isDarkThemeKey = booleanPreferencesKey("is_dark_theme")
    private val isLargeGridKey = booleanPreferencesKey("is_large_grid")
    private val activeSessionIdKey = stringPreferencesKey("active_session_id")
    private val selectedModeKey = stringPreferencesKey("selected_mode")
    private val selectedAspectKey = stringPreferencesKey("selected_aspect")
    private val selectedCountKey = intPreferencesKey("selected_count")
    private val selectedImageModelKey = stringPreferencesKey("selected_image_model")
    private val selectedFilterKey = stringPreferencesKey("selected_filter")

    val settings: Flow<PersistedStudioSettings> = context.dataStore.data.map { prefs ->
        PersistedStudioSettings(
            isDarkTheme = prefs[isDarkThemeKey] ?: true,
            isLargeGrid = prefs[isLargeGridKey] ?: false,
            activeSessionId = prefs[activeSessionIdKey],
            selectedMode = prefs[selectedModeKey]?.let {
                runCatching { MediaMode.valueOf(it) }.getOrNull()
            } ?: MediaMode.VIDEO,
            selectedAspect = prefs[selectedAspectKey]?.let {
                runCatching { AspectOption.valueOf(it) }.getOrNull()
            } ?: AspectOption.LANDSCAPE_16_9,
            selectedCount = (prefs[selectedCountKey] ?: 1).coerceIn(1, 4),
            selectedImageModel = prefs[selectedImageModelKey]?.let {
                runCatching { ImageModelOption.valueOf(it) }.getOrNull()
            } ?: ImageModelOption.GEMINI_4_HYDROGEN,
            selectedFilter = prefs[selectedFilterKey]?.let {
                runCatching { AssetFilter.valueOf(it) }.getOrNull()
            } ?: AssetFilter.ALL
        )
    }

    suspend fun getSettingsSnapshot(): PersistedStudioSettings = settings.first()

    suspend fun setDarkTheme(isDark: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[isDarkThemeKey] = isDark
        }
    }

    suspend fun setLargeGrid(isLarge: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[isLargeGridKey] = isLarge
        }
    }

    suspend fun setActiveSessionId(sessionId: String) {
        context.dataStore.edit { prefs ->
            prefs[activeSessionIdKey] = sessionId
        }
    }

    suspend fun setSelectedMode(mode: MediaMode) {
        context.dataStore.edit { prefs ->
            prefs[selectedModeKey] = mode.name
        }
    }

    suspend fun setSelectedAspect(aspect: AspectOption) {
        context.dataStore.edit { prefs ->
            prefs[selectedAspectKey] = aspect.name
        }
    }

    suspend fun setSelectedCount(count: Int) {
        context.dataStore.edit { prefs ->
            prefs[selectedCountKey] = count.coerceIn(1, 4)
        }
    }

    suspend fun setSelectedImageModel(model: ImageModelOption) {
        context.dataStore.edit { prefs ->
            prefs[selectedImageModelKey] = model.name
        }
    }

    suspend fun setSelectedFilter(filter: AssetFilter) {
        context.dataStore.edit { prefs ->
            prefs[selectedFilterKey] = filter.name
        }
    }
}

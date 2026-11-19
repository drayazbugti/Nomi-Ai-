package com.example.ui.studio

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.core.FirebaseSafety
import com.example.core.NetworkConnectivityObserver
import com.example.core.PromptModerationGuard
import com.example.core.SilentPromptBlockedException
import com.example.core.safeCurrentUser
import com.example.data.StudioRepository
import com.example.data.ai.AiCreationRepository
import com.example.data.ai.FirebaseNotConfiguredException
import com.example.data.ai.ImageModelOption
import com.example.data.ai.VideoModelOption
import com.example.data.local.AspectOption
import com.example.data.local.AssetFilter
import com.example.data.local.AssetKind
import com.example.data.local.MediaMode
import com.example.data.local.MediaStorageHelper
import com.example.data.local.NoomiDatabase
import com.example.data.local.PromptHistoryEntity
import com.example.data.local.StoryboardScene
import com.example.data.local.StudioAssetEntity
import com.example.data.local.StudioSessionEntity
import com.example.data.local.ThemePreferences
import com.example.data.remote.FirestoreStudioRepository
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "StudioViewModel"

private val DefaultPresetFolders = listOf(
    "Favorites",
    "Characters",
    "Scenes",
    "Product",
    "Concepts"
)

sealed interface GenerationUiState {
    data object Idle : GenerationUiState
    data class Generating(
        val activeCount: Int,
        val activePrompt: String,
        val mediaMode: MediaMode,
        val aspectOption: AspectOption,
        val progressPercent: Int,
        val statusMessage: String,
        val startedAtMs: Long
    ) : GenerationUiState
    data class Error(val message: String) : GenerationUiState
}

data class StudioUiState(
    val isDarkTheme: Boolean = true,
    val isLargeGrid: Boolean = false,
    val isOnline: Boolean = true,
    val sessions: List<StudioSessionEntity> = emptyList(),
    val activeSession: StudioSessionEntity? = null,
    val assets: List<StudioAssetEntity> = emptyList(),
    val totalSessionAssetCount: Int = 0,
    val trashedAssets: List<StudioAssetEntity> = emptyList(),
    val promptHistory: List<PromptHistoryEntity> = emptyList(),
    val availableFolderTags: List<String> = DefaultPresetFolders,
    val selectedFilter: AssetFilter = AssetFilter.ALL,
    val isSelectionMode: Boolean = false,
    val selectedAssetIds: Set<String> = emptySet(),
    val promptText: String = "",
    val selectedMode: MediaMode = MediaMode.VIDEO,
    val selectedAspect: AspectOption = AspectOption.LANDSCAPE_16_9,
    val selectedCount: Int = 1,
    val selectedImageModel: ImageModelOption = ImageModelOption.GEMINI_4_HYDROGEN,
    val attachedReferencePath: String? = null,
    val isSearchActive: Boolean = false,
    val searchQuery: String = "",
    val isOptionsSheetOpen: Boolean = false,
    val isSessionsSheetOpen: Boolean = false,
    val isHistorySheetOpen: Boolean = false,
    val isTrashSheetOpen: Boolean = false,
    val isRenameDialogOpen: Boolean = false,
    val isDeleteProjectDialogOpen: Boolean = false,
    val isProgressExpanded: Boolean = false,
    val selectedAssetForViewer: StudioAssetEntity? = null,
    val generationState: GenerationUiState = GenerationUiState.Idle,
    val activeGenerationCount: Int = 0
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StudioViewModel(
    application: Application,
    private val currentUserId: String? = null,
    private val firestoreRepository: FirestoreStudioRepository? = null
) : AndroidViewModel(application) {
    private val database = NoomiDatabase.getInstance(application)
    private val mediaStorage = MediaStorageHelper(application)
    private val themePrefs = ThemePreferences(application)
    private val networkObserver = NetworkConnectivityObserver(application)
    private val studioRepository = StudioRepository(
        sessionDao = database.sessionDao(),
        assetDao = database.assetDao(),
        promptHistoryDao = database.promptHistoryDao(),
        mediaStorage = mediaStorage,
        firestoreRepository = firestoreRepository,
        currentUserId = currentUserId
    )
    private val aiRepository = AiCreationRepository(application, mediaStorage)

    private val initialSettings = runBlocking(Dispatchers.IO) {
        themePrefs.getSettingsSnapshot()
    }

    private val _activeSessionId = MutableStateFlow<String?>(initialSettings.activeSessionId)
    private val _isSelectionMode = MutableStateFlow(false)
    private val _selectedAssetIds = MutableStateFlow<Set<String>>(emptySet())
    private val _promptText = MutableStateFlow("")
    private val _attachedReferencePath = MutableStateFlow<String?>(null)
    private val _isSearchActive = MutableStateFlow(false)
    private val _searchQuery = MutableStateFlow("")
    private val _isOptionsSheetOpen = MutableStateFlow(false)
    private val _isSessionsSheetOpen = MutableStateFlow(false)
    private val _isHistorySheetOpen = MutableStateFlow(false)
    private val _isTrashSheetOpen = MutableStateFlow(false)
    private val _isRenameDialogOpen = MutableStateFlow(false)
    private val _isDeleteProjectDialogOpen = MutableStateFlow(false)
    private val _isProgressExpanded = MutableStateFlow(false)
    private val _selectedAssetIdForViewer = MutableStateFlow<String?>(null)

    private val _activeGenerationCount = MutableStateFlow(0)
    private val _activeGenerationPrompt = MutableStateFlow("")
    private val _activeGenerationMode = MutableStateFlow(MediaMode.IMAGE)
    private val _activeGenerationAspect = MutableStateFlow(AspectOption.LANDSCAPE_16_9)
    private val _generationProgressPercent = MutableStateFlow(0)
    private val _generationStatus = MutableStateFlow("")
    private val _generationStartedAtMs = MutableStateFlow(0L)
    private val _lastError = MutableStateFlow<String?>(null)

    private val _snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snackbarEvents = _snackbarEvents.asSharedFlow()

    private val runningJobs = CopyOnWriteArrayList<Job>()

    private val sessionAssetsFlow = _activeSessionId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else studioRepository.observeAssetsForSession(id)
    }

    private val sheetAndDialogFlagsFlow = combine(
        combine(_isOptionsSheetOpen, _isSessionsSheetOpen, _isHistorySheetOpen, _isTrashSheetOpen) { opt, sess, hist, trash ->
            Quad(opt, sess, hist, trash)
        },
        combine(_isRenameDialogOpen, _isDeleteProjectDialogOpen, _isProgressExpanded) { ren, del, exp ->
            Triple(ren, del, exp)
        }
    ) { a, b ->
        SheetFlags(
            isOptionsSheetOpen = a.first,
            isSessionsSheetOpen = a.second,
            isHistorySheetOpen = a.third,
            isTrashSheetOpen = a.fourth,
            isRenameDialogOpen = b.first,
            isDeleteProjectDialogOpen = b.second,
            isProgressExpanded = b.third
        )
    }

    private val generationMetaFlow = combine(
        _activeGenerationPrompt,
        _activeGenerationMode,
        _activeGenerationAspect,
        _generationProgressPercent
    ) { prompt, mode, aspect, percent ->
        Quad(prompt, mode, aspect, percent)
    }

    private val generationStateFlow = combine(
        _activeGenerationCount,
        generationMetaFlow,
        _generationStatus,
        _generationStartedAtMs,
        _lastError
    ) { activeCount, meta, status, startedAt, error ->
        val state = when {
            activeCount > 0 -> GenerationUiState.Generating(
                activeCount = activeCount,
                activePrompt = meta.first,
                mediaMode = meta.second,
                aspectOption = meta.third,
                progressPercent = meta.fourth,
                statusMessage = status.ifBlank {
                    if (activeCount == 1) "Generating media…" else "Processing $activeCount media requests…"
                },
                startedAtMs = startedAt
            )
            error != null -> GenerationUiState.Error(error)
            else -> GenerationUiState.Idle
        }
        activeCount to state
    }

    private val transientControlsFlow = combine(
        combine(_promptText, _attachedReferencePath, _isSearchActive, _searchQuery) { prompt, ref, searchActive, query ->
            Quad(prompt, ref, searchActive, query)
        },
        combine(_selectedAssetIdForViewer, generationStateFlow, _isSelectionMode, _selectedAssetIds) { viewerId, genPair, selMode, selIds ->
            Quad(viewerId, genPair, selMode, selIds)
        },
        combine(sheetAndDialogFlagsFlow, networkObserver.isOnline) { flags, online ->
            flags to online
        }
    ) { searchPart, middlePart, (flags, online) ->
        TransientControls(
            promptText = searchPart.first,
            attachedReferencePath = searchPart.second,
            isSearchActive = searchPart.third,
            searchQuery = searchPart.fourth,
            selectedAssetIdForViewer = middlePart.first,
            activeGenerationCount = middlePart.second.first,
            generationState = middlePart.second.second,
            isSelectionMode = middlePart.third,
            selectedAssetIds = middlePart.fourth,
            sheetFlags = flags,
            isOnline = online
        )
    }

    private val databaseStreamsFlow = combine(
        studioRepository.sessions,
        _activeSessionId,
        sessionAssetsFlow,
        studioRepository.trashedAssets,
        combine(studioRepository.promptHistory, studioRepository.distinctFolderTags) { hist, tags ->
            hist to tags
        }
    ) { sessions, activeId, rawAssets, trashed, (history, customTags) ->
        DbSnapshot(sessions, activeId, rawAssets, trashed, history, customTags)
    }

    val uiState: StateFlow<StudioUiState> = combine(
        themePrefs.settings,
        databaseStreamsFlow,
        transientControlsFlow
    ) { prefs, db, controls ->
        val activeSession = db.sessions.firstOrNull { it.id == db.activeId } ?: db.sessions.firstOrNull()
        val query = controls.searchQuery.trim().lowercase()

        val filteredByType = when (prefs.selectedFilter) {
            AssetFilter.ALL -> db.rawAssets
            AssetFilter.IMAGES -> db.rawAssets.filter {
                it.assetKind == AssetKind.AI_IMAGE || it.assetKind == AssetKind.UPLOADED_IMAGE
            }
            AssetFilter.VIDEOS -> db.rawAssets.filter {
                it.assetKind == AssetKind.AI_STORYBOARD_REEL || it.assetKind == AssetKind.UPLOADED_VIDEO
            }
        }

        val filteredAssets = if (query.isBlank()) {
            filteredByType
        } else {
            filteredByType.filter { asset ->
                asset.prompt.lowercase().contains(query) ||
                    asset.modelLabel.lowercase().contains(query) ||
                    asset.assetKind.name.lowercase().contains(query) ||
                    (asset.folderTag?.lowercase()?.contains(query) == true)
            }
        }

        val validIds = db.rawAssets.map { it.id }.toSet()
        val cleanedSelectedIds = controls.selectedAssetIds.intersect(validIds)
        val viewerAsset = controls.selectedAssetIdForViewer?.let { targetId ->
            db.rawAssets.firstOrNull { it.id == targetId }
        }
        val mergedTags = (DefaultPresetFolders + db.customTags).distinct()

        StudioUiState(
            isDarkTheme = prefs.isDarkTheme,
            isLargeGrid = prefs.isLargeGrid,
            isOnline = controls.isOnline,
            sessions = db.sessions,
            activeSession = activeSession,
            assets = filteredAssets,
            totalSessionAssetCount = db.rawAssets.size,
            trashedAssets = db.trashedAssets,
            promptHistory = db.promptHistory,
            availableFolderTags = mergedTags,
            selectedFilter = prefs.selectedFilter,
            isSelectionMode = controls.isSelectionMode,
            selectedAssetIds = cleanedSelectedIds,
            promptText = controls.promptText,
            selectedMode = prefs.selectedMode,
            selectedAspect = prefs.selectedAspect,
            selectedCount = prefs.selectedCount,
            selectedImageModel = prefs.selectedImageModel,
            attachedReferencePath = controls.attachedReferencePath,
            isSearchActive = controls.isSearchActive,
            searchQuery = controls.searchQuery,
            isOptionsSheetOpen = controls.sheetFlags.isOptionsSheetOpen,
            isSessionsSheetOpen = controls.sheetFlags.isSessionsSheetOpen,
            isHistorySheetOpen = controls.sheetFlags.isHistorySheetOpen,
            isTrashSheetOpen = controls.sheetFlags.isTrashSheetOpen,
            isRenameDialogOpen = controls.sheetFlags.isRenameDialogOpen,
            isDeleteProjectDialogOpen = controls.sheetFlags.isDeleteProjectDialogOpen,
            isProgressExpanded = controls.sheetFlags.isProgressExpanded,
            selectedAssetForViewer = viewerAsset,
            generationState = controls.generationState,
            activeGenerationCount = controls.activeGenerationCount
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = StudioUiState(
            isDarkTheme = initialSettings.isDarkTheme,
            isLargeGrid = initialSettings.isLargeGrid,
            isOnline = networkObserver.isCurrentlyOnline(),
            selectedFilter = initialSettings.selectedFilter,
            selectedMode = initialSettings.selectedMode,
            selectedAspect = initialSettings.selectedAspect,
            selectedCount = initialSettings.selectedCount,
            selectedImageModel = initialSettings.selectedImageModel
        )
    )

    init {
        viewModelScope.launch {
            combine(studioRepository.sessions, themePrefs.settings) { sessions, settings ->
                sessions to settings.activeSessionId
            }.collect { (sessions, savedId) ->
                if (sessions.isEmpty()) {
                    val initial = studioRepository.createSession()
                    themePrefs.setActiveSessionId(initial.id)
                    _activeSessionId.value = initial.id
                } else {
                    val targetId = when {
                        _activeSessionId.value != null && sessions.any { it.id == _activeSessionId.value } ->
                            _activeSessionId.value!!
                        savedId != null && sessions.any { it.id == savedId } -> savedId
                        else -> sessions.first().id
                    }
                    if (_activeSessionId.value != targetId) {
                        _activeSessionId.value = targetId
                    }
                }
            }
        }

        if (!currentUserId.isNullOrBlank() && firestoreRepository != null) {
            viewModelScope.launch {
                safeCurrentUser(getApplication())?.let { user ->
                    firestoreRepository.upsertUserProfile(user)
                }
            }

            viewModelScope.launch {
                firestoreRepository.observeSessions(currentUserId)
                    .catch { error ->
                        val firestoreError = generateSequence(error) { it.cause }
                            .filterIsInstance<FirebaseFirestoreException>()
                            .firstOrNull()
                        if (firestoreError == null && error is CancellationException) throw error
                        Log.w(TAG, "Firestore sessions sync error", firestoreError ?: error)
                    }
                    .collect { remoteSessions ->
                        studioRepository.syncSessionsFromRemote(remoteSessions)
                    }
            }

            viewModelScope.launch {
                firestoreRepository.observeAssets(currentUserId)
                    .catch { error ->
                        val firestoreError = generateSequence(error) { it.cause }
                            .filterIsInstance<FirebaseFirestoreException>()
                            .firstOrNull()
                        if (firestoreError == null && error is CancellationException) throw error
                        Log.w(TAG, "Firestore assets sync error", firestoreError ?: error)
                    }
                    .collect { remoteAssets ->
                        studioRepository.syncAssetsFromRemote(remoteAssets)
                    }
            }

            viewModelScope.launch {
                firestoreRepository.observePromptHistory(currentUserId)
                    .catch { error ->
                        val firestoreError = generateSequence(error) { it.cause }
                            .filterIsInstance<FirebaseFirestoreException>()
                            .firstOrNull()
                        if (firestoreError == null && error is CancellationException) throw error
                        Log.w(TAG, "Firestore prompt history sync error", firestoreError ?: error)
                    }
                    .collect { remoteHistory ->
                        studioRepository.syncPromptHistoryFromRemote(remoteHistory)
                    }
            }
        }
    }

    override fun onCleared() {
        networkObserver.unregister()
        super.onCleared()
    }

    fun toggleTheme() {
        viewModelScope.launch {
            themePrefs.setDarkTheme(!uiState.value.isDarkTheme)
        }
    }

    fun toggleGridSize() {
        viewModelScope.launch {
            themePrefs.setLargeGrid(!uiState.value.isLargeGrid)
        }
    }

    fun selectFilter(filter: AssetFilter) {
        viewModelScope.launch {
            themePrefs.setSelectedFilter(filter)
        }
    }

    fun selectMode(mode: MediaMode) {
        viewModelScope.launch {
            themePrefs.setSelectedMode(mode)
        }
    }

    fun selectAspect(aspect: AspectOption) {
        viewModelScope.launch {
            themePrefs.setSelectedAspect(aspect)
        }
    }

    fun selectCount(count: Int) {
        viewModelScope.launch {
            themePrefs.setSelectedCount(count)
        }
    }

    fun selectImageModel(model: ImageModelOption) {
        viewModelScope.launch {
            themePrefs.setSelectedImageModel(model)
        }
    }

    fun setProgressExpanded(expanded: Boolean) {
        _isProgressExpanded.value = expanded
    }

    fun enterSelectionMode(initialAssetId: String? = null) {
        _isSelectionMode.value = true
        if (initialAssetId != null) {
            _selectedAssetIds.value = setOf(initialAssetId)
        }
    }

    fun toggleAssetSelection(assetId: String) {
        _selectedAssetIds.update { current ->
            if (current.contains(assetId)) current - assetId else current + assetId
        }
    }

    fun selectAllVisibleAssets() {
        val visibleIds = uiState.value.assets.map { it.id }.toSet()
        _selectedAssetIds.update { current ->
            if (visibleIds.isNotEmpty() && current.containsAll(visibleIds)) {
                emptySet()
            } else {
                visibleIds
            }
        }
    }

    fun exitSelectionMode() {
        _isSelectionMode.value = false
        _selectedAssetIds.value = emptySet()
    }

    fun deleteSelectedAssets() {
        val idsToDelete = uiState.value.selectedAssetIds
        if (idsToDelete.isEmpty()) return
        viewModelScope.launch {
            studioRepository.moveAssetsToTrash(idsToDelete)
            exitSelectionMode()
        }
    }

    fun batchExportSelectedAssets() {
        val selectedIds = uiState.value.selectedAssetIds
        val selectedAssets = uiState.value.assets.filter { selectedIds.contains(it.id) }
        if (selectedAssets.isEmpty()) return

        viewModelScope.launch {
            val result = mediaStorage.exportAssetsToGallery(selectedAssets)
            exitSelectionMode()
            val message = when {
                result.exportedCount > 0 && result.failedCount == 0 ->
                    "Exported ${result.exportedCount} item${if (result.exportedCount > 1) "s" else ""} to device gallery"
                result.exportedCount > 0 ->
                    "Exported ${result.exportedCount} items to gallery (${result.failedCount} failed)"
                else ->
                    "Could not export selected items to device gallery"
            }
            _snackbarEvents.tryEmit(message)
        }
    }

    fun exportSingleAsset(asset: StudioAssetEntity) {
        viewModelScope.launch {
            val result = mediaStorage.exportAssetsToGallery(listOf(asset))
            val message = if (result.exportedCount > 0) {
                "Exported successfully to device gallery"
            } else {
                "Could not export asset to device gallery"
            }
            _snackbarEvents.tryEmit(message)
        }
    }

    fun assignFolderTag(assetId: String, folderTag: String?) {
        viewModelScope.launch {
            studioRepository.updateAssetFolderTag(assetId, folderTag)
        }
    }

    fun onPromptChanged(newPrompt: String) {
        _promptText.value = newPrompt
        if (_lastError.value != null) {
            _lastError.value = null
        }
    }

    fun setOptionsSheetOpen(open: Boolean) {
        _isOptionsSheetOpen.value = open
    }

    fun setSessionsSheetOpen(open: Boolean) {
        _isSessionsSheetOpen.value = open
    }

    fun setHistorySheetOpen(open: Boolean) {
        _isHistorySheetOpen.value = open
    }

    fun setTrashSheetOpen(open: Boolean) {
        _isTrashSheetOpen.value = open
    }

    fun setRenameDialogOpen(open: Boolean) {
        _isRenameDialogOpen.value = open
    }

    fun setDeleteProjectDialogOpen(open: Boolean) {
        _isDeleteProjectDialogOpen.value = open
    }

    fun toggleSearch(active: Boolean) {
        _isSearchActive.value = active
        if (!active) {
            _searchQuery.value = ""
        }
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun openAssetViewer(asset: StudioAssetEntity?) {
        _selectedAssetIdForViewer.value = asset?.id
    }

    fun attachReferenceImage(filePath: String?) {
        _attachedReferencePath.value = filePath
    }

    fun reuseHistoryPrompt(entry: PromptHistoryEntity) {
        _promptText.value = entry.promptText
        viewModelScope.launch {
            runCatching { MediaMode.valueOf(entry.mediaMode) }.getOrNull()?.let {
                themePrefs.setSelectedMode(it)
            }
            AspectOption.entries.firstOrNull { it.label == entry.aspectRatio }?.let {
                themePrefs.setSelectedAspect(it)
            }
        }
        _isHistorySheetOpen.value = false
    }

    fun deleteHistoryEntry(id: String) {
        viewModelScope.launch {
            studioRepository.deleteHistoryItem(id)
        }
    }

    fun clearPromptHistory() {
        viewModelScope.launch {
            studioRepository.clearPromptHistory()
        }
    }

    fun remixAssetWithAi(asset: StudioAssetEntity) {
        val targetPath = when (asset.assetKind) {
            AssetKind.AI_IMAGE, AssetKind.UPLOADED_IMAGE -> asset.primaryFilePath
            AssetKind.AI_STORYBOARD_REEL ->
                asset.parseStoryboardScenes().firstOrNull()?.imageFilePath ?: asset.primaryFilePath
            AssetKind.UPLOADED_VIDEO -> null
        }
        if (targetPath != null) {
            _attachedReferencePath.value = targetPath
            selectMode(MediaMode.IMAGE)
        }
        _selectedAssetIdForViewer.value = null
    }

    fun createNewSession() {
        viewModelScope.launch {
            val session = studioRepository.createSession()
            themePrefs.setActiveSessionId(session.id)
            _activeSessionId.value = session.id
            _isSessionsSheetOpen.value = false
            _attachedReferencePath.value = null
            _promptText.value = ""
            exitSelectionMode()
        }
    }

    fun switchToSession(sessionId: String) {
        viewModelScope.launch {
            themePrefs.setActiveSessionId(sessionId)
            _activeSessionId.value = sessionId
            _isSessionsSheetOpen.value = false
            exitSelectionMode()
        }
    }

    fun renameActiveSession(newTitle: String) {
        val activeId = _activeSessionId.value ?: return
        viewModelScope.launch {
            studioRepository.renameSession(activeId, newTitle)
            _isRenameDialogOpen.value = false
        }
    }

    fun deleteActiveProject() {
        val active = uiState.value.activeSession ?: return
        viewModelScope.launch {
            studioRepository.deleteSession(active)
            _isDeleteProjectDialogOpen.value = false
            _attachedReferencePath.value = null
            _promptText.value = ""
            exitSelectionMode()
        }
    }

    fun deleteSession(session: StudioSessionEntity) {
        viewModelScope.launch {
            studioRepository.deleteSession(session)
        }
    }

    fun moveAssetToTrash(asset: StudioAssetEntity) {
        viewModelScope.launch {
            if (_selectedAssetIdForViewer.value == asset.id) {
                _selectedAssetIdForViewer.value = null
            }
            studioRepository.moveAssetToTrash(asset)
        }
    }

    fun restoreAssetFromTrash(asset: StudioAssetEntity) {
        viewModelScope.launch {
            studioRepository.restoreAssetFromTrash(asset)
        }
    }

    fun permanentlyDeleteAsset(asset: StudioAssetEntity) {
        viewModelScope.launch {
            studioRepository.permanentlyDeleteAsset(asset)
        }
    }

    fun emptyTrash() {
        val currentTrashed = uiState.value.trashedAssets
        viewModelScope.launch {
            studioRepository.emptyTrash(currentTrashed)
        }
    }

    fun shareAsset(asset: StudioAssetEntity) {
        val fallbackMime = if (asset.assetKind == AssetKind.UPLOADED_VIDEO) "video/mp4" else "image/png"
        val path = if (asset.assetKind == AssetKind.AI_STORYBOARD_REEL) {
            asset.parseStoryboardScenes().firstOrNull()?.imageFilePath ?: asset.primaryFilePath
        } else {
            asset.primaryFilePath
        }
        val launched = mediaStorage.shareFile(path, fallbackMime, asset.prompt)
        if (launched) {
            _snackbarEvents.tryEmit("Asset shared via system share sheet")
        } else {
            _snackbarEvents.tryEmit("Could not share asset via intent")
        }
    }

    fun importPickedMedia(uri: Uri) {
        val sessionId = _activeSessionId.value ?: return
        viewModelScope.launch {
            try {
                val imported = mediaStorage.importPickedUri(uri)
                if (imported == null) {
                    _lastError.value = "Could not read selected media."
                    return@launch
                }
                val asset = StudioAssetEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    kind = if (imported.isVideo) AssetKind.UPLOADED_VIDEO.name else AssetKind.UPLOADED_IMAGE.name,
                    prompt = if (imported.isVideo) "Uploaded video clip" else "Uploaded reference image",
                    modelLabel = "Gallery Upload",
                    aspectRatio = "Original",
                    primaryFilePath = imported.filePath,
                    storyboardJson = imported.thumbnailPath,
                    createdAt = System.currentTimeMillis()
                )
                studioRepository.saveAsset(asset)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed importing media", e)
                _lastError.value = e.localizedMessage ?: "Failed to import media."
            }
        }
    }

    fun dismissError() {
        _lastError.value = null
    }

    fun cancelGeneration() {
        runningJobs.forEach { it.cancel() }
        runningJobs.clear()
        _activeGenerationCount.value = 0
        _generationProgressPercent.value = 0
        _generationStatus.value = ""
        _generationStartedAtMs.value = 0L
        _isProgressExpanded.value = false
    }

    fun submitGeneration() {
        val prompt = _promptText.value.trim()
        if (prompt.isEmpty()) return

        // Silent blocking for restricted, XXX, or sexually explicit prompts:
        // Do not show any pre-warning or refusal message and do not generate media.
        if (PromptModerationGuard.isRestrictedPrompt(prompt)) {
            _promptText.value = ""
            _attachedReferencePath.value = null
            _lastError.value = null
            return
        }

        if (!networkObserver.isCurrentlyOnline()) {
            return
        }
        val sessionId = _activeSessionId.value ?: return
        val snapshot = uiState.value
        val mode = snapshot.selectedMode
        val aspect = snapshot.selectedAspect
        val count = snapshot.selectedCount
        val imageModel = snapshot.selectedImageModel
        val referencePath = _attachedReferencePath.value
        val active = snapshot.activeSession
        val sessionTitle = active?.title ?: "Studio Project"
        val wasSessionEmpty = snapshot.totalSessionAssetCount == 0

        _promptText.value = ""
        _attachedReferencePath.value = null
        _lastError.value = null

        lateinit var launchedJob: Job
        launchedJob = viewModelScope.launch {
            if (_activeGenerationCount.value == 0) {
                _generationStartedAtMs.value = System.currentTimeMillis()
                _generationProgressPercent.value = 4
            }
            _activeGenerationPrompt.value = prompt
            _activeGenerationMode.value = mode
            _activeGenerationAspect.value = aspect
            _activeGenerationCount.update { it + 1 }

            val progressCeiling = MutableStateFlow(if (mode == MediaMode.IMAGE) 92 else 28)
            val tickerJob = launch {
                while (isActive) {
                    delay(180L)
                    val current = _generationProgressPercent.value
                    val ceiling = progressCeiling.value
                    if (current < ceiling) {
                        val step = when {
                            ceiling - current > 20 -> 3
                            ceiling - current > 8 -> 2
                            else -> 1
                        }
                        _generationProgressPercent.value = (current + step).coerceAtMost(ceiling)
                    }
                }
            }

            var lastCreatedAsset: StudioAssetEntity? = null
            var recordedMeta = false
            try {
                val modelLabelForHistory = if (mode == MediaMode.IMAGE) {
                    imageModel.displayName
                } else {
                    VideoModelOption.GEMINI_STORYBOARD_REEL.displayName
                }

                val referenceBitmap = referencePath?.let { mediaStorage.loadScaledBitmap(it) }

                if (mode == MediaMode.IMAGE) {
                    for (idx in 0 until count) {
                        val itemStartMs = System.currentTimeMillis()
                        val itemBase = ((idx.toFloat() / count) * 90f).toInt().coerceAtLeast(4)
                        val itemCeiling = (((idx + 1f) / count) * 94f).toInt().coerceIn(itemBase + 5, 95)
                        if (_generationProgressPercent.value < itemBase) {
                            _generationProgressPercent.value = itemBase
                        }
                        progressCeiling.value = itemCeiling

                        _generationStatus.value = if (count > 1) {
                            "Generating image ${idx + 1}/$count • ${imageModel.displayName}…"
                        } else {
                            "Generating image • ${imageModel.displayName}…"
                        }
                        val filePath = aiRepository.generateImageAsset(
                            prompt = prompt,
                            modelOption = imageModel,
                            aspect = aspect,
                            referenceBitmap = referenceBitmap,
                            variationIndex = idx
                        )
                        val durationMs = (System.currentTimeMillis() - itemStartMs).coerceAtLeast(100L)
                        val asset = StudioAssetEntity(
                            id = UUID.randomUUID().toString(),
                            sessionId = sessionId,
                            kind = AssetKind.AI_IMAGE.name,
                            prompt = prompt,
                            modelLabel = imageModel.displayName,
                            aspectRatio = aspect.label,
                            primaryFilePath = filePath,
                            storyboardJson = null,
                            createdAt = System.currentTimeMillis(),
                            generationDurationMs = durationMs
                        )
                        studioRepository.saveAsset(asset, sessionTitle = sessionTitle)
                        if (!recordedMeta) {
                            recordedMeta = true
                            studioRepository.recordPromptHistory(
                                promptText = prompt,
                                mediaMode = mode.name,
                                aspectRatio = aspect.label,
                                modelLabel = modelLabelForHistory
                            )
                            if (active != null && wasSessionEmpty) {
                                val conciseTitle = prompt.take(28).let { if (prompt.length > 28) "$it…" else it }
                                studioRepository.renameSession(active.id, conciseTitle)
                            }
                        }
                        lastCreatedAsset = asset
                        _generationProgressPercent.value = itemCeiling
                    }
                } else {
                    for (idx in 0 until count) {
                        val itemStartMs = System.currentTimeMillis()
                        val takeStart = ((idx.toFloat() / count) * 90f).toInt().coerceAtLeast(4)
                        val takeSpan = (94f / count)
                        if (_generationProgressPercent.value < takeStart) {
                            _generationProgressPercent.value = takeStart
                        }
                        progressCeiling.value = (takeStart + takeSpan * 0.25f).toInt().coerceAtMost(94)

                        val reelPrompt = if (count > 1) "$prompt (Take ${idx + 1})" else prompt
                        val scenes = aiRepository.generateStoryboardReel(
                            prompt = reelPrompt,
                            aspect = aspect,
                            preferredModel = imageModel,
                            referenceBitmap = referenceBitmap,
                            onSceneProgress = { sceneIndex, totalScenes, status ->
                                _generationStatus.value = if (count > 1) {
                                    "Reel ${idx + 1}/$count • $status"
                                } else {
                                    status
                                }
                                val stageFraction = ((sceneIndex + 1f) / (totalScenes + 1f)).coerceIn(0.25f, 0.98f)
                                val milestone = (takeStart + takeSpan * stageFraction).toInt().coerceIn(5, 95)
                                progressCeiling.value = milestone
                            }
                        )
                        val durationMs = (System.currentTimeMillis() - itemStartMs).coerceAtLeast(100L)
                        val coverPath = scenes.first().imageFilePath
                        val asset = StudioAssetEntity(
                            id = UUID.randomUUID().toString(),
                            sessionId = sessionId,
                            kind = AssetKind.AI_STORYBOARD_REEL.name,
                            prompt = prompt,
                            modelLabel = "${imageModel.displayName} • Reel",
                            aspectRatio = aspect.label,
                            primaryFilePath = coverPath,
                            storyboardJson = StoryboardScene.toJsonString(scenes),
                            createdAt = System.currentTimeMillis(),
                            generationDurationMs = durationMs
                        )
                        studioRepository.saveAsset(asset, sessionTitle = sessionTitle)
                        if (!recordedMeta) {
                            recordedMeta = true
                            studioRepository.recordPromptHistory(
                                promptText = prompt,
                                mediaMode = mode.name,
                                aspectRatio = aspect.label,
                                modelLabel = modelLabelForHistory
                            )
                            if (active != null && wasSessionEmpty) {
                                val conciseTitle = prompt.take(28).let { if (prompt.length > 28) "$it…" else it }
                                studioRepository.renameSession(active.id, conciseTitle)
                            }
                        }
                        lastCreatedAsset = asset
                        _generationProgressPercent.value = (takeStart + takeSpan).toInt().coerceAtMost(95)
                    }
                }

                tickerJob.cancel()
                _generationProgressPercent.value = 100
                _generationStatus.value = "Complete"
                delay(420L)

                if (_isProgressExpanded.value && lastCreatedAsset != null) {
                    _isProgressExpanded.value = false
                    _selectedAssetIdForViewer.value = lastCreatedAsset.id
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: SilentPromptBlockedException) {
                _isProgressExpanded.value = false
                _lastError.value = null
            } catch (e: FirebaseNotConfiguredException) {
                Log.w(TAG, "Firebase unavailable in this environment; skipping error banner.", e)
                _isProgressExpanded.value = false
                _lastError.value = null
                _snackbarEvents.tryEmit("AI service unavailable: Firebase configuration missing in this build.")
            } catch (e: Exception) {
                if (PromptModerationGuard.isSafetyBlockException(e)) {
                    _isProgressExpanded.value = false
                    _lastError.value = null
                } else {
                    Log.e(TAG, "Generation failed", e)
                    _isProgressExpanded.value = false
                    if (FirebaseSafety.isFirebaseNotInitializedError(e)) {
                        _lastError.value = null
                        _snackbarEvents.tryEmit("AI service unavailable: Firebase configuration missing in this build.")
                    } else {
                        _lastError.value = e.localizedMessage
                            ?: "Generation failed. Please check your connection and try again."
                    }
                }
            } finally {
                tickerJob.cancel()
                runningJobs.remove(launchedJob)
                _activeGenerationCount.update { (it - 1).coerceAtLeast(0) }
                if (_activeGenerationCount.value == 0) {
                    _generationProgressPercent.value = 0
                    _generationStatus.value = ""
                    _generationStartedAtMs.value = 0L
                    _isProgressExpanded.value = false
                }
            }
        }
        runningJobs.add(launchedJob)
    }

    private data class Quad<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )

    private data class DbSnapshot(
        val sessions: List<StudioSessionEntity>,
        val activeId: String?,
        val rawAssets: List<StudioAssetEntity>,
        val trashedAssets: List<StudioAssetEntity>,
        val promptHistory: List<PromptHistoryEntity>,
        val customTags: List<String>
    )

    private data class SheetFlags(
        val isOptionsSheetOpen: Boolean,
        val isSessionsSheetOpen: Boolean,
        val isHistorySheetOpen: Boolean,
        val isTrashSheetOpen: Boolean,
        val isRenameDialogOpen: Boolean,
        val isDeleteProjectDialogOpen: Boolean,
        val isProgressExpanded: Boolean
    )

    private data class TransientControls(
        val promptText: String,
        val attachedReferencePath: String?,
        val isSearchActive: Boolean,
        val searchQuery: String,
        val selectedAssetIdForViewer: String?,
        val activeGenerationCount: Int,
        val generationState: GenerationUiState,
        val isSelectionMode: Boolean,
        val selectedAssetIds: Set<String>,
        val sheetFlags: SheetFlags,
        val isOnline: Boolean
    )

    companion object {
        fun provideFactory(
            application: Application,
            currentUserId: String?
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val firestoreRepo = if (!currentUserId.isNullOrBlank() && FirebaseSafety.isInitialized(application)) {
                    runCatching {
                        val dbId = application.getString(R.string.firestore_database_id)
                        val db = FirebaseFirestore.getInstance(dbId)
                        FirestoreStudioRepository(db)
                    }.getOrNull()
                } else {
                    null
                }
                return StudioViewModel(
                    application = application,
                    currentUserId = currentUserId,
                    firestoreRepository = firestoreRepo
                ) as T
            }
        }
    }
}

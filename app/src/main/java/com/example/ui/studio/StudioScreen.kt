package com.example.ui.studio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.studio.components.AssetViewerDialog
import com.example.ui.studio.components.CommandBar
import com.example.ui.studio.components.DeleteProjectDialog
import com.example.ui.studio.components.ExpandedGenerationProgressDialog
import com.example.ui.studio.components.HistorySheet
import com.example.ui.studio.components.OfflineConnectionPopup
import com.example.ui.studio.components.RenameProjectDialog
import com.example.ui.studio.components.SessionsSheet
import com.example.ui.studio.components.StudioCanvas
import com.example.ui.studio.components.StudioHeader
import com.example.ui.studio.components.StudioOptionsSheet
import com.example.ui.studio.components.TrashSheet

@Composable
fun StudioScreen(
    uiState: StudioUiState,
    viewModel: StudioViewModel,
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var lastQueuedSnackbarMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.snackbarEvents.collect { message ->
            lastQueuedSnackbarMessage = message
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short
            )
        }
    }

    val photoVideoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            if (uri != null) {
                viewModel.importPickedMedia(uri)
            }
        }
    )

    val launchGalleryPicker: () -> Unit = {
        photoVideoPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = {
                if (uiState.selectedAssetForViewer == null) {
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag("studio_snackbar_host")
                    ) { data ->
                        Snackbar(
                            snackbarData = data,
                            shape = RoundedCornerShape(16.dp),
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        ) { _ ->
            Column(modifier = Modifier.fillMaxSize()) {
                StudioHeader(
                    sessionTitle = uiState.activeSession?.title ?: stringResource(R.string.app_name),
                    isDarkTheme = uiState.isDarkTheme,
                    isLargeGrid = uiState.isLargeGrid,
                    trashedCount = uiState.trashedAssets.size,
                    isSearchActive = uiState.isSearchActive,
                    searchQuery = uiState.searchQuery,
                    onSessionsClick = { viewModel.setSessionsSheetOpen(true) },
                    onHistoryClick = { viewModel.setHistorySheetOpen(true) },
                    onSearchToggle = viewModel::toggleSearch,
                    onSearchQueryChange = viewModel::onSearchQueryChanged,
                    onRenameProject = { viewModel.setRenameDialogOpen(true) },
                    onToggleGridSize = viewModel::toggleGridSize,
                    onViewTrash = { viewModel.setTrashSheetOpen(true) },
                    onDeleteProject = { viewModel.setDeleteProjectDialogOpen(true) },
                    onToggleTheme = viewModel::toggleTheme,
                    onNewSession = viewModel::createNewSession
                )

                StudioCanvas(
                    assets = uiState.assets,
                    totalSessionAssetCount = uiState.totalSessionAssetCount,
                    selectedFilter = uiState.selectedFilter,
                    isSelectionMode = uiState.isSelectionMode,
                    selectedAssetIds = uiState.selectedAssetIds,
                    isLargeGrid = uiState.isLargeGrid,
                    generationState = uiState.generationState,
                    searchQuery = uiState.searchQuery,
                    onSelectFilter = viewModel::selectFilter,
                    onEnterSelectionMode = viewModel::enterSelectionMode,
                    onToggleAssetSelection = viewModel::toggleAssetSelection,
                    onSelectAllVisible = viewModel::selectAllVisibleAssets,
                    onExitSelectionMode = viewModel::exitSelectionMode,
                    onBatchExportSelected = viewModel::batchExportSelectedAssets,
                    onDeleteSelected = viewModel::deleteSelectedAssets,
                    onUploadClick = launchGalleryPicker,
                    onAssetClick = viewModel::openAssetViewer,
                    onShareAsset = viewModel::shareAsset,
                    onTrashAsset = viewModel::moveAssetToTrash,
                    onExpandProgress = { viewModel.setProgressExpanded(true) },
                    onCancelGeneration = viewModel::cancelGeneration,
                    onDismissError = viewModel::dismissError,
                    modifier = Modifier.weight(1f)
                )

                CommandBar(
                    promptText = uiState.promptText,
                    selectedMode = uiState.selectedMode,
                    selectedAspect = uiState.selectedAspect,
                    selectedCount = uiState.selectedCount,
                    attachedReferencePath = uiState.attachedReferencePath,
                    activeGenerationCount = uiState.activeGenerationCount,
                    onPromptChange = viewModel::onPromptChanged,
                    onOpenOptions = { viewModel.setOptionsSheetOpen(true) },
                    onRemoveReference = { viewModel.attachReferenceImage(null) },
                    onSubmit = viewModel::submitGeneration
                )
            }
        }

        if (uiState.selectedAssetForViewer == null && !uiState.isProgressExpanded) {
            OfflineConnectionPopup(
                visible = !uiState.isOnline,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }

    if (uiState.isProgressExpanded && uiState.generationState is GenerationUiState.Generating) {
        ExpandedGenerationProgressDialog(
            state = uiState.generationState,
            isOnline = uiState.isOnline,
            onCollapse = { viewModel.setProgressExpanded(false) },
            onCancel = viewModel::cancelGeneration
        )
    }

    if (uiState.isOptionsSheetOpen) {
        StudioOptionsSheet(
            selectedMode = uiState.selectedMode,
            selectedAspect = uiState.selectedAspect,
            selectedCount = uiState.selectedCount,
            selectedImageModel = uiState.selectedImageModel,
            onSelectMode = viewModel::selectMode,
            onSelectAspect = viewModel::selectAspect,
            onSelectCount = viewModel::selectCount,
            onSelectImageModel = viewModel::selectImageModel,
            onUploadFromGallery = launchGalleryPicker,
            onDismiss = { viewModel.setOptionsSheetOpen(false) }
        )
    }

    if (uiState.isHistorySheetOpen) {
        HistorySheet(
            history = uiState.promptHistory,
            onReusePrompt = viewModel::reuseHistoryPrompt,
            onDeleteHistoryItem = viewModel::deleteHistoryEntry,
            onClearHistory = viewModel::clearPromptHistory,
            onDismiss = { viewModel.setHistorySheetOpen(false) }
        )
    }

    if (uiState.isTrashSheetOpen) {
        TrashSheet(
            trashedAssets = uiState.trashedAssets,
            onRestoreAsset = viewModel::restoreAssetFromTrash,
            onDeletePermanently = viewModel::permanentlyDeleteAsset,
            onEmptyTrash = viewModel::emptyTrash,
            onDismiss = { viewModel.setTrashSheetOpen(false) }
        )
    }

    if (uiState.isSessionsSheetOpen) {
        SessionsSheet(
            sessions = uiState.sessions,
            activeSessionId = uiState.activeSession?.id,
            onSelectSession = viewModel::switchToSession,
            onNewSession = viewModel::createNewSession,
            onDeleteSession = viewModel::deleteSession,
            onDismiss = { viewModel.setSessionsSheetOpen(false) }
        )
    }

    if (uiState.isRenameDialogOpen) {
        RenameProjectDialog(
            currentTitle = uiState.activeSession?.title ?: "",
            onConfirm = viewModel::renameActiveSession,
            onDismiss = { viewModel.setRenameDialogOpen(false) }
        )
    }

    if (uiState.isDeleteProjectDialogOpen) {
        DeleteProjectDialog(
            projectTitle = uiState.activeSession?.title ?: "this project",
            onConfirm = viewModel::deleteActiveProject,
            onDismiss = { viewModel.setDeleteProjectDialogOpen(false) }
        )
    }

    uiState.selectedAssetForViewer?.let { asset ->
        AssetViewerDialog(
            asset = asset,
            availableFolderTags = uiState.availableFolderTags,
            isOnline = uiState.isOnline,
            snackbarHostState = snackbarHostState,
            onAssignFolderTag = viewModel::assignFolderTag,
            onDismiss = { viewModel.openAssetViewer(null) },
            onRemix = viewModel::remixAssetWithAi,
            onExport = viewModel::exportSingleAsset,
            onShare = viewModel::shareAsset,
            onDelete = viewModel::moveAssetToTrash
        )
    }
}

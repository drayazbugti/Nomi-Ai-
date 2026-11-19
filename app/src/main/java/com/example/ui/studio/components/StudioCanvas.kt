package com.example.ui.studio.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.local.AssetFilter
import com.example.data.local.AssetKind
import com.example.data.local.MediaMode
import com.example.data.local.StudioAssetEntity
import com.example.ui.studio.GenerationUiState
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun StudioCanvas(
    assets: List<StudioAssetEntity>,
    totalSessionAssetCount: Int,
    selectedFilter: AssetFilter,
    isSelectionMode: Boolean,
    selectedAssetIds: Set<String>,
    isLargeGrid: Boolean,
    generationState: GenerationUiState,
    searchQuery: String,
    onSelectFilter: (AssetFilter) -> Unit,
    onEnterSelectionMode: (String?) -> Unit,
    onToggleAssetSelection: (String) -> Unit,
    onSelectAllVisible: () -> Unit,
    onExitSelectionMode: () -> Unit,
    onBatchExportSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
    onUploadClick: () -> Unit,
    onAssetClick: (StudioAssetEntity) -> Unit,
    onShareAsset: (StudioAssetEntity) -> Unit,
    onTrashAsset: (StudioAssetEntity) -> Unit,
    onExpandProgress: () -> Unit,
    onCancelGeneration: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isGenerating = generationState is GenerationUiState.Generating

    if (isSelectionMode) {
        BackHandler {
            onExitSelectionMode()
        }
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize()
    ) {
        val columnCount = if (isLargeGrid) {
            if (maxWidth >= 900.dp) 2 else 1
        } else {
            when {
                maxWidth >= 900.dp -> 4
                maxWidth >= 600.dp -> 3
                else -> 2
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {

            AnimatedVisibility(visible = generationState is GenerationUiState.Error) {
                val errorMessage = (generationState as? GenerationUiState.Error)?.message.orEmpty()
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = onDismissError,
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Dismiss error",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            if (isSelectionMode) {
                MultiSelectActionBar(
                    selectedCount = selectedAssetIds.size,
                    totalVisibleCount = assets.size,
                    onExitSelection = onExitSelectionMode,
                    onSelectAll = onSelectAllVisible,
                    onBatchExport = onBatchExportSelected,
                    onDeleteSelected = onDeleteSelected
                )
            } else if (totalSessionAssetCount > 0 || isGenerating) {
                GalleryFilterBar(
                    selectedFilter = selectedFilter,
                    hasVisibleAssets = assets.isNotEmpty(),
                    onSelectFilter = onSelectFilter,
                    onEnterSelectionMode = { onEnterSelectionMode(null) }
                )
            }

            if (totalSessionAssetCount == 0 && !isGenerating) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    OutlinedButton(
                        onClick = onUploadClick,
                        shape = CircleShape,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("upload_main_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FileUpload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.upload_button_label),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            } else if (assets.isEmpty() && !isGenerating) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = if (searchQuery.isNotBlank()) {
                                "No matching ${selectedFilter.label.lowercase()}"
                            } else {
                                "No ${selectedFilter.label.lowercase()} yet"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        if (selectedFilter != AssetFilter.ALL) {
                            OutlinedButton(
                                onClick = { onSelectFilter(AssetFilter.ALL) },
                                shape = CircleShape,
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Text("Show all media")
                            }
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columnCount),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (generationState is GenerationUiState.Generating) {
                        item(span = { GridItemSpan(columnCount) }) {
                            GeneratingProgressCard(
                                state = generationState,
                                onExpand = onExpandProgress,
                                onCancel = onCancelGeneration
                            )
                        }
                    }

                    items(items = assets, key = { it.id }) { asset ->
                        AssetGridCard(
                            asset = asset,
                            isLarge = isLargeGrid,
                            isSelectionMode = isSelectionMode,
                            isSelected = selectedAssetIds.contains(asset.id),
                            onClick = {
                                if (isSelectionMode) {
                                    onToggleAssetSelection(asset.id)
                                } else {
                                    onAssetClick(asset)
                                }
                            },
                            onLongClick = {
                                if (!isSelectionMode) {
                                    onEnterSelectionMode(asset.id)
                                } else {
                                    onToggleAssetSelection(asset.id)
                                }
                            },
                            onShare = { onShareAsset(asset) },
                            onMoveToTrash = { onTrashAsset(asset) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryFilterBar(
    selectedFilter: AssetFilter,
    hasVisibleAssets: Boolean,
    onSelectFilter: (AssetFilter) -> Unit,
    onEnterSelectionMode: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState())
        ) {
            AssetFilter.entries.forEach { filter ->
                val selected = filter == selectedFilter
                FilterChip(
                    selected = selected,
                    onClick = { onSelectFilter(filter) },
                    label = {
                        Text(
                            text = filter.label,
                            style = MaterialTheme.typography.labelLarge
                        )
                    },
                    leadingIcon = when (filter) {
                        AssetFilter.ALL -> null
                        AssetFilter.IMAGES -> {
                            {
                                Icon(
                                    imageVector = Icons.Rounded.Image,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        AssetFilter.VIDEOS -> {
                            {
                                Icon(
                                    imageVector = Icons.Rounded.Videocam,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    },
                    shape = CircleShape,
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selected,
                        borderColor = MaterialTheme.colorScheme.outline,
                        selectedBorderColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("filter_chip_${filter.name.lowercase()}")
                )
            }
        }

        if (hasVisibleAssets) {
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onEnterSelectionMode)
                    .minimumInteractiveComponentSize()
                    .testTag("enter_select_mode_button")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Checklist,
                        contentDescription = "Select multiple items",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Select",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun MultiSelectActionBar(
    selectedCount: Int,
    totalVisibleCount: Int,
    onExitSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onBatchExport: () -> Unit,
    onDeleteSelected: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("multi_select_bar")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onExitSelection,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("exit_select_mode_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Exit selection mode",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = "$selectedCount selected",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                TextButton(
                    onClick = onSelectAll,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("select_all_button")
                ) {
                    Text(
                        text = if (totalVisibleCount > 0 && selectedCount == totalVisibleCount) {
                            "Clear"
                        } else {
                            "Select all"
                        },
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                OutlinedButton(
                    onClick = onBatchExport,
                    enabled = selectedCount > 0,
                    shape = CircleShape,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("batch_export_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Batch Export ($selectedCount)",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Button(
                    onClick = onDeleteSelected,
                    enabled = selectedCount > 0,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("delete_selected_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Delete ($selectedCount)",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun WhiteMistSmokeOverlay(
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "white_mist_smoke")
    val phaseA by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "mist_phase_a"
    )
    val phaseB by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 10400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "mist_phase_b"
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        // Soft drifting white smoke/mist puffs layered across the frame
        val puffs = listOf(
            Triple(0.18f, 0.68f, 0.46f),
            Triple(0.45f, 0.42f, 0.52f),
            Triple(0.75f, 0.62f, 0.48f),
            Triple(0.30f, 0.28f, 0.40f),
            Triple(0.62f, 0.26f, 0.44f),
            Triple(0.86f, 0.36f, 0.38f),
            Triple(0.50f, 0.78f, 0.54f)
        )

        puffs.forEachIndexed { index, (baseX, baseY, radiusFactor) ->
            val angleA = (phaseA * 2f * PI + index * 0.95).toFloat()
            val angleB = (phaseB * 2f * PI + index * 1.4).toFloat()
            val driftX = cos(angleA) * w * 0.14f + sin(angleB * 0.5f) * w * 0.08f
            val driftY = sin(angleB) * h * 0.16f - ((phaseA + index * 0.15f) % 1f - 0.5f) * h * 0.12f

            val center = Offset(
                x = baseX * w + driftX,
                y = baseY * h + driftY
            )
            val radius = maxOf(w, h) * radiusFactor
            val peakAlpha = 0.08f + 0.06f * ((sin(angleA + angleB) + 1f) * 0.5f)

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = peakAlpha),
                        Color.White.copy(alpha = peakAlpha * 0.45f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = radius
                ),
                radius = radius,
                center = center
            )
        }
    }
}

@Composable
private fun GeneratingProgressCard(
    state: GenerationUiState.Generating,
    onExpand: () -> Unit,
    onCancel: () -> Unit
) {
    var elapsedMs by remember(state.startedAtMs) {
        mutableLongStateOf(
            if (state.startedAtMs > 0L) (System.currentTimeMillis() - state.startedAtMs).coerceAtLeast(0L) else 0L
        )
    }

    LaunchedEffect(state.startedAtMs) {
        while (state.startedAtMs > 0L) {
            elapsedMs = (System.currentTimeMillis() - state.startedAtMs).coerceAtLeast(0L)
            delay(100L)
        }
    }

    val animatedPercent by animateIntAsState(
        targetValue = state.progressPercent.coerceIn(0, 100),
        animationSpec = tween(durationMillis = 220),
        label = "progress_card_percent"
    )

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF141414),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onExpand)
            .testTag("generation_progress_card")
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(196.dp)
        ) {
            WhiteMistSmokeOverlay(modifier = Modifier.fillMaxSize())

            Column(
                verticalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.10f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Icon(
                                imageVector = if (state.mediaMode == MediaMode.IMAGE) {
                                    Icons.Rounded.Image
                                } else {
                                    Icons.Rounded.Videocam
                                },
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = if (state.mediaMode == MediaMode.IMAGE) "Image" else "Video Reel",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White
                            )
                            Text(
                                text = "• ${formatDurationSeconds(elapsedMs)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.72f)
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color.White.copy(alpha = 0.10f)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.OpenInFull,
                                    contentDescription = "Expand progress view",
                                    tint = Color.White.copy(alpha = 0.85f),
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = "Expand",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.85f)
                                )
                            }
                        }

                        TextButton(
                            onClick = onCancel,
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Text(
                                text = "Cancel",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.78f)
                            )
                        }
                    }
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "$animatedPercent%",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.testTag("generation_progress_percent")
                    )
                    LinearProgressIndicator(
                        progress = { animatedPercent / 100f },
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.18f),
                        modifier = Modifier
                            .fillMaxWidth(0.72f)
                            .height(4.dp)
                            .clip(CircleShape)
                    )
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = state.statusMessage,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.88f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (state.activePrompt.isNotBlank()) {
                        Text(
                            text = state.activePrompt,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.62f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssetGridCard(
    asset: StudioAssetEntity,
    isLarge: Boolean,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onShare: () -> Unit,
    onMoveToTrash: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val kind = asset.assetKind
    val previewPath = when (kind) {
        AssetKind.UPLOADED_VIDEO -> asset.storyboardJson ?: asset.primaryFilePath
        AssetKind.AI_STORYBOARD_REEL ->
            asset.parseStoryboardScenes().firstOrNull()?.imageFilePath ?: asset.primaryFilePath
        else -> asset.primaryFilePath
    }

    val previewRatio = when {
        isLarge && asset.aspectRatio == "16:9" -> 16f / 9f
        isLarge && asset.aspectRatio == "9:16" -> 0.85f
        isLarge -> 4f / 3f
        asset.aspectRatio == "9:16" -> 0.78f
        else -> 1.25f
    }

    val cardBorder = if (isSelected) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = cardBorder,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                }
            )
            .testTag("asset_card_${asset.id}")
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(previewRatio)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                AsyncImage(
                    model = File(previewPath),
                    contentDescription = asset.prompt,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.65f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = when (kind) {
                                    AssetKind.AI_IMAGE, AssetKind.UPLOADED_IMAGE -> Icons.Rounded.Image
                                    AssetKind.AI_STORYBOARD_REEL -> Icons.Rounded.Movie
                                    AssetKind.UPLOADED_VIDEO -> Icons.Rounded.Videocam
                                },
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = when (kind) {
                                    AssetKind.AI_IMAGE -> "Image"
                                    AssetKind.AI_STORYBOARD_REEL -> "Reel"
                                    AssetKind.UPLOADED_IMAGE -> "Upload"
                                    AssetKind.UPLOADED_VIDEO -> "Video"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White
                            )
                        }
                    }

                    if (isSelectionMode) {
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.65f)
                        ) {
                            Icon(
                                imageVector = if (isSelected) {
                                    Icons.Rounded.CheckCircle
                                } else {
                                    Icons.Rounded.RadioButtonUnchecked
                                },
                                contentDescription = if (isSelected) "Selected" else "Not selected",
                                tint = if (isSelected) Color.White else Color.White.copy(alpha = 0.75f),
                                modifier = Modifier
                                    .padding(3.dp)
                                    .size(20.dp)
                            )
                        }
                    } else {
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.65f)
                        ) {
                            Text(
                                text = asset.aspectRatio,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                if (!asset.folderTag.isNullOrBlank()) {
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.70f),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FolderOpen,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = asset.folderTag,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (!isSelectionMode && (kind == AssetKind.UPLOADED_VIDEO || kind == AssetKind.AI_STORYBOARD_REEL)) {
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.65f),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = "Play",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = asset.prompt,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = if (isLarge) 2 else 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = asset.modelLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (asset.generationDurationMs != null && asset.generationDurationMs > 0) {
                            Text(
                                text = "• ${formatDurationSeconds(asset.generationDurationMs)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }

                if (!isSelectionMode) {
                    IconButton(
                        onClick = onShare,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("share_asset_${asset.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Share,
                            contentDescription = stringResource(R.string.cd_share_asset),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = onMoveToTrash,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("trash_asset_${asset.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = stringResource(R.string.cd_delete_asset),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

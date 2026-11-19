package com.example.data.local

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

private const val TAG = "MediaStorageHelper"

data class ImportedMediaResult(
    val filePath: String,
    val isVideo: Boolean,
    val thumbnailPath: String?
)

data class BatchExportResult(
    val exportedCount: Int,
    val failedCount: Int
)

class MediaStorageHelper(private val context: Context) {
    private val mediaDir: File
        get() = File(context.filesDir, "media").apply {
            if (!exists()) mkdirs()
        }

    suspend fun saveBitmapAsPng(bitmap: Bitmap, prefix: String = "gen"): String =
        withContext(Dispatchers.IO) {
            val file = File(mediaDir, "${prefix}_${UUID.randomUUID()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
            }
            file.absolutePath
        }

    suspend fun importPickedUri(uri: Uri): ImportedMediaResult? =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val mimeType = resolver.getType(uri) ?: "image/jpeg"
            val isVideo = mimeType.startsWith("video/")
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
                ?: if (isVideo) "mp4" else "jpg"

            val destFile = File(mediaDir, "upload_${UUID.randomUUID()}.$ext")
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext null

            val thumbPath = if (isVideo) {
                extractVideoThumbnail(destFile)
            } else {
                null
            }

            ImportedMediaResult(
                filePath = destFile.absolutePath,
                isVideo = isVideo,
                thumbnailPath = thumbPath
            )
        }

    private fun extractVideoThumbnail(videoFile: File): String? {
        return runCatching {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(videoFile.absolutePath)
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            retriever.release()
            frame?.let { bmp ->
                val thumbFile = File(mediaDir, "vthumb_${UUID.randomUUID()}.jpg")
                FileOutputStream(thumbFile).use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
                }
                thumbFile.absolutePath
            }
        }.getOrNull()
    }

    suspend fun loadScaledBitmap(filePath: String, maxDimension: Int = 1024): Bitmap? =
        withContext(Dispatchers.IO) {
            val file = File(filePath)
            if (!file.exists()) return@withContext null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(filePath, bounds)
            val largest = maxOf(bounds.outWidth, bounds.outHeight)
            var sampleSize = 1
            while (largest / sampleSize > maxDimension) {
                sampleSize *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            BitmapFactory.decodeFile(filePath, options)
        }

    fun deleteFileQuietly(filePath: String) {
        runCatching {
            val file = File(filePath)
            if (file.exists()) file.delete()
        }
    }

    fun shareFile(filePath: String, fallbackMimeType: String, promptText: String? = null): Boolean {
        val file = File(filePath)
        if (!file.exists()) return false
        return runCatching {
            val ext = file.extension.lowercase()
            val resolvedMime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: fallbackMimeType
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = resolvedMime
                putExtra(Intent.EXTRA_STREAM, contentUri)
                if (!promptText.isNullOrBlank()) {
                    putExtra(Intent.EXTRA_TEXT, promptText)
                }
                clipData = android.content.ClipData.newRawUri("Noomi AI Asset", contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(shareIntent, "Share via Noomi AI").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(chooser)
            true
        }.onFailure { e ->
            Log.e(TAG, "Failed to open share intent for $filePath", e)
        }.getOrDefault(false)
    }

    suspend fun exportAssetsToGallery(assets: List<StudioAssetEntity>): BatchExportResult =
        withContext(Dispatchers.IO) {
            var exported = 0
            var failed = 0

            assets.forEach { asset ->
                when (asset.assetKind) {
                    AssetKind.AI_STORYBOARD_REEL -> {
                        val scenes = asset.parseStoryboardScenes()
                        val filesToSave = if (scenes.isNotEmpty()) {
                            scenes.map { it.imageFilePath }
                        } else {
                            listOf(asset.primaryFilePath)
                        }
                        var anySaved = false
                        filesToSave.forEachIndexed { idx, path ->
                            val ok = saveSingleFileToGallery(
                                sourceFile = File(path),
                                isVideo = false,
                                displayName = "NoomiAI_Reel_${asset.id.take(6)}_scene${idx + 1}.png"
                            )
                            if (ok) anySaved = true
                        }
                        if (anySaved) exported++ else failed++
                    }
                    AssetKind.UPLOADED_VIDEO -> {
                        val src = File(asset.primaryFilePath)
                        val ext = src.extension.ifBlank { "mp4" }
                        val ok = saveSingleFileToGallery(
                            sourceFile = src,
                            isVideo = true,
                            displayName = "NoomiAI_Video_${asset.id.take(6)}.$ext"
                        )
                        if (ok) exported++ else failed++
                    }
                    AssetKind.AI_IMAGE, AssetKind.UPLOADED_IMAGE -> {
                        val src = File(asset.primaryFilePath)
                        val ext = src.extension.ifBlank { "png" }
                        val ok = saveSingleFileToGallery(
                            sourceFile = src,
                            isVideo = false,
                            displayName = "NoomiAI_Image_${asset.id.take(6)}.$ext"
                        )
                        if (ok) exported++ else failed++
                    }
                }
            }

            BatchExportResult(exportedCount = exported, failedCount = failed)
        }

    private fun saveSingleFileToGallery(
        sourceFile: File,
        isVideo: Boolean,
        displayName: String
    ): Boolean {
        if (!sourceFile.exists()) return false
        val ext = sourceFile.extension.lowercase()
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: if (isVideo) "video/mp4" else "image/png"

        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = if (isVideo) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }
                val relativeDir = if (isVideo) {
                    "${Environment.DIRECTORY_MOVIES}/Noomi AI"
                } else {
                    "${Environment.DIRECTORY_PICTURES}/Noomi AI"
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val itemUri = resolver.insert(collection, values) ?: return false
                try {
                    resolver.openOutputStream(itemUri)?.use { out ->
                        FileInputStream(sourceFile).use { input ->
                            input.copyTo(out)
                        }
                    } ?: return false
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(itemUri, values, null, null)
                    true
                } catch (e: Exception) {
                    resolver.delete(itemUri, null, null)
                    throw e
                }
            } else {
                val baseDir = context.getExternalFilesDir(
                    if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
                ) ?: mediaDir
                val exportDir = File(baseDir, "Noomi AI").apply { if (!exists()) mkdirs() }
                val dest = File(exportDir, displayName)
                FileInputStream(sourceFile).use { input ->
                    FileOutputStream(dest).use { out ->
                        input.copyTo(out)
                    }
                }
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(dest.absolutePath),
                    arrayOf(mimeType),
                    null
                )
                true
            }
        }.onFailure { e ->
            Log.e(TAG, "Failed exporting $displayName to gallery", e)
        }.getOrDefault(false)
    }
}

package org.nidoham.server.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.math.min
import kotlin.math.max

/**
 * Optimized Image Compressor targeting 400-500KB average, 1MB max
 * Maintains visual quality through smart dimension scaling + quality optimization
 */
class ImageCompressor(private val context: Context) {

    companion object {
        private const val TAG = "ImageCompressor"

        // Target: 400-500KB average, 1MB absolute max
        private const val TARGET_FILE_SIZE_BYTES = 500L * 1024L // 450KB sweet spot
        private const val MAX_FILE_SIZE_BYTES = 1L * 1024L * 1024L // 1MB hard limit
        private const val THUMBNAIL_DIMENSION = 720

        // Quality settings - start high, reduce only if needed
        private const val MAX_QUALITY = 92 // Start here for quality
        private const val HIGH_QUALITY = 85 // Standard high quality
        private const val MEDIUM_QUALITY = 80 // Fallback
        private const val MIN_QUALITY = 75 // Never below this to preserve quality

        // Dimension constraints for size control
        private const val MAX_DIMENSION_HIGH = 1920 // 1080p-1440p range
        private const val MAX_DIMENSION_MEDIUM = 1600 // For larger files
        private const val MAX_DIMENSION_LOW = 1280 // Last resort
    }

    data class CompressionResult(
        val file: File,
        val originalSize: Long,
        val compressedSize: Long,
        val compressionRatio: Float,
        val dimensions: Pair<Int, Int>,
        val quality: Int
    )

    data class CompressionOptions(
        val maxDimension: Int = MAX_DIMENSION_HIGH,
        val quality: Int = HIGH_QUALITY,
        val preserveExif: Boolean = true,
        val targetSize: Long = TARGET_FILE_SIZE_BYTES,
        val maxSize: Long = MAX_FILE_SIZE_BYTES,
        val aggressive: Boolean = false // Set true for strict size enforcement
    )

    /**
     * Primary compression - targets 400-500KB, max 1MB
     */
    fun compress(imageUri: Uri): CompressionResult {
        return compress(imageUri, CompressionOptions())
    }

    fun compress(
        imageUri: Uri,
        options: CompressionOptions
    ): CompressionResult {
        val originalFile = getFileFromUri(imageUri)
        val originalSize = originalFile.length()

        Timber.tag(TAG).d("Original: ${originalFile.name}, size: ${formatSize(originalSize)}")

        // Decode with sampling for memory efficiency
        val bitmap = decodeSampledBitmap(imageUri, options.maxDimension, options.maxDimension)
            ?: throw IllegalArgumentException("Failed to decode bitmap")

        // Handle EXIF rotation
        val orientedBitmap = correctOrientation(imageUri, bitmap)

        // Try compression pipeline: dimension scaling first, then quality adjustment
        val result = compressPipeline(orientedBitmap, options, originalFile.name, originalSize)

        // Cleanup bitmaps
        if (bitmap != orientedBitmap) bitmap.recycle()
        orientedBitmap.recycle()

        Timber.tag(TAG).d("Final: ${formatSize(result.compressedSize)}, ratio: ${"%.1f".format(result.compressionRatio * 100)}%, quality: ${result.quality}")

        return result
    }

    /**
     * Pipeline: Scale dimensions first (better quality than low quality + full size)
     * Then adjust quality if needed
     */
    private fun compressPipeline(
        sourceBitmap: Bitmap,
        options: CompressionOptions,
        originalName: String,
        originalSize: Long
    ): CompressionResult {

        var currentDimension = options.maxDimension
        var currentQuality = options.quality
        var attempt = 0
        val maxAttempts = 4

        while (attempt < maxAttempts) {
            attempt++

            // Scale bitmap to current dimension
            val scaledBitmap = if (sourceBitmap.width > currentDimension || sourceBitmap.height > currentDimension) {
                scaleBitmap(sourceBitmap, currentDimension, currentDimension)
            } else {
                sourceBitmap
            }

            // Try compressing with current quality
            val tempFile = compressToFile(scaledBitmap, currentQuality, originalName)
            val fileSize = tempFile.length()

            // Check if within target
            when {
                // Perfect: within target range
                fileSize <= options.targetSize -> {
                    val dimensions = Pair(scaledBitmap.width, scaledBitmap.height)
                    if (scaledBitmap != sourceBitmap) scaledBitmap.recycle()
                    return createResult(tempFile, originalSize, fileSize, dimensions, currentQuality)
                }

                // Acceptable: under max limit but over target
                fileSize <= options.maxSize && !options.aggressive -> {
                    val dimensions = Pair(scaledBitmap.width, scaledBitmap.height)
                    if (scaledBitmap != sourceBitmap) scaledBitmap.recycle()
                    return createResult(tempFile, originalSize, fileSize, dimensions, currentQuality)
                }

                // Too big: reduce quality first, then dimensions
                else -> {
                    tempFile.delete()

                    when {
                        // Try reducing quality first (keeps resolution)
                        currentQuality > MIN_QUALITY -> {
                            currentQuality = (currentQuality - 5).coerceAtLeast(MIN_QUALITY)
                            if (scaledBitmap != sourceBitmap) scaledBitmap.recycle()
                            // Retry with lower quality, same dimensions
                        }

                        // Quality at minimum, reduce dimensions
                        currentDimension > MAX_DIMENSION_LOW -> {
                            currentDimension = when {
                                currentDimension >= MAX_DIMENSION_HIGH -> MAX_DIMENSION_MEDIUM
                                currentDimension >= MAX_DIMENSION_MEDIUM -> MAX_DIMENSION_LOW
                                else -> (currentDimension * 0.8).toInt()
                            }
                            currentQuality = HIGH_QUALITY // Reset quality for new size
                            if (scaledBitmap != sourceBitmap) scaledBitmap.recycle()
                        }

                        // Last resort: force compress with minimum settings
                        else -> {
                            val finalFile = compressToFile(scaledBitmap, MIN_QUALITY, originalName)
                            val finalSize = finalFile.length()
                            val dimensions = Pair(scaledBitmap.width, scaledBitmap.height)
                            if (scaledBitmap != sourceBitmap) scaledBitmap.recycle()
                            return createResult(finalFile, originalSize, finalSize, dimensions, MIN_QUALITY)
                        }
                    }
                }
            }
        }

        // Fallback
        val fallbackFile = compressToFile(sourceBitmap, MIN_QUALITY, originalName)
        return createResult(fallbackFile, originalSize, fallbackFile.length(),
            Pair(sourceBitmap.width, sourceBitmap.height), MIN_QUALITY)
    }

    private fun compressToFile(bitmap: Bitmap, quality: Int, originalName: String): File {
        val outputFile = createTempFile(originalName)
        FileOutputStream(outputFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        return outputFile
    }

    private fun createResult(
        file: File,
        originalSize: Long,
        compressedSize: Long,
        dimensions: Pair<Int, Int>,
        quality: Int
    ): CompressionResult {
        return CompressionResult(
            file = file,
            originalSize = originalSize,
            compressedSize = compressedSize,
            compressionRatio = compressedSize.toFloat() / originalSize.toFloat(),
            dimensions = dimensions,
            quality = quality
        )
    }

    /**
     * Quick compress for thumbnails
     */
    fun compressThumbnail(imageUri: Uri): CompressionResult {
        return compress(
            imageUri,
            CompressionOptions(
                maxDimension = THUMBNAIL_DIMENSION,
                quality = 85,
                targetSize = 100 * 1024, // 100KB for thumbnails
                maxSize = 200 * 1024
            )
        )
    }

    /**
     * Batch compress with progress callback
     */
    fun compressBatch(
        uris: List<Uri>,
        onProgress: ((Int, Int) -> Unit)? = null
    ): List<CompressionResult> {
        return uris.mapIndexed { index, uri ->
            onProgress?.invoke(index + 1, uris.size)
            try {
                compress(uri)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to compress $uri")
                throw e
            }
        }
    }

    /**
     * Strict mode - guarantees under 1MB even if quality suffers slightly
     */
    fun compressStrict(imageUri: Uri): CompressionResult {
        return compress(imageUri, CompressionOptions(aggressive = true))
    }

    private fun decodeSampledBitmap(uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }

        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inJustDecodeBounds = false
        options.inPreferredConfig = Bitmap.Config.ARGB_8888

        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while (halfHeight / inSampleSize >= reqHeight &&
                halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun correctOrientation(uri: Uri, bitmap: Bitmap): Bitmap {
        val rotation = when (getExifOrientation(uri)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }

        return if (rotation != 0f) {
            val matrix = Matrix().apply { postRotate(rotation) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }
    }

    private fun getExifOrientation(uri: Uri): Int {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: IOException) {
            Timber.tag(TAG).w(e, "Failed to read EXIF")
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    private fun scaleBitmap(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxWidth && height <= maxHeight) {
            return bitmap
        }

        val ratio = min(
            maxWidth.toFloat() / width,
            maxHeight.toFloat() / height
        )

        val newWidth = max(1, (width * ratio).toInt())
        val newHeight = max(1, (height * ratio).toInt())

        return bitmap.scale(newWidth, newHeight)
    }

    private fun createTempFile(originalName: String): File {
        val extension = originalName.substringAfterLast(".", "jpg")
        val uniqueName = "compressed_${UUID.randomUUID()}.$extension"
        return File(context.cacheDir, uniqueName).apply {
            createNewFile()
        }
    }

    private fun getFileFromUri(uri: Uri): File {
        return when (uri.scheme) {
            "file" -> File(uri.path!!)
            "content" -> {
                val tempFile = createTempFile("temp_${UUID.randomUUID()}")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                tempFile
            }
            else -> throw IllegalArgumentException("Unsupported URI scheme: ${uri.scheme}")
        }
    }

    fun getContentUri(file: File): Uri {
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileProvider",
            file
        )
    }

    fun cleanup() {
        context.cacheDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("compressed_") || file.name.startsWith("temp_")) {
                file.delete()
            }
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
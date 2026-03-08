package com.nidoham.bondhu.presentation.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nidoham.server.util.ImageCompressor
import org.nidoham.server.util.ImgBBResult
import org.nidoham.server.util.ImgBBStorage
import timber.log.Timber
import java.io.File

class UploadViewModel(application: Application) : AndroidViewModel(application) {

    private val compressor = ImageCompressor(application)
    private val compressedFiles = mutableListOf<File>()

    // ── State ─────────────────────────────────────────────────────────────────

    sealed class UploadState {
        object Idle : UploadState()
        data class Compressing(val progress: Int, val total: Int) : UploadState()
        data class Uploading(val progress: Int, val total: Int) : UploadState()
        data class Success(val results: List<ImgBBResult>) : UploadState()
        data class Error(val message: String) : UploadState()
    }

    private val _uploadState = MutableLiveData<UploadState>(UploadState.Idle)
    val uploadState: LiveData<UploadState> = _uploadState

    // Legacy support (optional)
    private val _compressionResult = MutableLiveData<Result<ImageCompressor.CompressionResult>>()
    val compressionResult: LiveData<Result<ImageCompressor.CompressionResult>> = _compressionResult

    // ── Single Image Upload ────────────────────────────────────────────────────

    fun compressAndUpload(uri: Uri) {
        viewModelScope.launch {
            try {
                _uploadState.value = UploadState.Compressing(1, 1)

                // Compress
                val result: ImageCompressor.CompressionResult = withContext(Dispatchers.IO) {
                    compressor.compress(uri)
                }
                trackFile(result.file)
                _compressionResult.postValue(Result.success(result))

                Timber.d("Compressed: ${result.originalSize} → ${result.compressedSize} bytes")

                _uploadState.value = UploadState.Uploading(1, 1)

                // Upload to ImgBB
                val uploadResult: ImgBBResult = withContext(Dispatchers.IO) {
                    ImgBBStorage.upload(result.file)
                }

                _uploadState.value = UploadState.Success(listOf(uploadResult))
                cleanupFile(result.file)

            } catch (e: Exception) {
                Timber.e(e, "Upload failed")
                _uploadState.value = UploadState.Error(e.message ?: "Unknown error")
                _compressionResult.postValue(Result.failure(e))
            }
        }
    }

    // ── Batch Upload ───────────────────────────────────────────────────────────

    fun compressAndUploadBatch(uris: List<Uri>, basePath: String = "") {
        if (uris.isEmpty()) return

        viewModelScope.launch {
            try {
                _uploadState.value = UploadState.Compressing(0, uris.size)

                // Compress all
                val compressed: List<ImageCompressor.CompressionResult> = withContext(Dispatchers.IO) {
                    compressor.compressBatch(uris) { current: Int, total: Int ->
                        _uploadState.postValue(UploadState.Compressing(current, total))
                    }
                }

                compressed.forEach { trackFile(it.file) }

                _uploadState.value = UploadState.Uploading(0, uris.size)

                // Upload all
                val files: List<File> = compressed.map { it.file }
                val (results: List<ImgBBResult>, errors: List<Throwable>) = withContext(Dispatchers.IO) {
                    ImgBBStorage.uploadAll(files, basePath) { current: Int, total: Int ->
                        _uploadState.postValue(UploadState.Uploading(current, total))
                    }
                }

                // Cleanup
                compressed.forEach { cleanupFile(it.file) }

                when {
                    results.isNotEmpty() && errors.isEmpty() -> {
                        _uploadState.value = UploadState.Success(results)
                    }
                    results.isNotEmpty() -> {
                        // Partial success
                        _uploadState.value = UploadState.Success(results)
                        Timber.w("Partial upload: ${errors.size} failed")
                    }
                    else -> {
                        _uploadState.value = UploadState.Error(
                            errors.firstOrNull()?.message ?: "All uploads failed"
                        )
                    }
                }

            } catch (e: Exception) {
                Timber.e(e, "Batch upload failed")
                _uploadState.value = UploadState.Error(e.message ?: "Unknown error")
            }
        }
    }

    // ── Thumbnail Upload ─────────────────────────────────────────────────────

    fun compressAndUploadThumbnail(uri: Uri) {
        viewModelScope.launch {
            try {
                _uploadState.value = UploadState.Compressing(1, 1)

                val result: ImageCompressor.CompressionResult = withContext(Dispatchers.IO) {
                    compressor.compressThumbnail(uri)
                }
                trackFile(result.file)

                _uploadState.value = UploadState.Uploading(1, 1)

                val uploadResult: ImgBBResult = withContext(Dispatchers.IO) {
                    ImgBBStorage.upload(result.file)
                }

                _uploadState.value = UploadState.Success(listOf(uploadResult))
                cleanupFile(result.file)

            } catch (e: Exception) {
                Timber.e(e, "Thumbnail upload failed")
                _uploadState.value = UploadState.Error(e.message ?: "Unknown error")
            }
        }
    }

    // ── Strict Compress Upload (replaces autoCompress) ───────────────────────

    fun compressStrictAndUpload(uri: Uri) {
        viewModelScope.launch {
            try {
                _uploadState.value = UploadState.Compressing(1, 1)

                val result: ImageCompressor.CompressionResult = withContext(Dispatchers.IO) {
                    compressor.compressStrict(uri)
                }
                trackFile(result.file)

                Timber.d("Strict compressed: quality=${result.quality}, size=${result.compressedSize}")

                _uploadState.value = UploadState.Uploading(1, 1)

                val uploadResult: ImgBBResult = withContext(Dispatchers.IO) {
                    ImgBBStorage.upload(result.file)
                }

                _uploadState.value = UploadState.Success(listOf(uploadResult))
                cleanupFile(result.file)

            } catch (e: Exception) {
                Timber.e(e, "Strict upload failed")
                _uploadState.value = UploadState.Error(e.message ?: "Unknown error")
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    fun resetState() {
        _uploadState.value = UploadState.Idle
    }

    fun refreshApiKey() {
        ImgBBStorage.invalidateApiKey()
    }

    private fun trackFile(file: File) {
        synchronized(compressedFiles) {
            compressedFiles.add(file)
        }
    }

    private fun cleanupFile(file: File) {
        synchronized(compressedFiles) {
            compressedFiles.remove(file)
        }
        if (file.exists()) file.delete()
    }

    override fun onCleared() {
        super.onCleared()
        synchronized(compressedFiles) {
            compressedFiles.forEach { if (it.exists()) it.delete() }
            compressedFiles.clear()
        }
        compressor.cleanup()
    }
}
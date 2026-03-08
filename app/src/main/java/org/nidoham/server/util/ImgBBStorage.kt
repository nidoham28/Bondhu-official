package org.nidoham.server.util

import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

// ── DTOs ───────────────────────────────────────────────────────────────────────

data class ImgBBResult(
    val id: String,
    val url: String,
    val displayUrl: String,
    val thumbUrl: String,
    val deleteUrl: String,
    val size: Long,
    val path: String
)

private data class ImgBBResponse(
    val success: Boolean,
    val data: ImgBBData?,
    val error: ImgBBError?
)

private data class ImgBBData(
    val id: String,
    val url: String,
    val display_url: String,
    val delete_url: String,
    val size: Long,
    val thumb: ImgBBThumb
)

private data class ImgBBThumb(val url: String)

private data class ImgBBError(val message: String)

// ── Retrofit service interface ─────────────────────────────────────────────────

private interface ImgBBService {

    @Multipart
    @POST("upload")
    suspend fun upload(
        @Query("key") apiKey: String,
        @Part image: MultipartBody.Part
    ): Response<ImgBBResponse>
}

// ── Client ─────────────────────────────────────────────────────────────────────

private val IMAGE_MEDIA_TYPE = "image/*".toMediaTypeOrNull()

object ImgBBStorage {

    private const val BASE_URL      = "https://api.imgbb.com/1/"
    private const val MAX_FILE_SIZE = 32L * 1024 * 1024

    private val service: ImgBBService = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        )
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(ImgBBService::class.java)

    private val configRef = FirebaseDatabase.getInstance()
        .getReference("config/imgbb/apiKey")

    @Volatile private var cachedApiKey: String? = null

    suspend fun apiKey(): String =
        cachedApiKey ?: configRef.get().await()
            .getValue(String::class.java)
            ?.also { cachedApiKey = it }
        ?: error("ImgBB API key not found at Firebase path: config/imgbb/apiKey")

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Uploads a [File]. Throws [IOException] on failure. */
    suspend fun upload(
        file: File,
        path: String = file.name
    ): ImgBBResult {
        require(file.exists())                  { "File does not exist: ${file.path}" }
        require(file.length() <= MAX_FILE_SIZE) { "File exceeds the 32 MB limit" }

        val part = MultipartBody.Part.createFormData(
            "image", file.name, file.asRequestBody(IMAGE_MEDIA_TYPE)
        )
        return executeAndParse(part, path)
    }

    /** Uploads a raw [ByteArray] as a multipart image. Throws [IOException] on failure. */
    suspend fun upload(
        data: ByteArray,
        fileName: String = "image.jpg",
        path: String = fileName
    ): ImgBBResult {
        require(data.size <= MAX_FILE_SIZE) { "Data exceeds the 32 MB limit" }

        val part = MultipartBody.Part.createFormData(
            "image", fileName, data.toRequestBody(IMAGE_MEDIA_TYPE)
        )
        return executeAndParse(part, path)
    }

    /**
     * Uploads multiple files sequentially.
     * Returns a pair of successful [ImgBBResult] results and any [Exception] failures.
     */
    suspend fun uploadAll(
        files: List<File>,
        basePath: String = "",
        onProgress: ((completed: Int, total: Int) -> Unit)? = null
    ): Pair<List<ImgBBResult>, List<Exception>> = withContext(Dispatchers.IO) {
        val results = mutableListOf<ImgBBResult>()
        val errors  = mutableListOf<Exception>()

        files.forEachIndexed { index, file ->
            val path = if (basePath.isEmpty()) file.name else "$basePath/${file.name}"
            runCatching { results += upload(file, path) }
                .onFailure { errors += it as? Exception ?: Exception(it) }
            onProgress?.invoke(index + 1, files.size)
        }

        results to errors
    }

    /** Forces a fresh API key fetch from Firebase on the next upload. */
    fun invalidateApiKey() { cachedApiKey = null }

    // ── Private helpers ────────────────────────────────────────────────────────

    private suspend fun executeAndParse(
        part: MultipartBody.Part,
        path: String
    ): ImgBBResult = withContext(Dispatchers.IO) {
        val response = service.upload(apiKey(), part)

        if (!response.isSuccessful) {
            throw IOException("HTTP ${response.code()}: ${response.message()}")
        }

        val body = response.body()
            ?: throw IOException("Empty response body from ImgBB")

        if (!body.success || body.data == null) {
            throw IOException("ImgBB error: ${body.error?.message ?: "Unknown error"}")
        }

        body.data.toResult(path)
    }

    private fun ImgBBData.toResult(path: String) = ImgBBResult(
        id         = id,
        url        = url,
        displayUrl = display_url,
        thumbUrl   = thumb.url,
        deleteUrl  = delete_url,
        size       = size,
        path       = path
    )
}
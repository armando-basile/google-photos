package dev.armando.tvphotos.picker

import dev.armando.tvphotos.auth.AuthExpiredException
import dev.armando.tvphotos.auth.GoogleAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

@Serializable
data class PollingConfig(val pollInterval: String? = null, val timeoutIn: String? = null)

@Serializable
data class PickerSession(
    val id: String,
    // Dopo "Fine" Google non restituisce più il pickerUri.
    val pickerUri: String = "",
    val pollingConfig: PollingConfig? = null,
    val mediaItemsSet: Boolean = false,
)

@Serializable
data class MediaFile(val baseUrl: String, val mimeType: String? = null, val filename: String? = null)

@Serializable
data class PickedItem(
    val id: String,
    val createTime: String? = null,
    val type: String? = null,
    val mediaFile: MediaFile? = null,
)

@Serializable
private data class PickedItemsPage(val mediaItems: List<PickedItem> = emptyList(), val nextPageToken: String? = null)

class PickerApi(private val http: OkHttpClient, private val auth: GoogleAuth) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun createSession(): PickerSession =
        json.decodeFromString(call("POST", "$BASE/sessions", "{}".toRequestBody(JSON_TYPE)))

    suspend fun getSession(id: String): PickerSession =
        json.decodeFromString(call("GET", "$BASE/sessions/$id"))

    suspend fun deleteSession(id: String) {
        call("DELETE", "$BASE/sessions/$id")
    }

    suspend fun listItems(sessionId: String): List<PickedItem> {
        val all = mutableListOf<PickedItem>()
        var token: String? = null
        do {
            val url = "$BASE/mediaItems?sessionId=$sessionId&pageSize=100" +
                (token?.let { "&pageToken=$it" } ?: "")
            val page = json.decodeFromString<PickedItemsPage>(call("GET", url))
            all += page.mediaItems
            token = page.nextPageToken
        } while (!token.isNullOrEmpty())
        return all
    }

    /** Scarica l'immagine ridimensionata a max 1920x1080 (i baseUrl richiedono il token Bearer). */
    suspend fun downloadImage(baseUrl: String, dest: File) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl=w1920-h1080")
            .header("Authorization", "Bearer ${auth.accessToken()}")
            .build()
        http.newCall(request).execute().use { r ->
            if (r.code == 401) throw AuthExpiredException()
            if (!r.isSuccessful) throw IOException("Download fallito (${r.code})")
            val tmp = File(dest.parentFile, dest.name + ".part")
            r.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(dest)) throw IOException("Impossibile salvare ${dest.name}")
        }
    }

    private suspend fun call(method: String, url: String, body: okhttp3.RequestBody? = null): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${auth.accessToken()}")
                .method(method, body)
                .build()
            http.newCall(request).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (r.code == 401) throw AuthExpiredException()
                if (!r.isSuccessful) throw IOException("Picker API (${r.code}): ${text.take(200)}")
                text
            }
        }

    private companion object {
        const val BASE = "https://photospicker.googleapis.com/v1"
        val JSON_TYPE = "application/json".toMediaType()
    }
}

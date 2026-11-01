package dev.armando.tvphotos.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class AuthExpiredException : IOException("Autorizzazione scaduta o revocata")

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long = 3600,
    @SerialName("refresh_token") val refreshToken: String? = null,
)

class GoogleAuth(
    private val http: OkHttpClient,
    private val store: TokenStore,
    private val config: () -> OAuthConfig,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var access: String? = null
    private var expiresAtMs = 0L

    val isPaired: Boolean get() = store.refreshToken != null

    suspend fun exchangeCode(code: String, verifier: String) {
        val resp = tokenRequest(
            "grant_type" to "authorization_code",
            "code" to code,
            "code_verifier" to verifier,
            "redirect_uri" to config().pageUrl,
        )
        store.refreshToken = resp.refreshToken ?: throw IOException("Google non ha restituito il refresh token")
        mutex.withLock { remember(resp) }
    }

    suspend fun accessToken(): String = mutex.withLock {
        val cached = access
        if (cached != null && System.currentTimeMillis() < expiresAtMs - 60_000) return cached
        val refresh = store.refreshToken ?: throw AuthExpiredException()
        try {
            remember(tokenRequest("grant_type" to "refresh_token", "refresh_token" to refresh))
        } catch (e: AuthExpiredException) {
            signOut()
            throw e
        }
    }

    fun signOut() {
        store.refreshToken = null
        access = null
    }

    private fun remember(resp: TokenResponse): String {
        access = resp.accessToken
        expiresAtMs = System.currentTimeMillis() + resp.expiresIn * 1000
        return resp.accessToken
    }

    private suspend fun tokenRequest(vararg fields: Pair<String, String>): TokenResponse =
        withContext(Dispatchers.IO) {
            val cfg = config()
            val body = FormBody.Builder().apply {
                add("client_id", cfg.clientId)
                if (cfg.clientSecret.isNotBlank()) add("client_secret", cfg.clientSecret)
                fields.forEach { (k, v) -> add(k, v) }
            }.build()
            val request = Request.Builder().url("https://oauth2.googleapis.com/token").post(body).build()
            http.newCall(request).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    if (text.contains("invalid_grant")) throw AuthExpiredException()
                    throw IOException("Errore token Google (${r.code}): ${text.take(200)}")
                }
                json.decodeFromString<TokenResponse>(text)
            }
        }
}

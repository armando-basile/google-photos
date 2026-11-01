package dev.armando.tvphotos.auth

import dev.armando.tvphotos.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Credenziali OAuth in uso: quelle personalizzate dall'utente oppure, se assenti, quelle della build. */
data class OAuthConfig(
    val clientId: String,
    val clientSecret: String,
    val pageUrl: String,
    val isCustom: Boolean,
) {
    val isConfigured: Boolean get() = clientId.isNotBlank()

    /** Prime cifre dell'ID client, per riconoscerlo a schermo senza mostrarlo per intero. */
    val clientIdHint: String get() = clientId.take(8) + "..."
}

class OAuthConfigStore(private val store: TokenStore) {
    private val _config = MutableStateFlow(load())
    val config: StateFlow<OAuthConfig> = _config

    /** [pageUrl] vuoto = pagina di reindirizzamento predefinita. */
    fun save(clientId: String, clientSecret: String, pageUrl: String) {
        store.setSecret(K_ID, clientId.trim())
        store.setSecret(K_SECRET, clientSecret.trim())
        store.setSecret(K_PAGE, pageUrl.trim().ifEmpty { null })
        _config.value = load()
    }

    fun reset() {
        store.setSecret(K_ID, null)
        store.setSecret(K_SECRET, null)
        store.setSecret(K_PAGE, null)
        _config.value = load()
    }

    private fun load(): OAuthConfig {
        val id = store.getSecret(K_ID)
        if (!id.isNullOrBlank()) {
            return OAuthConfig(
                clientId = id,
                clientSecret = store.getSecret(K_SECRET).orEmpty(),
                pageUrl = store.getSecret(K_PAGE) ?: BuildConfig.PAIRING_PAGE_URL,
                isCustom = true,
            )
        }
        return OAuthConfig(
            clientId = BuildConfig.GOOGLE_CLIENT_ID,
            clientSecret = BuildConfig.GOOGLE_CLIENT_SECRET,
            pageUrl = BuildConfig.PAIRING_PAGE_URL,
            isCustom = false,
        )
    }

    companion object {
        private const val K_ID = "custom_client_id"
        private const val K_SECRET = "custom_client_secret"
        private const val K_PAGE = "custom_page_url"
        private val CLIENT_ID_REGEX = Regex("^[0-9]+-[A-Za-z0-9_]+\\.apps\\.googleusercontent\\.com$")

        /** Restituisce un messaggio d'errore in italiano, oppure null se i valori sono accettabili. */
        fun validate(clientId: String, clientSecret: String, pageUrl: String): String? {
            if (!CLIENT_ID_REGEX.matches(clientId.trim())) {
                return "L'ID client non è valido: deve finire con .apps.googleusercontent.com"
            }
            if (clientSecret.trim().length < 10) return "Il client secret è mancante o troppo corto"
            val page = pageUrl.trim()
            if (page.isNotEmpty() && !(page.startsWith("https://") && page.length > 12 && !page.contains(' '))) {
                return "La pagina di reindirizzamento deve essere un URL https"
            }
            return null
        }
    }
}

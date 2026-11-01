package dev.armando.tvphotos

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.armando.tvphotos.auth.AuthExpiredException
import dev.armando.tvphotos.auth.GoogleAuth
import dev.armando.tvphotos.auth.OAuthConfig
import dev.armando.tvphotos.auth.OAuthConfigStore
import dev.armando.tvphotos.auth.PairingServer
import dev.armando.tvphotos.auth.Pkce
import dev.armando.tvphotos.auth.SetupServer
import dev.armando.tvphotos.auth.TokenStore
import dev.armando.tvphotos.data.LocalPhoto
import dev.armando.tvphotos.data.PhotoCollection
import dev.armando.tvphotos.data.PhotoLibrary
import dev.armando.tvphotos.picker.PickerApi
import dev.armando.tvphotos.ui.NetworkUtils
import dev.armando.tvphotos.update.UpdateManager
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

sealed interface UiState {
    data object Starting : UiState
    data object ChooseFolder : UiState
    data object Consent : UiState
    data class Pairing(val url: String?, val error: String?) : UiState
    data class Picking(val pickerUri: String?, val error: String?) : UiState
    data class Importing(val done: Int, val total: Int) : UiState
    data object Gallery : UiState
}

/** Dove finiranno le foto scelte nel Picker. */
sealed interface PickTarget {
    data class NewFolder(val name: String?) : PickTarget
    data class Existing(val id: String, val replace: Boolean) : PickTarget
}

/** Schermata di inserimento delle credenziali Google dal telefono. */
data class SetupUi(val url: String?, val error: String?)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val tokenStore = TokenStore(app)
    val oauthConfig = OAuthConfigStore(tokenStore)
    private val auth = GoogleAuth(http, tokenStore) { oauthConfig.config.value }
    private val picker = PickerApi(http, auth)
    private val library = PhotoLibrary(app)
    val updates = UpdateManager(app, viewModelScope)
    private val prefs = app.getSharedPreferences("settings", Application.MODE_PRIVATE)

    private val _state = MutableStateFlow<UiState>(UiState.Starting)
    val state: StateFlow<UiState> = _state

    val photos: StateFlow<List<LocalPhoto>> = library.photos
    val collections: StateFlow<List<PhotoCollection>> = library.collections

    private val _openCollection = MutableStateFlow<String?>(null)
    val openCollection: StateFlow<String?> = _openCollection

    private var pickTarget: PickTarget = PickTarget.NewFolder(null)

    private val _viewerIndex = MutableStateFlow<Int?>(null)
    val viewerIndex: StateFlow<Int?> = _viewerIndex

    private var job: Job? = null
    private var setupJob: Job? = null

    private val _setup = MutableStateFlow<SetupUi?>(null)
    val setup: StateFlow<SetupUi?> = _setup

    private val _settingsOpen = MutableStateFlow(false)
    val settingsOpen: StateFlow<Boolean> = _settingsOpen

    init {
        if (updates.checkOnStartup.value) updates.check(manual = false)
        when {
            !oauthConfig.config.value.isConfigured -> _state.value = UiState.Pairing(null, NOT_CONFIGURED)
            !auth.isPaired -> startPairing()
            library.photos.value.isEmpty() -> startPicking()
            else -> _state.value = UiState.Gallery
        }
    }

    fun fileOf(photo: LocalPhoto): File = library.fileOf(photo)

    fun openViewer(index: Int) { _viewerIndex.value = index }

    fun closeViewer() { _viewerIndex.value = null }

    fun openSettings() { _settingsOpen.value = true }

    fun closeSettings() { _settingsOpen.value = false }

    fun showGallery() {
        job?.cancel()
        _state.value = UiState.Gallery
    }

    fun openCollection(id: String) { _openCollection.value = id }

    fun closeCollection() { _openCollection.value = null }

    /** Elimina dalla TV le foto di una raccolta; se era l'ultima, si torna alla scelta delle foto. */
    fun deleteCollection(id: String) {
        library.deleteCollection(id)
        _viewerIndex.value = null
        _openCollection.value = null
        if (library.collections.value.isEmpty()) startPicking()
    }

    /** Nuova scelta nel Picker: aggiunge le foto nuove e toglie quelle non più selezionate. */
    fun syncCollection(id: String) = pick(PickTarget.Existing(id, replace = true))

    fun suggestFolderName(): String = library.defaultName()

    fun renameCollection(id: String, name: String) = library.renameCollection(id, name)

    fun rotatePhoto(photo: LocalPhoto) = library.rotate(photo.file)

    /** Aggiunge le foto scelte a una cartella esistente, senza toccare quelle già presenti. */
    fun pickInto(id: String) = pick(PickTarget.Existing(id, replace = false))

    fun pickNewFolder(name: String) = pick(PickTarget.NewFolder(name))

    fun signOut() {
        job?.cancel()
        library.clear()
        auth.signOut()
        startPairing()
    }

    /** Apre il modulo sul telefono per inserire le proprie credenziali Google. */
    fun startSetup() {
        job?.cancel()
        setupJob?.cancel()
        setupJob = viewModelScope.launch {
            val token = Pkce.randomUrlSafe(16)
            val saved = CompletableDeferred<Unit>()
            var server: SetupServer? = null
            try {
                _setup.value = SetupUi(null, null)
                val ip = NetworkUtils.localIpv4() ?: throw IOException("La TV non è connessa a una rete locale")
                server = startSetupServer(token) { id, secret, page ->
                    OAuthConfigStore.validate(id, secret, page) ?: run {
                        oauthConfig.save(id, secret, page)
                        saved.complete(Unit)
                        null
                    }
                } ?: throw IOException("Nessuna porta locale disponibile")
                _setup.value = SetupUi("http://$ip:${server.listeningPort}/setup/$token", null)

                saved.await()
                useNewCredentials()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _setup.value = SetupUi(null, e.message ?: "Errore sconosciuto")
            } finally {
                withContext(NonCancellable) {
                    delay(500)
                    server?.stop()
                }
            }
        }
    }

    fun cancelSetup() {
        setupJob?.cancel()
        _setup.value = null
        if (state.value is UiState.Pairing) startPairing()
    }

    /** Torna alle credenziali incluse nella build. */
    fun resetCredentials() {
        oauthConfig.reset()
        useNewCredentials()
    }

    // Il refresh token appartiene al vecchio client OAuth: serve un nuovo collegamento.
    private fun useNewCredentials() {
        auth.signOut()
        _setup.value = null
        _settingsOpen.value = false
        startPairing()
    }

    /** L'informativa mostrata prima di collegare Google va accettata con un'azione esplicita. */
    fun acceptConsent() {
        prefs.edit().putBoolean(KEY_CONSENT, true).apply()
        startPairing()
    }

    fun startPairing() {
        job?.cancel()
        if (!prefs.getBoolean(KEY_CONSENT, false)) {
            _state.value = UiState.Consent
            return
        }
        job = viewModelScope.launch {
            val verifier = Pkce.randomUrlSafe(48)
            val nonce = Pkce.randomUrlSafe(16)
            val result = CompletableDeferred<Result<String>>()
            var server: PairingServer? = null
            try {
                _state.value = UiState.Pairing(null, null)
                val config = oauthConfig.config.value
                if (!config.isConfigured) throw IOException(NOT_CONFIGURED)
                val ip = NetworkUtils.localIpv4() ?: throw IOException("La TV non è connessa a una rete locale")
                server = startServer(nonce) { result.complete(it) }
                    ?: throw IOException("Nessuna porta locale disponibile")
                val tv = "$ip:${server.listeningPort}"
                _state.value = UiState.Pairing(pairingUrl(config, tv, nonce, Pkce.challenge(verifier)), null)

                val code = result.await().getOrThrow()
                auth.exchangeCode(code, verifier)
                afterPaired()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UiState.Pairing(null, e.message ?: "Errore sconosciuto")
            } finally {
                withContext(NonCancellable) {
                    delay(500)
                    server?.stop()
                }
            }
        }
    }

    /** Chiede sempre dove mettere le foto; senza cartelle chiede il nome della prima. */
    fun startPicking() {
        job?.cancel()
        _state.value = UiState.ChooseFolder
    }

    fun retryPicking() = pick(pickTarget)

    private fun pick(target: PickTarget) {
        pickTarget = target
        job?.cancel()
        job = viewModelScope.launch {
            var sessionId: String? = null
            try {
                _state.value = UiState.Picking(null, null)
                val session = picker.createSession()
                sessionId = session.id
                _state.value = UiState.Picking(session.pickerUri, null)

                val interval = seconds(session.pollingConfig?.pollInterval, 5.0)
                val deadline = System.currentTimeMillis() + seconds(session.pollingConfig?.timeoutIn, 300.0)
                var current = session
                while (!current.mediaItemsSet) {
                    if (System.currentTimeMillis() > deadline) throw IOException("Tempo scaduto, riprova")
                    delay(interval)
                    current = picker.getSession(session.id)
                }

                _state.value = UiState.Importing(0, 0)
                val items = picker.listItems(session.id)
                val result = library.import(
                    picker,
                    items,
                    collectionId = (target as? PickTarget.Existing)?.id,
                    newName = (target as? PickTarget.NewFolder)?.name,
                    removeMissing = (target as? PickTarget.Existing)?.replace == true,
                ) { done, total ->
                    _state.value = UiState.Importing(done, total)
                }

                if (target is PickTarget.NewFolder && result.added == 0) {
                    _state.value = UiState.Picking(null, "Nessuna foto importata (i video non sono supportati)")
                } else {
                    _openCollection.value = result.collectionId
                    _state.value = UiState.Gallery
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthExpiredException) {
                startPairing()
            } catch (e: Exception) {
                _state.value = UiState.Picking(null, e.message ?: "Errore sconosciuto")
            } finally {
                sessionId?.let { id -> withContext(NonCancellable) { runCatching { picker.deleteSession(id) } } }
            }
        }
    }

    private fun afterPaired() {
        if (library.photos.value.isEmpty()) startPicking() else _state.value = UiState.Gallery
    }

    private fun startServer(nonce: String, onResult: (Result<String>) -> Unit): PairingServer? {
        for (port in 8765..8785) {
            val server = PairingServer(port, nonce, onResult)
            try {
                server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                return server
            } catch (_: IOException) {
                continue
            }
        }
        return null
    }

    private fun startSetupServer(token: String, onSubmit: (String, String, String) -> String?): SetupServer? {
        for (port in 8765..8785) {
            val server = SetupServer(port, token, BuildConfig.PAIRING_PAGE_URL, onSubmit)
            try {
                server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                return server
            } catch (_: IOException) {
                continue
            }
        }
        return null
    }

    private fun pairingUrl(config: OAuthConfig, tv: String, nonce: String, challenge: String): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        return config.pageUrl +
            "?cid=${enc(config.clientId)}&cc=${enc(challenge)}&tv=${enc(tv)}&n=${enc(nonce)}"
    }

    private fun seconds(value: String?, default: Double): Long =
        ((value?.removeSuffix("s")?.toDoubleOrNull() ?: default) * 1000).toLong()

    private companion object {
        const val KEY_CONSENT = "privacy_consent_v1"
        const val NOT_CONFIGURED = "Credenziali Google non configurate: inseriscile dal telefono"
    }
}

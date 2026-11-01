package dev.armando.tvphotos

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.armando.tvphotos.auth.AuthExpiredException
import dev.armando.tvphotos.auth.GoogleAuth
import dev.armando.tvphotos.auth.PairingServer
import dev.armando.tvphotos.auth.Pkce
import dev.armando.tvphotos.auth.TokenStore
import dev.armando.tvphotos.data.LocalPhoto
import dev.armando.tvphotos.data.PhotoLibrary
import dev.armando.tvphotos.picker.PickerApi
import dev.armando.tvphotos.ui.NetworkUtils
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
    data class Pairing(val url: String?, val error: String?) : UiState
    data class Picking(val pickerUri: String?, val error: String?) : UiState
    data class Importing(val done: Int, val total: Int) : UiState
    data object Gallery : UiState
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val auth = GoogleAuth(http, TokenStore(app))
    private val picker = PickerApi(http, auth)
    private val library = PhotoLibrary(app)

    private val _state = MutableStateFlow<UiState>(UiState.Starting)
    val state: StateFlow<UiState> = _state

    val photos: StateFlow<List<LocalPhoto>> = library.photos

    private val _viewerIndex = MutableStateFlow<Int?>(null)
    val viewerIndex: StateFlow<Int?> = _viewerIndex

    private var job: Job? = null

    init {
        when {
            BuildConfig.GOOGLE_CLIENT_ID.isBlank() ->
                _state.value = UiState.Pairing(null, "Manca GOOGLE_CLIENT_ID in local.properties")
            !auth.isPaired -> startPairing()
            library.photos.value.isEmpty() -> startPicking()
            else -> _state.value = UiState.Gallery
        }
    }

    fun fileOf(photo: LocalPhoto): File = library.fileOf(photo)

    fun openViewer(index: Int) { _viewerIndex.value = index }

    fun closeViewer() { _viewerIndex.value = null }

    fun showGallery() {
        job?.cancel()
        _state.value = UiState.Gallery
    }

    fun clearLibrary() {
        library.clear()
        startPicking()
    }

    fun signOut() {
        job?.cancel()
        library.clear()
        auth.signOut()
        startPairing()
    }

    fun startPairing() {
        job?.cancel()
        job = viewModelScope.launch {
            val verifier = Pkce.randomUrlSafe(48)
            val nonce = Pkce.randomUrlSafe(16)
            val result = CompletableDeferred<Result<String>>()
            var server: PairingServer? = null
            try {
                _state.value = UiState.Pairing(null, null)
                val ip = NetworkUtils.localIpv4() ?: throw IOException("La TV non è connessa a una rete locale")
                server = startServer(nonce) { result.complete(it) }
                    ?: throw IOException("Nessuna porta locale disponibile")
                val tv = "$ip:${server.listeningPort}"
                _state.value = UiState.Pairing(pairingUrl(tv, nonce, Pkce.challenge(verifier)), null)

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

    fun startPicking() {
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
                library.import(picker, items) { done, total -> _state.value = UiState.Importing(done, total) }

                _state.value =
                    if (library.photos.value.isEmpty()) UiState.Picking(null, "Nessuna foto importata (i video non sono supportati)")
                    else UiState.Gallery
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

    private fun pairingUrl(tv: String, nonce: String, challenge: String): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        return BuildConfig.PAIRING_PAGE_URL +
            "?cid=${enc(BuildConfig.GOOGLE_CLIENT_ID)}&cc=${enc(challenge)}&tv=${enc(tv)}&n=${enc(nonce)}"
    }

    private fun seconds(value: String?, default: Double): Long =
        ((value?.removeSuffix("s")?.toDoubleOrNull() ?: default) * 1000).toLong()
}

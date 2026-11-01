package dev.armando.tvphotos.update

import android.app.Application
import dev.armando.tvphotos.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: ReleaseInfo) : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    data object Installing : UpdateState
    data class Failed(val message: String) : UpdateState
}

class UpdateManager(private val app: Application, private val scope: CoroutineScope) {
    private val prefs = app.getSharedPreferences("settings", Application.MODE_PRIVATE)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    private val _checkOnStartup = MutableStateFlow(prefs.getBoolean(KEY_CHECK_ON_STARTUP, false))
    val checkOnStartup: StateFlow<Boolean> = _checkOnStartup

    fun setCheckOnStartup(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CHECK_ON_STARTUP, enabled).apply()
        _checkOnStartup.value = enabled
    }

    /** Con [manual] = false (controllo all'avvio) gli errori di rete restano silenziosi. */
    fun check(manual: Boolean = true) {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        scope.launch {
            try {
                val release = withContext(Dispatchers.IO) { UpdateChecker.fetchLatest() }
                _state.value = when {
                    release != null && UpdateChecker.isNewer(release.tag, BuildConfig.VERSION_NAME) ->
                        UpdateState.Available(release)
                    manual -> UpdateState.UpToDate
                    else -> UpdateState.Idle
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = if (manual) UpdateState.Failed("Controllo non riuscito: ${e.message}") else UpdateState.Idle
            }
        }
    }

    fun downloadAndInstall() {
        val available = _state.value as? UpdateState.Available ?: return
        _state.value = UpdateState.Downloading(0f)
        scope.launch {
            try {
                val apk = UpdateInstaller.download(app, available.info) { _state.value = UpdateState.Downloading(it) }
                _state.value = UpdateState.Installing
                withContext(Dispatchers.IO) { UpdateInstaller.install(app, apk) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: UpdateException) {
                _state.value = UpdateState.Failed(messageFor(e.kind))
            } catch (e: Exception) {
                _state.value = UpdateState.Failed("Installazione non riuscita: ${e.message}")
            }
        }
    }

    private fun messageFor(kind: UpdateException.Kind) = when (kind) {
        UpdateException.Kind.DOWNLOAD -> "Download non riuscito"
        UpdateException.Kind.CHECKSUM -> "Controllo SHA-256 fallito: file non valido"
        UpdateException.Kind.SIGNATURE -> "La firma dell'aggiornamento non corrisponde all'app installata"
        UpdateException.Kind.INVALID_APK -> "Il file scaricato non è un aggiornamento valido"
        UpdateException.Kind.NOT_NEWER -> "L'aggiornamento non è più recente della versione installata"
        UpdateException.Kind.TOO_LARGE -> "Il file scaricato è troppo grande"
    }

    private companion object {
        const val KEY_CHECK_ON_STARTUP = "check_updates_on_startup"
    }
}

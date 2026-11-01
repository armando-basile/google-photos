package dev.armando.tvphotos.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.armando.tvphotos.BuildConfig
import dev.armando.tvphotos.auth.OAuthConfig
import dev.armando.tvphotos.update.UpdateManager
import dev.armando.tvphotos.update.UpdateState

@Composable
fun SettingsScreen(
    updates: UpdateManager,
    config: OAuthConfig,
    onConfigure: () -> Unit,
    onResetCredentials: () -> Unit,
    onClose: () -> Unit,
) {
    val state by updates.state.collectAsStateWithLifecycle()
    val onStartup by updates.checkOnStartup.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    val busy = state is UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.Installing

    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { first.requestFocus() }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(64.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Impostazioni", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(8.dp))
            Text("Versione installata: ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge)
            Text(statusText(state), style = MaterialTheme.typography.titleMedium)
            (state as? UpdateState.Available)?.info?.notes?.takeIf { it.isNotBlank() }?.let {
                Text(it.take(600), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = { updates.check(manual = true) },
                    enabled = !busy,
                    modifier = Modifier.focusRequester(first),
                ) { Text("Cerca aggiornamenti") }
                if (state is UpdateState.Available) {
                    Button(onClick = updates::downloadAndInstall) { Text("Scarica e installa") }
                }
                Button(onClick = { updates.setCheckOnStartup(!onStartup) }) {
                    Text("Controlla all'avvio: ${if (onStartup) "sì" else "no"}")
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    config.isCustom -> "Credenziali Google: personali (${config.clientIdHint})"
                    config.isConfigured -> "Credenziali Google: predefinite dell'app"
                    else -> "Credenziali Google: non configurate"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = onConfigure) { Text("Imposta credenziali dal telefono") }
                if (config.isCustom) Button(onClick = onResetCredentials) { Text("Ripristina predefinite") }
            }
            Button(onClick = onClose) { Text("Indietro") }
        }
    }
}

private fun statusText(state: UpdateState): String = when (state) {
    UpdateState.Idle -> ""
    UpdateState.Checking -> "Controllo in corso..."
    UpdateState.UpToDate -> "L'app è aggiornata."
    is UpdateState.Available -> "Disponibile la versione ${state.info.tag}."
    is UpdateState.Downloading -> "Download in corso: ${(state.progress * 100).toInt()}%"
    UpdateState.Installing -> "Conferma l'installazione nella schermata di sistema."
    is UpdateState.Failed -> state.message
}

package dev.armando.tvphotos.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.armando.tvphotos.AppViewModel
import dev.armando.tvphotos.UiState
import dev.armando.tvphotos.data.LocalPhoto
import dev.armando.tvphotos.update.UpdateState
import kotlinx.coroutines.delay

private const val SLIDESHOW_MS = 6000L

@Composable
fun AppRoot(vm: AppViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val photos by vm.photos.collectAsStateWithLifecycle()
    val viewer by vm.viewerIndex.collectAsStateWithLifecycle()
    val settingsOpen by vm.settingsOpen.collectAsStateWithLifecycle()
    val update by vm.updates.state.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (val s = state) {
            UiState.Starting -> CenterText("Avvio...")
            is UiState.Pairing -> PairingScreen(s, onRetry = vm::startPairing)
            is UiState.Picking -> PickingScreen(
                state = s,
                canCancel = photos.isNotEmpty(),
                onCancel = vm::showGallery,
                onRetry = vm::startPicking,
            )
            is UiState.Importing -> CenterText(
                if (s.total == 0) "Recupero delle foto selezionate..." else "Importazione ${s.done} / ${s.total}",
            )
            UiState.Gallery -> {
                GalleryScreen(
                    photos = photos,
                    fileOf = vm::fileOf,
                    onOpen = vm::openViewer,
                    onAdd = vm::startPicking,
                    onClear = vm::clearLibrary,
                    onSignOut = vm::signOut,
                    updateTag = (update as? UpdateState.Available)?.info?.tag,
                    onSettings = vm::openSettings,
                )
                viewer?.let { start ->
                    ViewerScreen(photos, start, vm::fileOf, onClose = vm::closeViewer)
                }
                if (settingsOpen) SettingsScreen(vm.updates, onClose = vm::closeSettings)
            }
        }
    }
}

@Composable
private fun CenterText(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable
private fun QrCode(text: String, size: Dp) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bitmap = remember(text, px) { QrEncoder.encode(text, px).asImageBitmap() }
    Image(
        bitmap = bitmap,
        contentDescription = "Codice QR",
        filterQuality = FilterQuality.None,
        modifier = Modifier.size(size + 24.dp).background(Color.White).padding(12.dp),
    )
}

@Composable
private fun QrScreen(title: String, steps: List<String>, qr: String?, error: String?, actions: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxSize().padding(64.dp),
        horizontalArrangement = Arrangement.spacedBy(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(title, style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(8.dp))
            steps.forEachIndexed { i, step -> Text("${i + 1}. $step", style = MaterialTheme.typography.titleLarge) }
            if (error != null) {
                Text(error, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { actions() }
        }
        Box(Modifier.width(384.dp), contentAlignment = Alignment.Center) {
            if (qr != null) QrCode(qr, 360.dp) else Text("...", style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable
private fun PairingScreen(state: UiState.Pairing, onRetry: () -> Unit) {
    QrScreen(
        title = "Collega Google Foto",
        steps = listOf(
            "Inquadra il codice con il telefono (stessa rete Wi-Fi della TV).",
            "Accedi con il tuo account Google e consenti l'accesso.",
            "Il telefono tornerà alla TV: il collegamento si completa da solo.",
        ),
        qr = state.url,
        error = state.error,
    ) {
        if (state.error != null) Button(onClick = onRetry) { Text("Riprova") }
    }
}

@Composable
private fun PickingScreen(state: UiState.Picking, canCancel: Boolean, onCancel: () -> Unit, onRetry: () -> Unit) {
    if (canCancel) BackHandler(onBack = onCancel)
    QrScreen(
        title = "Scegli le foto",
        steps = listOf(
            "Inquadra il codice con il telefono.",
            "In Google Foto seleziona foto o album, anche condivisi.",
            "Premi Fine sul telefono: le foto arriveranno sulla TV.",
        ),
        qr = state.pickerUri,
        error = state.error,
    ) {
        if (state.error != null) Button(onClick = onRetry) { Text("Riprova") }
        if (canCancel) Button(onClick = onCancel) { Text("Annulla") }
    }
}

@Composable
private fun GalleryScreen(
    photos: List<LocalPhoto>,
    fileOf: (LocalPhoto) -> java.io.File,
    onOpen: (Int) -> Unit,
    onAdd: () -> Unit,
    onClear: () -> Unit,
    onSignOut: () -> Unit,
    updateTag: String?,
    onSettings: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Le mie foto (${photos.size})",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onAdd) { Text("Aggiungi foto") }
            Button(onClick = onClear) { Text("Svuota") }
            Button(onClick = onSignOut) { Text("Scollega account") }
            Button(onClick = onSettings) {
                Text(if (updateTag != null) "Impostazioni (aggiornamento $updateTag)" else "Impostazioni")
            }
        }
        Spacer(Modifier.height(24.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(200.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(photos, key = { _, p -> p.id }) { index, photo ->
                Surface(
                    onClick = { onOpen(index) },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
                    modifier = Modifier.fillMaxWidth().height(150.dp),
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(fileOf(photo))
                            .size(400)
                            .crossfade(true)
                            .build(),
                        contentDescription = photo.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewerScreen(
    photos: List<LocalPhoto>,
    startIndex: Int,
    fileOf: (LocalPhoto) -> java.io.File,
    onClose: () -> Unit,
) {
    var index by remember { mutableIntStateOf(startIndex) }
    var playing by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val count = photos.size

    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(playing, count) {
        while (playing && count > 0) {
            delay(SLIDESHOW_MS)
            index = (index + 1) % count
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || count == 0) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionRight -> { index = (index + 1) % count; true }
                    Key.DirectionLeft -> { index = (index - 1 + count) % count; true }
                    Key.DirectionCenter, Key.Enter, Key.MediaPlayPause -> { playing = !playing; true }
                    else -> false
                }
            }
            .focusRequester(focus)
            .focusable(),
    ) {
        photos.getOrNull(index)?.let { photo ->
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(fileOf(photo)).size(1920, 1080).build(),
                contentDescription = photo.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            "${index + 1} / $count  ·  OK: ${if (playing) "ferma" else "avvia"} presentazione",
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
    }
}

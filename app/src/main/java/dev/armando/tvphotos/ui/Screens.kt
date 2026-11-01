package dev.armando.tvphotos.ui

import androidx.activity.compose.BackHandler
import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed as listItemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.armando.tvphotos.AppViewModel
import dev.armando.tvphotos.R
import dev.armando.tvphotos.SetupUi
import dev.armando.tvphotos.UiState
import dev.armando.tvphotos.data.LocalPhoto
import dev.armando.tvphotos.data.PhotoCollection
import dev.armando.tvphotos.update.UpdateState
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AppRoot(vm: AppViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val photos by vm.photos.collectAsStateWithLifecycle()
    val collections by vm.collections.collectAsStateWithLifecycle()
    val openId by vm.openCollection.collectAsStateWithLifecycle()
    val viewer by vm.viewerIndex.collectAsStateWithLifecycle()
    val settingsOpen by vm.settingsOpen.collectAsStateWithLifecycle()
    val update by vm.updates.state.collectAsStateWithLifecycle()
    val setup by vm.setup.collectAsStateWithLifecycle()
    val oauthConfig by vm.oauthConfig.config.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
      CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        when (val s = state) {
            UiState.Starting -> CenterText("Avvio...")
            UiState.Consent -> ConsentScreen(onAccept = vm::acceptConsent)
            UiState.ChooseFolder -> ChooseFolderScreen(
                collections = collections,
                photos = photos,
                suggestedName = remember { vm.suggestFolderName() },
                onExisting = vm::pickInto,
                onNew = vm::pickNewFolder,
                onCancel = vm::showGallery,
            )
            is UiState.Pairing -> PairingScreen(s, onRetry = vm::startPairing, onCredentials = vm::startSetup)
            is UiState.Picking -> PickingScreen(
                state = s,
                canCancel = photos.isNotEmpty(),
                onCancel = vm::showGallery,
                onRetry = vm::retryPicking,
            )
            is UiState.Importing -> CenterText(
                if (s.total == 0) "Recupero delle foto selezionate..." else "Importazione ${s.done} / ${s.total}",
            )
            UiState.Gallery -> {
                val open = collections.firstOrNull { it.id == openId }
                val openPhotos = if (open != null) photos.filter { it.collectionId == open.id } else emptyList()
                if (open == null) {
                    CollectionsScreen(
                        collections = collections,
                        photos = photos,
                        fileOf = vm::fileOf,
                        onOpen = vm::openCollection,
                        onSync = vm::syncCollection,
                        onAdd = vm::startPicking,
                        onClear = vm::clearLibrary,
                        onSignOut = vm::signOut,
                        updateTag = (update as? UpdateState.Available)?.info?.tag,
                        onSettings = vm::openSettings,
                    )
                } else {
                    CollectionScreen(
                        collection = open,
                        photos = openPhotos,
                        fileOf = vm::fileOf,
                        onOpenPhoto = vm::openViewer,
                        onBack = vm::closeCollection,
                        onSync = { vm.syncCollection(open.id) },
                        onRename = { vm.renameCollection(open.id, it) },
                        onDelete = { vm.deleteCollection(open.id) },
                    )
                }
                viewer?.let { start ->
                    if (open != null) {
                        ViewerScreen(openPhotos, start, vm::fileOf, onRotate = vm::rotatePhoto, onClose = vm::closeViewer)
                    }
                }
                if (settingsOpen) {
                    SettingsScreen(
                        updates = vm.updates,
                        config = oauthConfig,
                        onConfigure = vm::startSetup,
                        onResetCredentials = vm::resetCredentials,
                        onClose = vm::closeSettings,
                    )
                }
            }
        }
        setup?.let { SetupScreen(it, onRetry = vm::startSetup, onCancel = vm::cancelSetup) }
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
private fun PairingScreen(state: UiState.Pairing, onRetry: () -> Unit, onCredentials: () -> Unit) {
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
        Button(onClick = onCredentials) { Text("Usa le mie credenziali Google") }
    }
}

@Composable
private fun ConsentScreen(onAccept: () -> Unit) {
    val activity = LocalContext.current as? Activity
    val accept = remember { FocusRequester() }
    LaunchedEffect(Unit) { accept.requestFocus() }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.consent_title), style = MaterialTheme.typography.headlineMedium)
        listOf(
            R.string.consent_access,
            R.string.consent_storage,
            R.string.consent_sharing,
            R.string.consent_control,
        ).forEach { Text("\u2022  " + stringResource(it), style = MaterialTheme.typography.titleMedium) }
        Text(stringResource(R.string.consent_limited_use), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.consent_privacy), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = onAccept, modifier = Modifier.focusRequester(accept)) {
                Text(stringResource(R.string.consent_accept))
            }
            Button(onClick = { activity?.finish() }) { Text(stringResource(R.string.consent_exit)) }
        }
    }
}

@Composable
private fun ChooseFolderScreen(
    collections: List<PhotoCollection>,
    photos: List<LocalPhoto>,
    suggestedName: String,
    onExisting: (String) -> Unit,
    onNew: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val hasFolders = collections.isNotEmpty()
    if (hasFolders) BackHandler(onBack = onCancel)
    var name by remember { mutableStateOf(suggestedName) }
    val first = remember { FocusRequester() }
    val create = remember { FocusRequester() }
    LaunchedEffect(hasFolders) { (if (hasFolders) first else create).requestFocus() }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (hasFolders) "Dove vuoi mettere le foto?" else "Come vuoi chiamare la cartella?",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            if (hasFolders) "Scegli una cartella sulla TV oppure creane una nuova. Le foto già presenti nella cartella restano."
            else "Le foto che scegli verranno salvate in una cartella sulla TV con questo nome.",
            style = MaterialTheme.typography.bodyLarge,
        )
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listItemsIndexed(collections, key = { _, c -> c.id }) { i, c ->
                val count = photos.count { it.collectionId == c.id }
                Button(
                    onClick = { onExisting(c.id) },
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                ) { Text("${c.name} \u00b7 $count foto") }
            }
        }
        Text(if (hasFolders) "Nuova cartella" else "Nome della cartella", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            NameField(name, onChange = { name = it }, modifier = Modifier.weight(1f))
            Button(onClick = { onNew(name) }, modifier = Modifier.focusRequester(create)) {
                Text("Crea e scegli le foto")
            }
        }
        if (hasFolders) Button(onClick = onCancel) { Text("Annulla") }
    }
}

@Composable
private fun NameField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.onBackground
    BasicTextField(
        value = value,
        onValueChange = { onChange(it.take(60)) },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleMedium.copy(color = color),
        cursorBrush = SolidColor(color),
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .border(2.dp, if (focused) color else color.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(12.dp),
    )
}

@Composable
private fun SetupScreen(state: SetupUi, onRetry: () -> Unit, onCancel: () -> Unit) {
    BackHandler(onBack = onCancel)
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        QrScreen(
            title = "Credenziali Google personali",
            steps = listOf(
                "Inquadra il codice con il telefono (stessa rete Wi-Fi della TV).",
                "Incolla ID client e client secret del tuo progetto Google Cloud.",
                "Premi Salva: la TV ti chiederà di collegare l'account.",
            ),
            qr = state.url,
            error = state.error,
        ) {
            if (state.error != null) Button(onClick = onRetry) { Text("Riprova") }
            Button(onClick = onCancel) { Text("Annulla") }
        }
    }
}

@Composable
private fun PickingScreen(state: UiState.Picking, canCancel: Boolean, onCancel: () -> Unit, onRetry: () -> Unit) {
    if (canCancel) BackHandler(onBack = onCancel)
    QrScreen(
        title = "Scegli le foto",
        steps = listOf(
            "Inquadra il codice con il telefono.",
            "In Google Foto cerca il titolo dell'album (anche condiviso) e seleziona le foto: gli album non compaiono nell'elenco.",
            "Premi Fine sul telefono: le foto arriveranno sulla TV.",
        ),
        qr = state.pickerUri,
        error = state.error,
    ) {
        if (state.error != null) Button(onClick = onRetry) { Text("Riprova") }
        if (canCancel) Button(onClick = onCancel) { Text("Annulla") }
    }
}

private fun formatDate(millis: Long): String =
    SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(millis))

// L'API Picker non permette di sapere se un album è cambiato: si ricorda di risincronizzare dopo questo periodo.
private const val STALE_AFTER_DAYS = 30L
private const val STALE_AFTER_MS = STALE_AFTER_DAYS * 24 * 60 * 60 * 1000

@Composable
private fun CollectionsScreen(
    collections: List<PhotoCollection>,
    photos: List<LocalPhoto>,
    fileOf: (LocalPhoto) -> java.io.File,
    onOpen: (String) -> Unit,
    onSync: (String) -> Unit,
    onAdd: () -> Unit,
    onClear: () -> Unit,
    onSignOut: () -> Unit,
    updateTag: String?,
    onSettings: () -> Unit,
) {
    // Calcolato a ogni apertura dell'app e quando cambiano le raccolte.
    val stale = remember(collections) {
        val limit = System.currentTimeMillis() - STALE_AFTER_MS
        collections.filter { it.syncedAt < limit }.sortedBy { it.syncedAt }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Le mie foto (${photos.size} in ${collections.size} ${if (collections.size == 1) "cartella" else "cartelle"})",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onAdd) { Text("Aggiungi foto") }
            Button(onClick = onClear) { Text("Svuota la TV") }
            Button(onClick = onSignOut) { Text("Scollega account") }
            Button(onClick = onSettings) {
                Text(if (updateTag != null) "Impostazioni (aggiornamento $updateTag)" else "Impostazioni")
            }
        }
        if (stale.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    if (stale.size == 1) "1 cartella non sincronizzata da più di $STALE_AFTER_DAYS giorni: “${stale.first().name}”"
                    else "${stale.size} cartelle non sincronizzate da più di $STALE_AFTER_DAYS giorni",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { onSync(stale.first().id) }) { Text("Sincronizza") }
            }
        }
        Spacer(Modifier.height(24.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(280.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(collections, key = { it.id }) { collection ->
                val inCollection = photos.filter { it.collectionId == collection.id }
                Surface(
                    onClick = { onOpen(collection.id) },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                ) {
                    Box(Modifier.fillMaxSize()) {
                        inCollection.firstOrNull()?.let { cover ->
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current).data(fileOf(cover)).size(600).build(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Column(
                            Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.65f))
                                .padding(12.dp),
                        ) {
                            Text(collection.name, style = MaterialTheme.typography.titleMedium, color = Color.White)
                            Text(
                                "${inCollection.size} foto \u00b7 sincronizzata il ${formatDate(collection.syncedAt)}" +
                                    if (collection in stale) " \u00b7 da sincronizzare" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.8f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionScreen(
    collection: PhotoCollection,
    photos: List<LocalPhoto>,
    fileOf: (LocalPhoto) -> java.io.File,
    onOpenPhoto: (Int) -> Unit,
    onBack: () -> Unit,
    onSync: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember(collection.id) { mutableStateOf(false) }
    var renaming by remember(collection.id) { mutableStateOf(false) }
    var newName by remember(collection.id, renaming) { mutableStateOf(collection.name) }
    val nameFocus = remember { FocusRequester() }
    LaunchedEffect(renaming) { if (renaming) nameFocus.requestFocus() }
    BackHandler {
        when {
            renaming -> renaming = false
            confirmDelete -> confirmDelete = false
            else -> onBack()
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(collection.name, style = MaterialTheme.typography.headlineMedium)
                Text(
                    "${photos.size} foto \u00b7 ultima sincronizzazione ${formatDate(collection.syncedAt)}",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Button(onClick = onBack) { Text("Indietro") }
        }
        Spacer(Modifier.height(12.dp))
        if (renaming) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                NameField(newName, onChange = { newName = it }, modifier = Modifier.weight(1f).focusRequester(nameFocus))
                Button(onClick = { onRename(newName); renaming = false }) { Text("Salva nome") }
                Button(onClick = { renaming = false }) { Text("Annulla") }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = onSync) { Text("Sincronizza") }
                Button(onClick = { renaming = true }) { Text("Rinomina") }
                if (confirmDelete) {
                    Button(onClick = onDelete) { Text("Conferma: elimina dalla TV") }
                    Button(onClick = { confirmDelete = false }) { Text("Annulla") }
                } else {
                    Button(onClick = { confirmDelete = true }) { Text("Elimina dalla TV") }
                }
            }
        }
        Text(
            "Sincronizza: scegli di nuovo le foto o l'album in Google Foto. Le foto nuove vengono aggiunte, quelle non più selezionate rimosse. " +
                "L'eliminazione riguarda solo le copie sulla TV: su Google Foto non cambia nulla.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(16.dp))
        PhotoGrid(photos, fileOf, onOpenPhoto)
    }
}

@Composable
private fun PhotoGrid(photos: List<LocalPhoto>, fileOf: (LocalPhoto) -> java.io.File, onOpen: (Int) -> Unit) {
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

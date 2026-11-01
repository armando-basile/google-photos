package dev.armando.tvphotos.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.armando.tvphotos.data.LocalPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

private const val SLIDESHOW_MS = 6000L
private val ZOOM_LEVELS = listOf(1f, 2f, 3f)
private const val PAN_STEP = 0.2f

/** Immagine adattata allo schermo, già ruotata: fattore di scala extra per la rotazione e dimensioni risultanti. */
private class Geometry(val rotationFit: Float, val width: Float, val height: Float)

private fun geometry(dims: IntSize?, vw: Float, vh: Float, quarterTurn: Boolean): Geometry {
    if (dims == null || vw <= 0f || vh <= 0f) return Geometry(1f, vw, vh)
    val fit = minOf(vw / dims.width, vh / dims.height)
    val dw = dims.width * fit
    val dh = dims.height * fit
    if (!quarterTurn) return Geometry(1f, dw, dh)
    val extra = minOf(vw / dh, vh / dw)
    return Geometry(extra, dh * extra, dw * extra)
}

@Composable
internal fun ViewerScreen(
    photos: List<LocalPhoto>,
    startIndex: Int,
    fileOf: (LocalPhoto) -> File,
    onRotate: (LocalPhoto) -> Unit,
    onClose: () -> Unit,
) {
    var index by remember { mutableIntStateOf(startIndex) }
    var playing by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val imageFocus = remember { FocusRequester() }
    val barFocus = remember { FocusRequester() }
    val count = photos.size
    val photo = photos.getOrNull(index)
    val rotation = photo?.rotation ?: 0
    val zoomed = zoom > 1f

    val dims by produceState<IntSize?>(null, photo?.file) {
        value = null
        value = photo?.let { p ->
            withContext(Dispatchers.IO) {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(fileOf(p).path, o)
                if (o.outWidth > 0 && o.outHeight > 0) IntSize(o.outWidth, o.outHeight) else null
            }
        }
    }
    val vw = viewport.width.toFloat()
    val vh = viewport.height.toFloat()
    val geo = geometry(dims, vw, vh, quarterTurn = (rotation / 90) % 2 == 1)

    fun clampPan(p: Offset, z: Float): Offset {
        val mx = maxOf(0f, (geo.width * z - vw) / 2)
        val my = maxOf(0f, (geo.height * z - vh) / 2)
        return Offset(p.x.coerceIn(-mx, mx), p.y.coerceIn(-my, my))
    }

    fun resetView() {
        zoom = 1f
        pan = Offset.Zero
    }

    fun go(delta: Int) {
        if (count == 0) return
        index = (index + delta + count) % count
        resetView()
    }

    fun cycleZoom() {
        val next = ZOOM_LEVELS[(ZOOM_LEVELS.indexOf(zoom).coerceAtLeast(0) + 1) % ZOOM_LEVELS.size]
        zoom = next
        pan = clampPan(pan, next)
        playing = false
    }

    BackHandler { if (zoomed) resetView() else onClose() }
    LaunchedEffect(Unit) { imageFocus.requestFocus() }
    LaunchedEffect(playing, count) {
        while (playing && count > 0) {
            delay(SLIDESHOW_MS)
            go(1)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .onSizeChanged { viewport = it }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown || count == 0) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionRight -> {
                            if (zoomed) pan = clampPan(pan.copy(x = pan.x - vw * PAN_STEP), zoom) else go(1)
                            true
                        }
                        Key.DirectionLeft -> {
                            if (zoomed) pan = clampPan(pan.copy(x = pan.x + vw * PAN_STEP), zoom) else go(-1)
                            true
                        }
                        Key.DirectionUp -> {
                            if (zoomed) pan = clampPan(pan.copy(y = pan.y + vh * PAN_STEP), zoom)
                            zoomed
                        }
                        Key.DirectionDown -> {
                            if (zoomed) pan = clampPan(pan.copy(y = pan.y - vh * PAN_STEP), zoom) else barFocus.requestFocus()
                            true
                        }
                        Key.DirectionCenter, Key.Enter, Key.MediaPlayPause -> {
                            if (zoomed) resetView() else playing = !playing
                            true
                        }
                        else -> false
                    }
                }
                .focusRequester(imageFocus)
                .focusable(),
        ) {
            if (photo != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(fileOf(photo)).size(1920, 1080).build(),
                    contentDescription = photo.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        rotationZ = rotation.toFloat()
                        scaleX = geo.rotationFit * zoom
                        scaleY = geo.rotationFit * zoom
                        translationX = pan.x
                        translationY = pan.y
                    },
                )
            }
        }

        Text(
            "${index + 1} / $count" + if (zoomed) "  \u00b7  frecce: sposta  \u00b7  Indietro: esci dallo zoom" else "",
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = 0.75f),
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
        )

        // Giù dall'immagine porta ai comandi, Su dai comandi torna all'immagine.
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                .padding(8.dp)
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp) {
                        imageFocus.requestFocus()
                        true
                    } else {
                        false
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = {
                    photo?.let(onRotate)
                    resetView()
                },
                modifier = Modifier.focusRequester(barFocus),
            ) { Text("Ruota 90\u00b0") }
            Button(onClick = { cycleZoom() }) { Text("Zoom ${zoom.toInt()}x") }
            Button(onClick = {
                playing = !playing
                if (playing) resetView()
            }) { Text(if (playing) "Ferma presentazione" else "Presentazione") }
        }
    }
}

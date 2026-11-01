package dev.armando.tvphotos.data

import android.content.Context
import dev.armando.tvphotos.auth.AuthExpiredException
import dev.armando.tvphotos.picker.PickedItem
import dev.armando.tvphotos.picker.PickerApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

const val DEFAULT_COLLECTION = "default"

@Serializable
data class LocalPhoto(
    val id: String,
    val file: String,
    val name: String? = null,
    val createTime: String? = null,
    val collectionId: String = DEFAULT_COLLECTION,
    /** Rotazione oraria scelta dall'utente: 0, 90, 180 o 270 gradi. */
    val rotation: Int = 0,
)

/** Per data di scatto crescente; le foto senza data vanno in fondo. */
private fun List<LocalPhoto>.byDateAscending(): List<LocalPhoto> =
    sortedWith(compareBy<LocalPhoto>({ it.createTime == null }, { it.createTime }))

/** Una selezione fatta con il Picker: la si può sincronizzare di nuovo o eliminare. */
@Serializable
data class PhotoCollection(val id: String, val name: String, val syncedAt: Long)

@Serializable
private data class LibraryIndex(
    val collections: List<PhotoCollection> = emptyList(),
    val photos: List<LocalPhoto> = emptyList(),
)

class ImportResult(val collectionId: String, val added: Int, val removed: Int)

/** Le foto scelte vengono scaricate in locale: i baseUrl del Picker scadono dopo 60 minuti. */
class PhotoLibrary(context: Context) {
    private val dir = File(context.filesDir, "photos").apply { mkdirs() }
    private val indexFile = File(context.filesDir, "library.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val legacySerializer = ListSerializer(LocalPhoto.serializer())

    private val loaded = load()
    private val _photos = MutableStateFlow(loaded.photos)
    private val _collections = MutableStateFlow(loaded.collections)
    val photos: StateFlow<List<LocalPhoto>> = _photos
    val collections: StateFlow<List<PhotoCollection>> = _collections

    fun fileOf(photo: LocalPhoto): File = File(dir, photo.file)

    /**
     * Con [collectionId] nullo crea una nuova cartella chiamata [newName]; altrimenti importa in quella esistente.
     * Con [removeMissing] le foto della cartella non più presenti nella selezione vengono rimosse (sincronizzazione).
     */
    suspend fun import(
        api: PickerApi,
        items: List<PickedItem>,
        collectionId: String?,
        newName: String?,
        removeMissing: Boolean,
        onProgress: (done: Int, total: Int) -> Unit,
    ): ImportResult = withContext(Dispatchers.IO) {
        val id = collectionId ?: UUID.randomUUID().toString()
        val usable = items.filter { it.mediaFile != null && it.type != "VIDEO" }
        val existing = _photos.value.filter { it.collectionId == id }.mapTo(HashSet()) { it.id }
        val todo = usable.filter { it.id !in existing }
        var added = 0
        var removed = 0
        try {
            if (_collections.value.none { it.id == id }) {
                val name = newName?.trim()?.take(60)?.ifBlank { null } ?: defaultName()
                _collections.value = _collections.value + PhotoCollection(id, name, System.currentTimeMillis())
            }
            todo.forEachIndexed { i, item ->
                onProgress(i, todo.size)
                val photo = LocalPhoto(
                    id = item.id,
                    file = sha1("$id/${item.id}") + ".jpg",
                    name = item.mediaFile?.filename,
                    createTime = item.createTime,
                    collectionId = id,
                )
                runCatching { api.downloadImage(item.mediaFile!!.baseUrl, fileOf(photo)) }
                    .onSuccess {
                        _photos.value = (_photos.value + photo).byDateAscending()
                        added++
                        if (added % 10 == 0) save()
                    }
                    .onFailure { if (it is AuthExpiredException) throw it }
            }
            if (removeMissing && collectionId != null) {
                val keep = usable.mapTo(HashSet()) { it.id }
                val gone = _photos.value.filter { it.collectionId == id && it.id !in keep }
                gone.forEach { fileOf(it).delete() }
                _photos.value = _photos.value - gone.toSet()
                removed = gone.size
            }
            _collections.value = _collections.value.map { if (it.id == id) it.copy(syncedAt = System.currentTimeMillis()) else it }
        } finally {
            // Una raccolta nuova senza foto scaricate non va conservata.
            if (_photos.value.none { it.collectionId == id }) {
                _collections.value = _collections.value.filter { it.id != id }
            }
            save()
        }
        onProgress(todo.size, todo.size)
        ImportResult(id, added, removed)
    }

    /** Ruota di 90° in senso orario la foto con quel file e ricorda la scelta. */
    fun rotate(file: String) {
        _photos.value = _photos.value.map { if (it.file == file) it.copy(rotation = (it.rotation + 90) % 360) else it }
        save()
    }

    /** Un nome vuoto non cambia nulla; il nome è limitato a 60 caratteri. */
    fun renameCollection(id: String, name: String) {
        val clean = name.trim().take(60)
        if (clean.isEmpty()) return
        _collections.value = _collections.value.map { if (it.id == id) it.copy(name = clean) else it }
        save()
    }

    fun deleteCollection(id: String) {
        _photos.value.filter { it.collectionId == id }.forEach { fileOf(it).delete() }
        _photos.value = _photos.value.filter { it.collectionId != id }
        _collections.value = _collections.value.filter { it.id != id }
        save()
    }

    fun clear() {
        _photos.value = emptyList()
        _collections.value = emptyList()
        dir.listFiles()?.forEach { it.delete() }
        indexFile.delete()
    }

    private fun load(): LibraryIndex {
        val text = runCatching { indexFile.readText() }.getOrNull() ?: return LibraryIndex()
        val index = runCatching { json.decodeFromString<LibraryIndex>(text) }.getOrNull()
            // Formato precedente: elenco piatto di foto, raccolto in un'unica raccolta.
            ?: runCatching { json.decodeFromString(legacySerializer, text) }.getOrNull()?.let {
                LibraryIndex(listOf(PhotoCollection(DEFAULT_COLLECTION, "Foto importate", System.currentTimeMillis())), it)
            }
            ?: return LibraryIndex()
        val photos = index.photos.filter { File(dir, it.file).exists() }.byDateAscending()
        val collections = index.collections.filter { c -> photos.any { it.collectionId == c.id } }
        return LibraryIndex(collections, photos)
    }

    private fun save() {
        indexFile.writeText(json.encodeToString(LibraryIndex.serializer(), LibraryIndex(_collections.value, _photos.value)))
    }

    fun defaultName(): String =
        "Selezione del " + SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

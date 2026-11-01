package dev.armando.tvphotos.data

import android.content.Context
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

@Serializable
data class LocalPhoto(val id: String, val file: String, val name: String? = null, val createTime: String? = null)

/** Le foto scelte vengono scaricate in locale: i baseUrl del Picker scadono dopo 60 minuti. */
class PhotoLibrary(context: Context) {
    private val dir = File(context.filesDir, "photos").apply { mkdirs() }
    private val indexFile = File(context.filesDir, "library.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(LocalPhoto.serializer())

    private val _photos = MutableStateFlow(load())
    val photos: StateFlow<List<LocalPhoto>> = _photos

    fun fileOf(photo: LocalPhoto): File = File(dir, photo.file)

    suspend fun import(api: PickerApi, items: List<PickedItem>, onProgress: (done: Int, total: Int) -> Unit): Int =
        withContext(Dispatchers.IO) {
            val known = _photos.value.mapTo(HashSet()) { it.id }
            val todo = items.filter { it.mediaFile != null && it.type != "VIDEO" && it.id !in known }
            var added = 0
            todo.forEachIndexed { i, item ->
                onProgress(i, todo.size)
                val photo = LocalPhoto(
                    id = item.id,
                    file = sha1(item.id) + ".jpg",
                    name = item.mediaFile?.filename,
                    createTime = item.createTime,
                )
                runCatching { api.downloadImage(item.mediaFile!!.baseUrl, fileOf(photo)) }
                    .onSuccess {
                        _photos.value = (_photos.value + photo).sortedByDescending { it.createTime.orEmpty() }
                        added++
                        if (added % 10 == 0) save()
                    }
                    .onFailure { if (it is dev.armando.tvphotos.auth.AuthExpiredException) throw it }
            }
            save()
            onProgress(todo.size, todo.size)
            added
        }

    fun clear() {
        _photos.value = emptyList()
        dir.listFiles()?.forEach { it.delete() }
        indexFile.delete()
    }

    private fun load(): List<LocalPhoto> = runCatching {
        json.decodeFromString(serializer, indexFile.readText()).filter { File(dir, it.file).exists() }
    }.getOrDefault(emptyList())

    private fun save() {
        indexFile.writeText(json.encodeToString(serializer, _photos.value))
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

package dev.armando.tvphotos.update

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class ReleaseInfo(
    val tag: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    val checksumUrl: String,
)

/** Cerca l'ultima release pubblicata su GitHub e decide se è più recente della versione installata. */
object UpdateChecker {
    const val REPO = "armando-basile/google-photos"

    private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val MAX_REDIRECTS = 5
    private const val MAX_JSON_BYTES = 1L * 1024 * 1024
    private val VERSION_REGEX = Regex("^v?(\\d{1,4})(?:\\.(\\d{1,4}))?(?:\\.(\\d{1,4}))?")

    /** Restituisce null se non c'è ancora una release utilizzabile; gli errori di rete lanciano [IOException]. */
    fun fetchLatest(): ReleaseInfo? {
        val connection = open(LATEST_URL, "application/vnd.github+json")
        try {
            return when (val code = connection.responseCode) {
                200 -> parseRelease(String(readLimited(connection.inputStream, MAX_JSON_BYTES), Charsets.UTF_8))
                404 -> null
                else -> throw IOException("HTTP $code")
            }
        } finally {
            connection.disconnect()
        }
    }

    /** La release deve avere un APK e il relativo file `.sha256`, altrimenti non è installabile. */
    fun parseRelease(json: String): ReleaseInfo? {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            return null
        }
        val tag = root.optString("tag_name")
        if (parseVersion(tag) == null) return null

        val assets = root.optJSONArray("assets") ?: return null
        val byName = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
            .associateBy { it.optString("name") }
        val apk = byName.entries.firstOrNull { it.key.endsWith(".apk") } ?: return null
        val checksum = byName[apk.key + ".sha256"] ?: return null
        val apkUrl = apk.value.optString("browser_download_url")
        val checksumUrl = checksum.optString("browser_download_url")
        if (!isAllowedUrl(apkUrl) || !isAllowedUrl(checksumUrl)) return null

        return ReleaseInfo(
            tag = tag,
            notes = root.optString("body").trim(),
            apkUrl = apkUrl,
            apkSize = apk.value.optLong("size", -1L),
            checksumUrl = checksumUrl,
        )
    }

    fun parseVersion(value: String): List<Int>? {
        val match = VERSION_REGEX.find(value.trim()) ?: return null
        return (1..3).map { match.groupValues[it].ifEmpty { "0" }.toInt() }
    }

    fun isNewer(remote: String, installed: String): Boolean {
        val r = parseVersion(remote) ?: return false
        val i = parseVersion(installed) ?: return false
        for (n in 0..2) {
            if (r[n] != i[n]) return r[n] > i[n]
        }
        return false
    }

    fun isAllowedUrl(url: String): Boolean = runCatching {
        val parsed = URL(url)
        parsed.protocol == "https" && isAllowedHost(parsed.host)
    }.getOrDefault(false)

    fun isAllowedHost(host: String): Boolean {
        val h = host.lowercase()
        return h == "github.com" || h.endsWith(".github.com") ||
            h == "githubusercontent.com" || h.endsWith(".githubusercontent.com")
    }

    /** Segue i redirect a mano, così ogni passaggio è controllato rispetto all'elenco degli host GitHub. */
    fun open(url: String, accept: String): HttpURLConnection {
        var current = URL(url)
        repeat(MAX_REDIRECTS + 1) {
            if (current.protocol != "https" || !isAllowedHost(current.host)) throw IOException("blocked url")
            val connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "FotoTV-Updater")
                setRequestProperty("Accept", accept)
            }
            if (connection.responseCode in 300..399) {
                val location = connection.getHeaderField("Location") ?: throw IOException("redirect without location")
                connection.disconnect()
                current = URL(current, location)
            } else {
                return connection
            }
        }
        throw IOException("too many redirects")
    }

    fun readLimited(input: InputStream, limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        input.use {
            while (true) {
                val read = it.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) throw IOException("response too large")
                out.write(buffer, 0, read)
            }
        }
        return out.toByteArray()
    }
}

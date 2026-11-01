package dev.armando.tvphotos.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

class UpdateException(val kind: Kind) : Exception(kind.name) {
    enum class Kind { DOWNLOAD, CHECKSUM, SIGNATURE, INVALID_APK, NOT_NEWER, TOO_LARGE }
}

/** Scarica l'APK di una release, lo verifica e lo passa all'installer di sistema. */
object UpdateInstaller {
    private const val MAX_APK_BYTES = 150L * 1024 * 1024
    private val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}")

    suspend fun download(context: Context, info: ReleaseInfo, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "update.apk")
            try {
                val expected = fetchChecksum(info.checksumUrl)
                val actual = downloadTo(target, info, onProgress)
                if (!actual.equals(expected, ignoreCase = true)) throw UpdateException(UpdateException.Kind.CHECKSUM)
                verifyArchive(context, target)
                target
            } catch (e: Throwable) {
                target.delete()
                throw e
            }
        }

    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("update.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val callback = Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName)
                val pending = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    callback,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            installer.abandonSession(sessionId)
            throw e
        }
    }

    private fun fetchChecksum(url: String): String {
        val connection = UpdateChecker.open(url, "application/octet-stream")
        try {
            if (connection.responseCode != 200) throw UpdateException(UpdateException.Kind.DOWNLOAD)
            val text = String(UpdateChecker.readLimited(connection.inputStream, 4096), Charsets.UTF_8)
            return SHA256_REGEX.find(text.trim())?.value ?: throw UpdateException(UpdateException.Kind.CHECKSUM)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun downloadTo(target: File, info: ReleaseInfo, onProgress: (Float) -> Unit): String {
        val connection = UpdateChecker.open(info.apkUrl, "application/octet-stream")
        try {
            if (connection.responseCode != 200) throw UpdateException(UpdateException.Kind.DOWNLOAD)
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: info.apkSize
            if (total > MAX_APK_BYTES) throw UpdateException(UpdateException.Kind.TOO_LARGE)

            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var received = 0L
            var lastReported = -1
            connection.inputStream.use { input ->
                target.outputStream().use { out ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        received += read
                        if (received > MAX_APK_BYTES) throw UpdateException(UpdateException.Kind.TOO_LARGE)
                        digest.update(buffer, 0, read)
                        out.write(buffer, 0, read)
                        if (total > 0) {
                            val percent = (received * 100 / total).toInt().coerceIn(0, 100)
                            if (percent != lastReported) {
                                lastReported = percent
                                onProgress(percent / 100f)
                            }
                        }
                    }
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: IOException) {
            throw UpdateException(UpdateException.Kind.DOWNLOAD)
        } finally {
            connection.disconnect()
        }
    }

    /** Intercetta pacchetto sbagliato, versione vecchia o firma diversa prima che lo faccia Android. */
    private fun verifyArchive(context: Context, apk: File) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: throw UpdateException(UpdateException.Kind.INVALID_APK)
        if (archive.packageName != context.packageName) throw UpdateException(UpdateException.Kind.INVALID_APK)

        val installed = pm.getPackageInfo(context.packageName, flags)
        if (PackageInfoCompat.getLongVersionCode(archive) <= PackageInfoCompat.getLongVersionCode(installed)) {
            throw UpdateException(UpdateException.Kind.NOT_NEWER)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val newSigners = archive.signingInfo?.apkContentsSigners?.toSet()
            val oldSigners = installed.signingInfo?.apkContentsSigners?.toSet()
            if (newSigners != null && oldSigners != null && newSigners != oldSigners) {
                throw UpdateException(UpdateException.Kind.SIGNATURE)
            }
        }
    }
}

/** Riceve l'esito dell'installer: apre la conferma di sistema quando serve e segnala gli errori. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val reason = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                Toast.makeText(context, "Installazione non riuscita: $reason", Toast.LENGTH_LONG).show()
            }
        }
    }
}

package dev.armando.tvphotos.auth

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

object Pkce {
    private val random = SecureRandom()
    private const val FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

    fun randomUrlSafe(bytes: Int = 32): String {
        val buf = ByteArray(bytes)
        random.nextBytes(buf)
        return Base64.encodeToString(buf, FLAGS)
    }

    fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, FLAGS)
    }
}

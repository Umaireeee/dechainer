package io.github.warleysr.dechainer.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The recovery code is stored only as a salted hash, never as text: a copy of the app's files (a
 * backup, a rooted phone) no longer contains a code that can be typed in. The text has the form
 * `v1$iterations$salt$hash`, so the cost can be raised later without breaking old codes.
 */
internal object RecoveryCodeHash {
    private const val PREFIX = "v1"
    private const val ITERATIONS = 120_000
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256

    private fun derive(code: String, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(code.toCharArray(), salt, iterations, KEY_BITS)).encoded

    fun create(code: String, salt: ByteArray = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }): String {
        val enc = Base64.getEncoder().withoutPadding()
        return listOf(PREFIX, ITERATIONS.toString(), enc.encodeToString(salt), enc.encodeToString(derive(code, salt, ITERATIONS)))
            .joinToString("$")
    }

    /** True if [code] is the one [stored] was made from. False for anything that is not a valid stored hash. */
    fun verify(code: String, stored: String): Boolean = try {
        val parts = stored.split("$")
        if (parts.size != 4 || parts[0] != PREFIX) {
            false
        } else {
            val dec = Base64.getDecoder()
            val iterations = parts[1].toInt()
            val salt = dec.decode(parts[2])
            val expected = dec.decode(parts[3])
            iterations in 1..10_000_000 && MessageDigest.isEqual(derive(code, salt, iterations), expected)
        }
    } catch (_: Exception) {
        false
    }
}

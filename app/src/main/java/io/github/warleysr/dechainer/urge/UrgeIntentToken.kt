package io.github.warleysr.dechainer.urge

import java.security.SecureRandom

/**
 * Proof that an urge-start request came from this app's own Quick Settings tile. MainActivity is
 * exported (it is the launcher), so any app on the phone can send it an intent with an extra; without
 * this, a hostile app could start the ten-minute lock whenever it liked. The tile service issues a
 * token and puts it in the intent it sends; the activity accepts the request only if the token is the
 * one just issued, once, and fresh. The token lives in this process's memory only, which another app
 * cannot read. Pure: the time is passed in.
 */
class UrgeIntentToken(private val maxAgeMs: Long = MAX_AGE_MS) {
    private var token: String? = null
    private var issuedAt = 0L

    /** A new token, valid for [maxAgeMs] and for one use. Replaces any earlier one. */
    @Synchronized
    fun issue(now: Long): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }.also { token = it; issuedAt = now }
    }

    /** True once, for the token just issued and not yet too old. Every other value, a replay and a stale token all fail. */
    @Synchronized
    fun consume(candidate: String?, now: Long): Boolean {
        val expected = token
        token = null
        return expected != null && candidate != null && candidate == expected && now - issuedAt in 0..maxAgeMs
    }

    companion object {
        const val MAX_AGE_MS = 30_000L

        /** The one the tile and the activity share, inside this process. */
        val shared = UrgeIntentToken()
    }
}

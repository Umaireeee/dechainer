package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.security.RecoveryCodeHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryCodeHashTest {
    private val code = "ABCDEFGHIJKLMNOP"

    @Test
    fun theHashVerifiesTheRightCodeAndNothingElse() {
        val stored = RecoveryCodeHash.create(code)
        assertTrue(RecoveryCodeHash.verify(code, stored))
        assertFalse(RecoveryCodeHash.verify("ABCDEFGHIJKLMNOQ", stored))
        assertFalse(RecoveryCodeHash.verify("", stored))
    }

    @Test
    fun theStoredTextDoesNotContainTheCode() {
        assertFalse(RecoveryCodeHash.create(code).contains(code))
    }

    @Test
    fun everyHashHasItsOwnSalt() {
        assertNotEquals(RecoveryCodeHash.create(code), RecoveryCodeHash.create(code))
        // Same salt, same code: same hash (so a stored hash can be checked again later).
        val salt = ByteArray(16) { it.toByte() }
        assertEquals(RecoveryCodeHash.create(code, salt), RecoveryCodeHash.create(code, salt))
    }

    @Test
    fun anOldPlainTextCodeIsRecognisedAsNotAHashAndCanBeMigrated() {
        // What an older version stored: the code itself. It is not a hash, so it gets migrated on first read,
        // and the new hash accepts the same code.
        assertFalse(RecoveryCodeHash.isHash(code))
        val migrated = RecoveryCodeHash.create(code)
        assertTrue(RecoveryCodeHash.isHash(migrated))
        assertTrue(RecoveryCodeHash.verify(code, migrated))
    }

    @Test
    fun anythingThatIsNotAValidHashNeverVerifies() {
        assertFalse(RecoveryCodeHash.verify(code, code))
        assertFalse(RecoveryCodeHash.verify(code, "v1\$abc\$def\$ghi"))
        assertFalse(RecoveryCodeHash.verify(code, "v2\$1\$AAAA\$AAAA"))
        assertFalse(RecoveryCodeHash.verify(code, ""))
    }
}

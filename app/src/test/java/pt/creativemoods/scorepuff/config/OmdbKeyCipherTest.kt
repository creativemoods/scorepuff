// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.config

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import javax.crypto.KeyGenerator

class OmdbKeyCipherTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun roundTripUsesFreshCiphertextForEachWrite() {
        val key = key()
        val first = OmdbKeyCipher.encrypt("testSecret123", key)
        val second = OmdbKeyCipher.encrypt("testSecret123", key)
        assertNotEquals(first, second)
        assertFalse(first.contains("testSecret123"))
        assertEquals("testSecret123", OmdbKeyCipher.decrypt(first, key))
        assertEquals("testSecret123", OmdbKeyCipher.decrypt(second, key))
    }

    @Test fun wrongKeyCannotDecrypt() {
        val encrypted = OmdbKeyCipher.encrypt("testSecret123", key())
        assertThrows(Exception::class.java) { OmdbKeyCipher.decrypt(encrypted, key()) }
    }

    @Test fun tamperedCiphertextIsRejected() {
        val key = key()
        val parts = OmdbKeyCipher.encrypt("testSecret123", key).split('.')
        val bytes = Base64.getDecoder().decode(parts[1])
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        val damaged = parts[0] + "." + Base64.getEncoder().encodeToString(bytes)
        assertThrows(Exception::class.java) { OmdbKeyCipher.decrypt(damaged, key) }
    }
}

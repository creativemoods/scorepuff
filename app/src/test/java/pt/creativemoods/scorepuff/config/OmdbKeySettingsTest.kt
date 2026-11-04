// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.config

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class OmdbKeySettingsTest {
    private class Storage(var value: String = "") : OmdbKeyStorage {
        var failRead = false
        var failWrite = false
        var writes = 0
        override fun read(): String { if (failRead) throw IOException(); return value }
        override fun write(value: String) {
            if (failWrite) throw IOException()
            this.value = value
            writes++
        }
    }

    @Test fun savingPersistsTrimmedKeyAndPublishesOnlyPresence() {
        val storage = Storage()
        val settings = OmdbKeySettings(storage)
        assertFalse(settings.state.value.configured)
        settings.save("  testKey123  ")
        assertEquals("testKey123", storage.value)
        assertEquals(OmdbKeyState(true, 1), settings.state.value)
        assertEquals("testKey123", OmdbKeySettings(storage).credentials().key)
        assertFalse(settings.state.value.toString().contains("testKey123"))
        assertFalse(settings.credentials().toString().contains("testKey123"))
    }

    @Test fun replacementAndRemovalUpdateActiveCredential() {
        val storage = Storage("oldKey")
        val settings = OmdbKeySettings(storage)
        settings.save("newKey")
        assertEquals("newKey", settings.credentials().key)
        settings.save("")
        assertEquals("", storage.value)
        assertEquals("", settings.credentials().key)
        assertEquals(OmdbKeyState(false, 2), settings.state.value)
    }

    @Test fun savingSameKeyStillRequestsFreshSession() {
        val settings = OmdbKeySettings(Storage("sameKey"))
        settings.save("sameKey")
        assertEquals(1L, settings.credentials().revision)
    }

    @Test fun invalidInputsDoNotChangeStoredOrActiveKey() {
        val storage = Storage("validKey")
        val settings = OmdbKeySettings(storage)
        for (invalid in listOf("a b", "key&apikey=other", "x".repeat(129))) {
            assertThrows(IllegalArgumentException::class.java) { settings.save(invalid) }
        }
        assertEquals("validKey", settings.credentials().key)
        assertEquals(0, storage.writes)
        assertEquals(0L, settings.state.value.revision)
    }

    @Test fun failedWriteKeepsPreviousCredentialAndRevision() {
        val storage = Storage("oldKey").also { it.failWrite = true }
        val settings = OmdbKeySettings(storage)
        assertThrows(IOException::class.java) { settings.save("newKey") }
        assertEquals("oldKey", settings.credentials().key)
        assertEquals(OmdbKeyState(true), settings.state.value)
    }

    @Test fun unreadableStorageFailsClosedAndCanBeReplaced() {
        val storage = Storage().also { it.failRead = true }
        val settings = OmdbKeySettings(storage)
        assertEquals(OmdbKeyState(false, unreadable = true), settings.state.value)
        assertEquals("", settings.credentials().key)
        settings.save("replacement")
        assertEquals(OmdbKeyState(true, 1), settings.state.value)
    }
}

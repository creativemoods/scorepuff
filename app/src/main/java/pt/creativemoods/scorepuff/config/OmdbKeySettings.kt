// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.config

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

interface OmdbKeyStorage {
    fun read(): String
    fun write(value: String)
}

data class OmdbKeyState(val configured: Boolean, val revision: Long = 0, val unreadable: Boolean = false)

internal class OmdbCredentials(val key: String, val revision: Long) {
    override fun toString() = "OmdbCredentials(configured=${key.isNotBlank()}, revision=$revision)"
}

/** Publishes only key presence/revision. Plaintext is never placed in UI or saved-instance state. */
class OmdbKeySettings(private val storage: OmdbKeyStorage) {
    private var key = ""
    private val mutable = MutableStateFlow(OmdbKeyState(false))
    val state = mutable.asStateFlow()

    init {
        try {
            key = validate(storage.read())
            mutable.value = OmdbKeyState(key.isNotEmpty())
        } catch (_: Exception) {
            mutable.value = OmdbKeyState(false, unreadable = true)
        }
    }

    @Synchronized internal fun credentials() = OmdbCredentials(key, mutable.value.revision)

    /** Call off the main thread. Failed writes leave the active credential unchanged. */
    @Synchronized fun save(value: String) {
        val next = validate(value)
        storage.write(next)
        key = next
        mutable.value = OmdbKeyState(next.isNotEmpty(), mutable.value.revision + 1)
    }

    private fun validate(value: String): String = value.trim().also {
        require(it.length <= 128 && it.matches(Regex("[A-Za-z0-9]*"))) {
            "Use only letters and numbers, up to 128 characters."
        }
    }
}

// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.config

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Only ciphertext enters preferences; its encryption key stays in Android Keystore. */
class AndroidOmdbKeyStorage(context: Context) : OmdbKeyStorage {
    private val prefs = context.applicationContext.getSharedPreferences("omdb_credentials", Context.MODE_PRIVATE)
    private val alias = "pt.creativemoods.scorepuff.omdb"

    override fun read(): String {
        val payload = prefs.getString("encrypted_key", null) ?: return ""
        val key = keyStore().getKey(alias, null) as? SecretKey ?: throw IOException("Credential unavailable")
        return OmdbKeyCipher.decrypt(payload, key)
    }

    override fun write(value: String) {
        val editor = prefs.edit()
        if (value.isEmpty()) editor.remove("encrypted_key")
        else editor.putString("encrypted_key", OmdbKeyCipher.encrypt(value, encryptionKey()))
        if (!editor.commit()) throw IOException("Credential could not be saved")
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun encryptionKey(): SecretKey {
        (keyStore().getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return generator.generateKey()
    }
}

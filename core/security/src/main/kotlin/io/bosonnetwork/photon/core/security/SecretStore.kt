/*
 * Copyright (c) 2023 -      bosonnetwork.io
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.bosonnetwork.photon.core.security

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted-at-rest key/value store backed by EncryptedSharedPreferences with an Android Keystore
 * master key (StrongBox-backed where available). Used for the Director CWT and the wrapped 64-byte
 * private keys. Values are never written in cleartext and never logged (spec 4.8).
 */
class SecretStore(context: Context, fileName: String = FILE_NAME) {
    private val masterKey = MasterKey.Builder(context.applicationContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .setRequestStrongBoxBacked(true)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context.applicationContext,
        fileName,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun getString(key: String): String? = prefs.getString(key, null)

    /**
     * Writes (or removes, when null) a value. [commit] forces a synchronous flush to disk: use it when
     * the caller is about to end the process (e.g. seeding another profile before an app relaunch),
     * where the default async apply() could be dropped before it reaches disk.
     */
    fun putString(key: String, value: String?, commit: Boolean = false) {
        val editor = prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }
        if (commit) editor.commit() else editor.apply()
    }

    fun getBytes(key: String): ByteArray? =
        getString(key)?.let { Base64.decode(it, Base64.NO_WRAP) }

    fun putBytes(key: String, value: ByteArray?) = putBytes(key, value, commit = false)

    /**
     * Writes (or removes, when null) a byte value. [commit] forces a synchronous flush to disk: use it
     * when seeding another profile's store just before an app relaunch (see [putString]).
     */
    fun putBytes(key: String, value: ByteArray?, commit: Boolean) {
        putString(key, value?.let { Base64.encodeToString(it, Base64.NO_WRAP) }, commit)
    }

    fun remove(key: String, commit: Boolean = false) {
        val editor = prefs.edit().remove(key)
        if (commit) editor.commit() else editor.apply()
    }

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val FILE_NAME = "photon_secrets"
    }
}

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

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals small secrets (the identity's private keys) with a key that never leaves the Android Keystore.
 * A sealed value is text: the cipher's IV and the ciphertext with its tag.
 */
interface KeySealer {
    /** Seals [plain] under the Keystore key [alias], made on first use. */
    fun seal(alias: String, plain: ByteArray): String

    /** Opens what [seal] sealed under [alias]. */
    fun open(alias: String, sealed: String): ByteArray

    /** Deletes the Keystore key [alias]; what it sealed can't be opened any more. */
    fun delete(alias: String)
}

/**
 * The [KeySealer] of the Android Keystore: AES-256-GCM, in StrongBox where the phone has one. The key
 * needs no user authentication: Photon decrypts every message with the identity's key, in the
 * background too, so the key must open without the user; the Keystore still keeps it off the app's
 * storage and memory.
 */
class KeystoreSealer : KeySealer {
    private val keyStore: KeyStore by lazy { KeyStore.getInstance(KEYSTORE).apply { load(null) } }

    override fun seal(alias: String, plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(alias))
        val sealed = cipher.iv + cipher.doFinal(plain)
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    override fun open(alias: String, sealed: String): ByteArray {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        val key = keyStore.getKey(alias, null) as? SecretKey ?: throw IllegalStateException("No Keystore key $alias")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_SIZE))
        return cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE)
    }

    override fun delete(alias: String) {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    @Synchronized
    private fun key(alias: String): SecretKey {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generate(alias, strongBox = true)
            } catch (e: StrongBoxUnavailableException) {
                // No StrongBox on this phone: the Keystore's own hardware.
            }
        }
        return generate(alias, strongBox = false)
    }

    private fun generate(alias: String, strongBox: Boolean): SecretKey {
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) builder.setIsStrongBoxBacked(true)
        val spec = builder.build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}

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

package io.bosonnetwork.photon.feature.settings

import androidx.lifecycle.ViewModel
import io.bosonnetwork.photon.core.boson.BosonCrypto
import io.bosonnetwork.photon.core.boson.KeyManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Backs the hidden "Show identity key" screen (reached by tapping the Boson ID row 7 times): exposes
 * the user identity private key in base58 (64-byte libsodium form) so it can be backed up or imported
 * onto another device. This is the account-wide identity secret, distinct from the per-device device
 * key. Reads the key lazily and never logs it.
 */
@HiltViewModel
class ShowIdentityKeyViewModel @Inject constructor(
    private val keyManager: KeyManager,
) : ViewModel() {

    /** The user identity private key as base58, or null if this device has no identity key. */
    fun userKeyBase58(): String? = keyManager.userKeyPair()?.let {
        BosonCrypto.privateKey64ToBase58(BosonCrypto.privateKeyBytes64(it))
    }
}

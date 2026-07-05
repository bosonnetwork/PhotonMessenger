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

package io.photonmessenger.core.boson

import io.bosonnetwork.Id
import io.bosonnetwork.crypto.HybridTrustManager
import io.photonmessenger.core.network.DirectorConfig
import io.photonmessenger.core.network.DirectorTrustManagerProvider
import javax.net.ssl.X509TrustManager

/**
 * Pins the Director's HTTPS certificate to its Boson node identity, mirroring how the messaging and
 * ion-store clients pin their services (both wrap the same [HybridTrustManager] into their TLS
 * options).
 *
 * When [DirectorConfig.nodeId] is set, the Director's self-signed ECDSA certificate is accepted only
 * if it carries a valid Ed25519 identity binding for that node id (or, for a legacy Ed25519 leaf,
 * matches CN + raw key); a CA-signed certificate is delegated to the system trust store. Returns
 * null - i.e. default system-CA trust - when no node id is configured.
 */
class BosonDirectorTrustManagerProvider : DirectorTrustManagerProvider {
    override fun trustManagerFor(config: DirectorConfig): X509TrustManager? {
        val nodeId = config.nodeId?.takeIf { it.isNotBlank() } ?: return null
        val id = Id.of(nodeId)
        // expectedCn = node id (legacy Ed25519 path); expectedPublicKey = the 32-byte Ed25519 key the
        // Boson id encodes (modern ECDSA-cert identity-binding path).
        return HybridTrustManager(id.toString(), id.bytesUnsafe())
    }
}

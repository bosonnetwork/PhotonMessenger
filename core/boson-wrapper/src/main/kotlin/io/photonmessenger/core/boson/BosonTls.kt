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

import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider

/**
 * Installs BouncyCastle's JSSE provider so the Boson messaging client can complete its mqtts TLS
 * handshake on Android.
 *
 * Boson's messaging server presents a self-signed Ed25519 certificate over TLS 1.3 only. To finish
 * the handshake a client must advertise the `ed25519` signature scheme; Android's Conscrypt does not
 * (confirmed empirically - the same handshake also fails on LibreSSL/OpenSSL <= 1.1). BouncyCastle's
 * pure-Java JSSE stack does, so registering it at top priority makes Netty/Vert.x's
 * `SSLContext.getInstance("TLS")` resolve to BCJSSE.
 *
 * NOTE: this changes the JVM-wide default TLS provider for the process, so it also affects other TLS
 * (e.g. OkHttp/Retrofit). That is acceptable: BCJSSE is a standards-compliant TLS 1.3 implementation.
 * The crypto provider is supplied as a dedicated [BouncyCastleProvider] instance rather than being
 * registered globally, to avoid clashing with Android's built-in stripped-down "BC" provider.
 *
 * Must run before the first TLS connection (call from `Application.onCreate`). Idempotent.
 */
object BosonTls {
    @Volatile
    private var installed = false

    @Synchronized
    fun install() {
        if (installed)
            return

        Security.insertProviderAt(BouncyCastleJsseProvider(BouncyCastleProvider()), 1)
        installed = true
    }
}

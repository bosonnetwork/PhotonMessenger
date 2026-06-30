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

import io.photonmessenger.core.model.ServiceCoords
import io.bosonnetwork.Id
import io.bosonnetwork.ionstore.IonStore
import io.bosonnetwork.photonmessaging.Configuration
import io.bosonnetwork.photonmessaging.MessagingClient
import io.bosonnetwork.photonmessaging.MessagingStore
import io.vertx.core.Vertx
import java.nio.file.Path

/**
 * Constructs the MessagingClient and IonStore once sign-in keys and discovered service coordinates
 * exist (design spec sections 1.7, 4.4). No embedded DHT node is used on mobile: the client is
 * created with node == null and a fixed mqtts endpoint carried by the Configuration.
 *
 * Private keys are passed in the libsodium-style 64-byte form (seed || publicKey).
 *
 * NOTE: this M0 factory exists to prove the Boson types resolve and the construction contract
 * compiles against the local Maven artifacts. Key loading, lifecycle ownership, and listener
 * registration are wired in M1 (M1-13..M1-16).
 */
class BosonClientFactory(
    private val vertx: Vertx,
) {
    /**
     * Builds the messaging Configuration from discovered coordinates and the user/device keys, using
     * the supplied native persistence backend (Option A). No JDBC/SQL backend or database URI is used.
     */
    fun buildConfiguration(
        coords: ServiceCoords,
        userKey64: ByteArray,
        deviceKey64: ByteArray,
        dataDir: Path,
        store: MessagingStore,
    ): Configuration =
        Configuration.builder()
            .service(Id.of(coords.messagingPeerId), coords.messagingEndpoint)
            .userKey(userKey64)
            .deviceKey(deviceKey64)
            .dataDir(dataDir)
            .store(store)
            .build()

    /** Creates the MessagingClient with the shared Vertx and node == null (no embedded DHT). */
    fun createMessagingClient(config: Configuration): MessagingClient =
        MessagingClient.create(vertx, /* node = */ null, config)

    /** Builds the IonStore against the discovered ion-store service. */
    fun buildIonStore(
        coords: ServiceCoords,
        userId: String,
        deviceKey64: ByteArray,
    ): IonStore =
        IonStore.builder()
            .vertx(vertx)
            .userId(Id.of(userId))
            .deviceKey(deviceKey64)
            .servicePeerId(Id.of(coords.ionStorePeerId))
            .serviceUrl(coords.ionStoreUrl)
            .build()
}

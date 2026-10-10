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

package io.bosonnetwork.photon.core.boson

import io.bosonnetwork.photon.core.model.ServiceCoords
import io.bosonnetwork.Id
import io.bosonnetwork.ionstore.IonStore
import io.bosonnetwork.photonmessaging.Configuration
import io.bosonnetwork.photonmessaging.MessagingClient
import io.bosonnetwork.photonmessaging.MessagingStore
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.file.FileSystemOptions
import java.io.File
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Constructs the MessagingClient and IonStore once sign-in keys and discovered service coordinates
 * exist (design spec sections 1.7, 4.4). No embedded DHT node is used on mobile: the client is
 * created with node == null and a fixed mqtts endpoint carried by the Configuration.
 *
 * Private keys are passed in the libsodium-style 64-byte form (seed || publicKey).
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

    companion object {
        /**
         * Creates the shared [Vertx] runtime with Netty's async DNS resolver disabled, so hostname
         * resolution goes through the JDK and therefore Android's system resolver (getaddrinfo).
         *
         * Netty's own resolver is unusable on Android: it cannot discover the device's nameservers
         * (reading the net.dns* properties is blocked since Android 8), silently falls back to
         * Google Public DNS (which many networks block), and its strict DNS codec rejects the
         * lenient/malformed responses some home routers produce - every mqtts/ion-store connect
         * then fails with "Exceeded max queries per resolve" while OkHttp resolves the same name
         * fine. Always create client Vertx instances through this helper.
         */
        fun newVertx(): Vertx {
            System.setProperty("vertx.disableDnsResolver", "true")
            return Vertx.vertx(VertxOptions().setFileSystemOptions(fileSystemOptions()))
        }

        /**
         * Vert.x's file cache (where it copies classpath files it is asked to read), named exactly
         * `<tmpdir>/vertx-cache`; on Android the tmpdir is the app's cache directory. Vert.x deletes it
         * only on close or JVM shutdown, which Android never runs, and names it `vertx-cache-<uuid>` by
         * default (`-2`, `-3`... when the exact name is taken): each start left one behind. The ones a
         * previous run left are deleted here, once per process, before this run creates its own.
         */
        private fun fileSystemOptions(): FileSystemOptions {
            if (cacheCleaned.compareAndSet(false, true)) {
                val base = File(FileSystemOptions.DEFAULT_FILE_CACHING_DIR)
                base.parentFile
                    ?.listFiles { file -> file.name == base.name || file.name.startsWith(base.name + "-") }
                    ?.forEach { runCatching { it.deleteRecursively() } }
            }
            return FileSystemOptions().setFileCacheDirAsExactPath(true)
        }

        private val cacheCleaned = AtomicBoolean(false)
    }
}

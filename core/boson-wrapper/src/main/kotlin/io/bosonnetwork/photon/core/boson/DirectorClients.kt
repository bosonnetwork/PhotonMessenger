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

import io.bosonnetwork.Id
import io.bosonnetwork.crypto.Signature
import io.bosonnetwork.director.client.DirectorAuth
import io.bosonnetwork.director.client.DirectorClient
import io.bosonnetwork.photon.core.model.AppError
import io.bosonnetwork.photon.core.network.DirectorConfig
import io.bosonnetwork.photon.core.network.DirectorConfigStore
import io.vertx.core.Vertx
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

/**
 * The Director clients of the active profile, all built on the shared [Vertx] and the configured Director:
 *
 *  - [client] acts as the profile's own user, with the user key held in [KeyManager];
 *  - [clientFor] acts as a user whose key is only in memory (an identity being created or imported,
 *    not yet committed to a profile);
 *  - [auth] covers what comes before a key: OAuth sign-in, and a new device joining an account.
 *
 * Each client issues its own tokens from the key it acts with, so there is no session to keep fresh.
 * The shared clients are rebuilt when the Director config or the user key changes, and the ones they
 * replace are closed. The Director's node id, when configured, pins its self-signed certificate.
 */
class DirectorClients(
    private val vertx: Vertx,
    private val configStore: DirectorConfigStore,
    private val keyManager: KeyManager,
) {
    private val lock = Any()
    private var shared: Shared? = null
    private var sharedAuth: Pair<DirectorConfig, DirectorAuth>? = null

    private class Shared(val config: DirectorConfig, val userId: Id, val client: DirectorClient)

    /** The configured Director; emits again whenever it changes. */
    val config: Flow<DirectorConfig> = configStore.config.distinctUntilChanged()

    /**
     * The client acting as the profile's user.
     *
     * @throws AppError.Unauthorized if this profile holds no user key yet
     */
    suspend fun client(): DirectorClient {
        val cfg = configStore.config.first()
        val userKey = keyManager.userKeyPair()
            ?: throw AppError.Unauthorized("No identity on this device")
        val userId = BosonCrypto.idOf(userKey)

        val stale: DirectorClient?
        val client: DirectorClient
        synchronized(lock) {
            val current = shared
            if (current != null && current.config == cfg && current.userId == userId) return current.client
            stale = current?.client
            client = DirectorClient.builder().configure(cfg).userKey(userKey).build()
            shared = Shared(cfg, userId, client)
        }
        stale?.close()
        return client
    }

    /**
     * A client acting as the user of [userKey], optionally with [deviceKey] as its device (the initial
     * device of a registration). It is not shared: the caller closes it; see [withClient].
     */
    suspend fun clientFor(userKey: Signature.KeyPair, deviceKey: Signature.KeyPair? = null): DirectorClient {
        val builder = DirectorClient.builder().configure(configStore.config.first()).userKey(userKey)
        if (deviceKey != null) builder.deviceKey(deviceKey)
        return builder.build()
    }

    /** Runs [block] with a [clientFor] client, closing it afterwards. */
    suspend fun <T> withClient(
        userKey: Signature.KeyPair,
        deviceKey: Signature.KeyPair? = null,
        block: suspend (DirectorClient) -> T,
    ): T {
        val client = clientFor(userKey, deviceKey)
        try {
            return block(client)
        } finally {
            client.close()
        }
    }

    /** The client for signing in and for joining an account from a new device. */
    suspend fun auth(): DirectorAuth {
        val cfg = configStore.config.first()
        val stale: DirectorAuth?
        val auth: DirectorAuth
        synchronized(lock) {
            val current = sharedAuth
            if (current != null && current.first == cfg) return current.second
            stale = current?.second
            auth = DirectorAuth.builder().vertx(vertx).directorUrl(cfg.baseUrl).apply {
                cfg.nodeId?.let { nodeId(parseNodeId(it)) }
            }.build()
            sharedAuth = cfg to auth
        }
        stale?.close()
        return auth
    }

    private fun DirectorClient.Builder.configure(cfg: DirectorConfig): DirectorClient.Builder {
        vertx(vertx).directorUrl(cfg.baseUrl)
        cfg.nodeId?.let { nodeId(parseNodeId(it)) }
        return this
    }

    private fun parseNodeId(nodeId: String): Id =
        try {
            Id.of(nodeId)
        } catch (e: Exception) {
            throw AppError.InvalidInput("Invalid super node id", e)
        }
}

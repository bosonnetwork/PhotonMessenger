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

package io.bosonnetwork.photon.core.database

import android.content.Context
import io.bosonnetwork.photon.core.model.ChannelInviteStore
import io.bosonnetwork.photonmessaging.MessagingStore
import io.vertx.core.Vertx

/**
 * Both Room-backed stores that share a single [PhotonDatabase] instance (the same Room file). Exposes
 * only the [MessagingStore] and [ChannelInviteStore] contracts, keeping the `RoomDatabase` type
 * hierarchy encapsulated in this module.
 */
class RoomStores internal constructor(
    val messagingStore: MessagingStore,
    val channelInviteStore: ChannelInviteStore,
)

/**
 * Constructs the native Room-backed stores, keeping Room (and the `RoomDatabase` type hierarchy)
 * fully encapsulated in this module so consumers depend only on the store contracts.
 */
object MessagingStoreFactory {
    fun create(context: Context, vertx: Vertx): MessagingStore =
        RoomMessagingStore(vertx, PhotonDatabase.create(context))

    /** Named persistent backend (isolated on-disk store), used by the restart/persistence harness. */
    fun create(context: Context, vertx: Vertx, dbName: String): MessagingStore =
        RoomMessagingStore(vertx, PhotonDatabase.create(context, dbName))

    /** In-memory backend for tests/integration harnesses. */
    fun createInMemory(context: Context, vertx: Vertx): MessagingStore =
        RoomMessagingStore(vertx, PhotonDatabase.createInMemory(context))

    /** The messaging store and channel-invite store, sharing one on-disk Room database. */
    fun createStores(context: Context, vertx: Vertx): RoomStores {
        val db = PhotonDatabase.create(context)
        return RoomStores(RoomMessagingStore(vertx, db), RoomChannelInviteStore(db))
    }
}

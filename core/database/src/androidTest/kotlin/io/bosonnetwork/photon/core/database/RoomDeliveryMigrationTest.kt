/*
 * Copyright (c) 2026 -      bosonnetwork.io
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
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.bosonnetwork.Id
import io.bosonnetwork.photonmessaging.Contact
import io.bosonnetwork.photonmessaging.DeliveryStatus
import io.bosonnetwork.photonmessaging.MessagingStore
import io.bosonnetwork.photonmessaging.MessagingStore.StoredMessage
import io.vertx.core.Future
import io.vertx.core.Vertx
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomDeliveryMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var vertx: Vertx

    private fun <T> Future<T>.await(): T =
        toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)

    @Before
    fun setUp() {
        vertx = Vertx.vertx(io.vertx.core.VertxOptions().setFileSystemOptions(
            io.vertx.core.file.FileSystemOptions().setClassPathResolvingEnabled(false),
        ))
    }

    @After
    fun tearDown() {
        if (::vertx.isInitialized) vertx.close().await()
    }

    private fun legacyDatabase(name: String, seed: (SQLiteDatabase) -> Unit = {}) {
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            val assets = InstrumentationRegistry.getInstrumentation().context.assets
            val schema = assets.open("migrations/2.sql").bufferedReader().use { it.readText() }
            schema.split(';').filter { it.isNotBlank() }.forEach { db.execSQL(it) }
            seed(db)
            db.version = 2
        }
    }

    private fun insertContact(db: SQLiteDatabase, peer: Id) {
        db.execSQL("""
            INSERT INTO contacts (id, type, sessionKey, name, remark, tags, muted, blocked, revision, createdAt, updatedAt)
            VALUES (?, ?, NULL, 'Bob', NULL, NULL, 0, 0, 1, 100, 200)
        """.trimIndent(), arrayOf(peer.bytesUnsafe(), Contact.Type.FRIEND.value()))
    }

    private fun insertMessage(db: SQLiteDatabase, id: Id, peer: Id, rid: Long) {
        db.execSQL("""
            INSERT INTO messages (rid, id, conversationId, version, recipient, type, fromId, createdAt,
                contentType, contentDisposition, payload, sentAt, receivedAt)
            VALUES (?, ?, ?, 1, ?, 1, NULL, 100, 'text/plain', NULL, ?, 150, 200)
        """.trimIndent(), arrayOf(rid, id.bytesUnsafe(), peer.bytesUnsafe(), peer.bytesUnsafe(), "preserved".toByteArray()))
    }

    private fun withStore(name: String, check: (PhotonDatabase, MessagingStore) -> Unit) {
        val db = PhotonDatabase.create(context, name)
        try {
            val store = RoomMessagingStore(vertx, db)
            store.open().await()
            assertEquals(3, db.openHelper.writableDatabase.version)
            db.openHelper.writableDatabase.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'message_delivery_failures'",
            ).use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals(0, rows.getInt(0))
            }
            check(db, store)
        } finally {
            db.close()
        }
    }

    @Test
    fun versionTwoPreservesHistoryAndDefaultsToNoReceipt() {
        val name = "room-delivery-version-two-test.db"
        val peer = Id.random()
        val id = Id.random()
        legacyDatabase(name) { db ->
            insertContact(db, peer)
            insertMessage(db, id, peer, 31L)
        }
        try {
            withStore(name) { _, store ->
                val saved = store.getMessage(id).await()!!
                assertEquals(31L, saved.rid())
                assertEquals(peer, saved.conversationId())
                assertEquals(100L, saved.createdAt())
                assertEquals(150L, saved.sentAt())
                assertEquals(200L, saved.receivedAt())
                assertArrayEquals("preserved".toByteArray(), saved.payload())
                assertEquals(DeliveryStatus.UNDELIVERED, saved.deliveryStatus())
                assertNull(saved.deliveryReason())
                assertEquals(0L, saved.deliveryAt())
                assertEquals("Bob", store.getContact(peer).await()!!.name())
                assertEquals(id, store.getConversation(peer).await()!!.lastMessage()!!.id())
                assertEquals(peer, store.updateMessageDeliveryStatus(id, DeliveryStatus.DELIVERED, null, 456L).await())
                val fresh = StoredMessage(0L, Id.random(), peer, 1, peer, 1, null, 300L,
                    "text/plain", null, byteArrayOf(1), 0L, 300L, DeliveryStatus.UNDELIVERED, null, 0L)
                assertTrue(store.putMessage(fresh).await() > 31L)
            }
            withStore(name) { _, store ->
                val restored = store.getMessage(id).await()!!
                assertEquals(DeliveryStatus.DELIVERED, restored.deliveryStatus())
                assertEquals(456L, restored.deliveryAt())
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun emptyVersionTwoDatabaseMigratesWithoutCreatingMessages() {
        val name = "room-delivery-empty-version-two-test.db"
        legacyDatabase(name)
        try {
            withStore(name) { _, store ->
                assertTrue(store.getAllConversations().await().isEmpty())
                assertFalse(store.existsMessage(Id.random()).await())
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}

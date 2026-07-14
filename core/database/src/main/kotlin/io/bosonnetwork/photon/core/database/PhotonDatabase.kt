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
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import io.bosonnetwork.photon.core.database.entity.ChannelEntity
import io.bosonnetwork.photon.core.database.entity.ChannelMemberEntity
import io.bosonnetwork.photon.core.database.entity.ContactEntity
import io.bosonnetwork.photon.core.database.entity.ContactsRevisionEntity
import io.bosonnetwork.photon.core.database.entity.FriendRequestEntity
import io.bosonnetwork.photon.core.database.entity.MessageEntity

/** Room database backing the native Android implementation of the Boson `MessagingStore` (Option A). */
@Database(
    entities = [
        ContactsRevisionEntity::class,
        ContactEntity::class,
        ChannelEntity::class,
        ChannelMemberEntity::class,
        FriendRequestEntity::class,
        MessageEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class PhotonDatabase : RoomDatabase() {
    abstract fun messagingDao(): MessagingDao

    companion object {
        private const val DB_NAME = "photonmessaging-room.db"

        /** Builds the Room database; keeps Room construction inside this module. */
        fun create(context: Context): PhotonDatabase = create(context, DB_NAME)

        /**
         * Builds a named, persistent Room database. Lets a caller (e.g. an integration harness that
         * restarts a client, or a future multi-account split) keep isolated stores on disk.
         */
        fun create(context: Context, dbName: String): PhotonDatabase =
            Room.databaseBuilder(context.applicationContext, PhotonDatabase::class.java, dbName).build()

        /** In-memory database for tests/integration harnesses (no persistence across process death). */
        fun createInMemory(context: Context): PhotonDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, PhotonDatabase::class.java).build()
    }
}

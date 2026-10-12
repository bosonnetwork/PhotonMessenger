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

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * The home node of a profile's identity, through its Director.
 *
 * @property url the Director's base URL, without a trailing slash
 * @property nodeId the node id (Base58) that pins the Director's certificate when it is self-signed; null
 *   for a Director with a CA-issued certificate
 */
data class HomeNode(val url: String, val nodeId: String? = null)

/**
 * The identity's own profile as its home node last gave it.
 *
 * @property revision the node's profile revision it was read at
 * @property hasAvatar whether the profile has an avatar; the image itself is in the profile's [AvatarStore]
 */
data class OwnProfile(
    val name: String? = null,
    val bio: String? = null,
    val email: String? = null,
    val revision: Long = 0,
    val hasAvatar: Boolean = false,
)

/**
 * What a profile knows about its identity, apart from the keys: all of it public, so that any profile's
 * can be read (the accounts list) without opening its keys.
 *
 * @property userId the identity's id (Base58); null before the profile holds one
 * @property deviceId this device's id under the identity (Base58); null before it has a device key
 * @property homeNode the node the profile uses; set before an identity is, while onboarding chooses it
 * @property registeredNodeId the node this device key is registered with; null when it isn't
 * @property backupPending the identity was made here and its backup not yet checked
 * @property cardVersion the version of the profile card this device last published
 * @property profile the identity's profile as the home node last gave it; null before the first time
 */
data class IdentityInfo(
    val userId: String? = null,
    val deviceId: String? = null,
    val homeNode: HomeNode? = null,
    val registeredNodeId: String? = null,
    val backupPending: Boolean = false,
    val cardVersion: Int? = null,
    val profile: OwnProfile? = null,
)

/**
 * A profile's identity, as Boson Identity keeps one: a single record in the profile's own files, written
 * atomically, with the private keys sealed by the Android Keystore ([KeySealer], under [alias]).
 * Keys are given and taken in their 64-byte form (seed || public key). Opened keys are kept in memory,
 * as Photon uses them all the time.
 *
 * A write replaces the file atomically: a new file is written and synced, then renamed over the old one,
 * and the directory is synced, so a power cut leaves the old record or the new one, never a torn file.
 */
class IdentityVault(
    private val dir: File,
    private val alias: String,
    private val sealer: KeySealer,
) {
    private val file = File(dir, FILE_NAME)
    private var stored: Stored = read(file) ?: Stored()
    private var userKey: ByteArray? = null
    private var deviceKey: ByteArray? = null

    private val _info = MutableStateFlow(stored.info)

    /** The record, kept current. */
    val info: StateFlow<IdentityInfo> = _info.asStateFlow()

    /** Whether the record was ever written: a profile from before the vault has none yet. */
    fun exists(): Boolean = file.exists()

    /** The user key (64 bytes), or null when the profile holds no identity. */
    @Synchronized
    fun userKey(): ByteArray? {
        val sealed = stored.userKey ?: return null
        val key = userKey ?: sealer.open(alias, sealed).also { userKey = it }
        return key.copyOf()
    }

    /** This device's key (64 bytes), or null before it has one. */
    @Synchronized
    fun deviceKey(): ByteArray? {
        val sealed = stored.deviceKey ?: return null
        val key = deviceKey ?: sealer.open(alias, sealed).also { deviceKey = it }
        return key.copyOf()
    }

    /** Stores [key] (64 bytes) as the identity's key, [userId] being its id; null removes it. */
    @Synchronized
    fun setUserKey(key: ByteArray?, userId: String?) {
        write(stored.copy(userKey = key?.let { sealer.seal(alias, it) }, info = stored.info.copy(userId = userId.takeIf { key != null })))
        userKey = key?.copyOf()
    }

    /** Stores [key] (64 bytes) as this device's key, [deviceId] being its id; null removes it. */
    @Synchronized
    fun setDeviceKey(key: ByteArray?, deviceId: String?) {
        write(stored.copy(deviceKey = key?.let { sealer.seal(alias, it) }, info = stored.info.copy(deviceId = deviceId.takeIf { key != null })))
        deviceKey = key?.copyOf()
    }

    /** Changes the public part of the record. */
    @Synchronized
    fun update(change: (IdentityInfo) -> IdentityInfo) {
        val changed = change(stored.info)
        if (changed != stored.info || !file.exists()) write(stored.copy(info = changed))
    }

    /**
     * Writes a whole identity at once (a profile seeded before a relaunch, or migrated): [info] with its
     * keys. The file is synced before this returns.
     */
    @Synchronized
    fun store(info: IdentityInfo, userKey: ByteArray?, deviceKey: ByteArray?) {
        write(Stored(info, userKey?.let { sealer.seal(alias, it) }, deviceKey?.let { sealer.seal(alias, it) }))
        this.userKey = userKey?.copyOf()
        this.deviceKey = deviceKey?.copyOf()
    }

    /** Forgets the identity: its keys and what goes with them. The home node stays. */
    @Synchronized
    fun clear() {
        write(Stored(IdentityInfo(homeNode = stored.info.homeNode)))
        userKey = null
        deviceKey = null
    }

    private fun write(next: Stored) {
        dir.mkdirs()
        val tmp = File(dir, "$FILE_NAME.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(encode(next).toString().toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) throw IOException("Could not replace the identity record")
        syncDirectory(dir)
        stored = next
        _info.value = next.info
    }

    private data class Stored(
        val info: IdentityInfo = IdentityInfo(),
        val userKey: String? = null,
        val deviceKey: String? = null,
    )

    companion object {
        const val FILE_NAME = "identity.json"
        private const val VERSION = 1

        /** The Keystore alias of the profile [profileId]'s keys. */
        fun aliasFor(profileId: String): String = "photon.identity.$profileId"

        /** The public part of the record in [dir] (another profile's), or null when it has none. */
        fun readInfo(dir: File): IdentityInfo? = read(File(dir, FILE_NAME))?.info

        // Makes a rename durable. Opening a directory works on Linux (and so Android); where it does not,
        // the rename is still atomic, only not yet flushed.
        internal fun syncDirectory(dir: File) {
            try {
                FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
            } catch (e: Exception) {
                // Best effort.
            }
        }

        private fun read(file: File): Stored? {
            if (!file.exists()) return null
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val node = json.optJSONObject("homeNode")?.let { HomeNode(it.getString("url"), it.optStringOrNull("nodeId")) }
            val info = IdentityInfo(
                userId = json.optStringOrNull("userId"),
                deviceId = json.optStringOrNull("deviceId"),
                homeNode = node,
                registeredNodeId = json.optStringOrNull("registeredNodeId"),
                backupPending = json.optBoolean("backupPending", false),
                cardVersion = if (json.has("cardVersion")) json.getInt("cardVersion") else null,
                profile = json.optJSONObject("profile")?.let {
                    OwnProfile(
                        name = it.optStringOrNull("name"),
                        bio = it.optStringOrNull("bio"),
                        email = it.optStringOrNull("email"),
                        revision = it.optLong("revision", 0),
                        hasAvatar = it.optBoolean("hasAvatar", false),
                    )
                },
            )
            return Stored(info, json.optStringOrNull("userKey"), json.optStringOrNull("deviceKey"))
        }

        private fun encode(stored: Stored): JSONObject {
            val info = stored.info
            return JSONObject()
                .put("version", VERSION)
                .putOpt("userId", info.userId)
                .putOpt("deviceId", info.deviceId)
                .putOpt("homeNode", info.homeNode?.let { JSONObject().put("url", it.url).putOpt("nodeId", it.nodeId) })
                .putOpt("registeredNodeId", info.registeredNodeId)
                .put("backupPending", info.backupPending)
                .putOpt("cardVersion", info.cardVersion)
                .putOpt("profile", info.profile?.let {
                    JSONObject().putOpt("name", it.name).putOpt("bio", it.bio).putOpt("email", it.email)
                        .put("revision", it.revision).put("hasAvatar", it.hasAvatar)
                })
                .putOpt("userKey", stored.userKey)
                .putOpt("deviceKey", stored.deviceKey)
        }

        private fun JSONObject.optStringOrNull(name: String): String? =
            if (has(name) && !isNull(name)) getString(name) else null
    }
}

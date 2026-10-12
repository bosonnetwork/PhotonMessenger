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

import android.content.Context
import io.bosonnetwork.Id
import io.bosonnetwork.crypto.KeyBackup
import io.bosonnetwork.director.client.PairingCode
import io.bosonnetwork.director.client.SuperNodeCode

/**
 * What a scanned or pasted code is, in the forms of Boson's string registry (core/api/docs/formats.md).
 * Each screen takes the kinds it can use and tells the user what anything else is, rather than only
 * that it is invalid: a node code scanned where a contact is expected, a code that belongs to Boson
 * Identity, a private key.
 */
sealed interface ScannedCode {
    /** A super node: `boson:supernode:1`, or the JSON form read before it. */
    data class SuperNode(val code: SuperNodeCode) : ScannedCode

    /** A new device asking to be paired: `boson:pair:1`, or the form read before it. */
    data class Pairing(val code: PairingCode) : ScannedCode

    /** A user's id: `did:boson:`, Base58 or hex. */
    data class UserId(val id: Id) : ScannedCode

    /** A user key's backup: Base58, hex or the key file. A secret: the caller reads it again itself. */
    data object PrivateKey : ScannedCode

    /** A Boson Identity code: administrator enrollment (`enroll`), moving to a new phone (`move`), web sign-in (`signin`). */
    data class ForBosonIdentity(val namespace: String) : ScannedCode

    /** A Boson string Photon has no use for. */
    data class OtherBosonCode(val namespace: String) : ScannedCode

    /** Anything else. */
    data object Unknown : ScannedCode

    companion object {
        private val NAMESPACE = Regex("^boson:([a-z][a-z0-9-]*):")
        private val FOR_BOSON_IDENTITY = setOf("enroll", "move", "signin")

        /** What [text] is; never throws. */
        fun classify(text: String): ScannedCode {
            val value = text.trim()
            if (value.isEmpty())
                return Unknown

            NAMESPACE.find(value)?.let { match ->
                return when (val namespace = match.groupValues[1]) {
                    "supernode" -> superNode(value)
                    "pair" -> pairing(value)
                    in FOR_BOSON_IDENTITY -> ForBosonIdentity(namespace)
                    else -> OtherBosonCode(namespace)
                }
            }
            if (value.startsWith("bosonpair:"))
                return pairing(value)
            if (value.startsWith("{"))
                return superNode(value)

            // A key (64 bytes) is longer than an id (32), so the two can't be taken for each other.
            if (runCatching { KeyBackup.parse(value) }.isSuccess)
                return PrivateKey
            return runCatching { UserId(Id.of(value)) }.getOrDefault(Unknown)
        }

        private fun superNode(value: String): ScannedCode =
            SuperNodeCode.parse(value).map<ScannedCode> { SuperNode(it) }.orElse(Unknown)

        private fun pairing(value: String): ScannedCode =
            runCatching { Pairing(PairingCode.parse(value)) }.getOrDefault(Unknown)
    }
}

/**
 * What to tell the user when [this] code was scanned where a different kind is expected: what it is,
 * and where it belongs.
 */
fun ScannedCode.misplacedMessage(context: Context): String = context.getString(
    when (this) {
        is ScannedCode.SuperNode -> R.string.bw_code_super_node
        is ScannedCode.Pairing -> R.string.bw_code_pairing
        is ScannedCode.UserId -> R.string.bw_code_user_id
        ScannedCode.PrivateKey -> R.string.bw_code_private_key
        is ScannedCode.ForBosonIdentity -> when (namespace) {
            "enroll" -> R.string.bw_code_enroll
            "move" -> R.string.bw_code_move
            else -> R.string.bw_code_sign_in
        }
        is ScannedCode.OtherBosonCode -> R.string.bw_code_other
        ScannedCode.Unknown -> R.string.bw_code_unknown
    }
)

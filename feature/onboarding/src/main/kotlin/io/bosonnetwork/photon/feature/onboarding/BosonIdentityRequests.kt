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

package io.bosonnetwork.photon.feature.onboarding

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContract
import io.bosonnetwork.director.client.SuperNodeCode

/**
 * Boson Identity's app requests, as Photon asks them: an identity's home node, and approving this device
 * from its pairing code. The API is Boson Identity's (its `docs/app-requests.md`): an explicit intent to
 * its package, started for a result; only public data comes back, and no key ever crosses the intent
 * (an approval hands the user key over sealed, through the node, as with a scanned code).
 */
object BosonIdentityRequests {
    const val PACKAGE = "io.bosonnetwork.identity"

    private const val ACTION_GET_HOME_NODE = "io.bosonnetwork.identity.action.GET_HOME_NODE"
    private const val ACTION_APPROVE_DEVICE = "io.bosonnetwork.identity.action.APPROVE_DEVICE"
    private const val EXTRA_USER_ID = "io.bosonnetwork.identity.extra.USER_ID"
    private const val EXTRA_HOME_NODE = "io.bosonnetwork.identity.extra.HOME_NODE"
    private const val EXTRA_PAIRING_CODE = "io.bosonnetwork.identity.extra.PAIRING_CODE"
    private const val EXTRA_ERROR = "io.bosonnetwork.identity.extra.ERROR"

    const val ERROR_CANCELED = "canceled"
    const val ERROR_INVALID_REQUEST = "invalid_request"
    const val ERROR_NO_IDENTITY = "no_identity"
    const val ERROR_NO_HOME_NODE = "no_home_node"
    const val ERROR_DENIED = "denied"
    const val ERROR_EXPIRED = "expired"

    /** What Boson Identity answered. */
    sealed interface Answer {
        /** The identity the user chose, and its home node. */
        data class HomeNode(val userId: String, val node: SuperNodeCode) : Answer

        /** The user approved this device for the identity; the node's answer completes the pairing. */
        data class Approved(val userId: String?) : Answer

        /** No answer: one of the `ERROR_` values (or a newer one this app doesn't know). */
        data class Refused(val reason: String) : Answer
    }

    /**
     * Whether Boson Identity on this phone answers both requests: installed, and new enough. An older
     * one simply isn't offered.
     */
    fun isAvailable(context: Context): Boolean {
        val pm = context.packageManager
        return listOf(ACTION_GET_HOME_NODE, ACTION_APPROVE_DEVICE).all { action ->
            pm.resolveActivity(Intent(action).setPackage(PACKAGE), PackageManager.MATCH_DEFAULT_ONLY) != null
        }
    }

    /** Asks for an identity's home node; [userId] proposes one. */
    class GetHomeNode : ActivityResultContract<String?, Answer>() {
        override fun createIntent(context: Context, input: String?): Intent =
            Intent(ACTION_GET_HOME_NODE).setPackage(PACKAGE).apply { input?.let { putExtra(EXTRA_USER_ID, it) } }

        override fun parseResult(resultCode: Int, intent: Intent?): Answer {
            if (resultCode != Activity.RESULT_OK) return refused(intent)
            val userId = intent?.getStringExtra(EXTRA_USER_ID)
            val node = intent?.getStringExtra(EXTRA_HOME_NODE)?.let { SuperNodeCode.parse(it).orElse(null) }
            return if (userId != null && node != null) Answer.HomeNode(userId, node) else Answer.Refused(ERROR_INVALID_REQUEST)
        }
    }

    /** What [ApproveDevice] asks: this device's pairing code, for the identity [userId] if known. */
    data class Approval(val pairingCode: String, val userId: String?)

    /** Asks to approve this device from its pairing code. */
    class ApproveDevice : ActivityResultContract<Approval, Answer>() {
        override fun createIntent(context: Context, input: Approval): Intent =
            Intent(ACTION_APPROVE_DEVICE).setPackage(PACKAGE)
                .putExtra(EXTRA_PAIRING_CODE, input.pairingCode)
                .apply { input.userId?.let { putExtra(EXTRA_USER_ID, it) } }

        override fun parseResult(resultCode: Int, intent: Intent?): Answer =
            if (resultCode == Activity.RESULT_OK) Answer.Approved(intent?.getStringExtra(EXTRA_USER_ID)) else refused(intent)
    }

    // Leaving Boson Identity without its answer (Back, or it was closed) is a cancel.
    private fun refused(intent: Intent?): Answer =
        Answer.Refused(intent?.getStringExtra(EXTRA_ERROR) ?: ERROR_CANCELED)
}

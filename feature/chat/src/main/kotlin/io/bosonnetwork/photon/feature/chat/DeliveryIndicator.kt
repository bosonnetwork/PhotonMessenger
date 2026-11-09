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

package io.bosonnetwork.photon.feature.chat

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import io.bosonnetwork.photon.feature.chat.model.MessageStatus
import io.bosonnetwork.photon.feature.chat.model.UiDeliveryReceipt
import io.bosonnetwork.photonmessaging.DeliveryStatus

/** Service acceptance has the ordinary metadata tint; it is not a read receipt. */
@Composable
internal fun DeliveryIndicator(
    status: MessageStatus,
    receipt: UiDeliveryReceipt?,
    isChannel: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val icon = when (receipt?.status) {
        null -> when (status) {
            MessageStatus.SENDING -> Icons.Filled.Schedule
            MessageStatus.SENT -> Icons.Filled.Check
            MessageStatus.FAILED -> Icons.Filled.ErrorOutline
        }
        DeliveryStatus.DELIVERED -> Icons.Filled.DoneAll
        DeliveryStatus.EXPIRED -> Icons.Filled.WarningAmber
        else -> Icons.Filled.ErrorOutline
    }
    val description = when (receipt?.status) {
        null -> when (status) {
            MessageStatus.SENDING -> R.string.chat_status_sending
            MessageStatus.SENT -> R.string.chat_status_sent
            MessageStatus.FAILED -> R.string.chat_status_failed
        }
        DeliveryStatus.DELIVERED -> if (isChannel) R.string.chat_status_delivered_channel else R.string.chat_status_delivered
        else -> receipt.status.failureTextRes()!!
    }
    Icon(icon, contentDescription = stringResource(description), modifier = modifier, tint = tint)
}

/** Raw service reason strings stay out of user-visible text. */
@StringRes
internal fun DeliveryStatus.failureTextRes(): Int? = when (this) {
    DeliveryStatus.UNDELIVERED -> R.string.chat_delivery_failed
    DeliveryStatus.DELIVERED -> null
    DeliveryStatus.RECIPIENT_UNRESOLVED -> R.string.chat_delivery_recipient_unresolved
    DeliveryStatus.RECIPIENT_UNKNOWN -> R.string.chat_delivery_recipient_unknown
    DeliveryStatus.NOT_FEDERATING -> R.string.chat_delivery_not_federating
    DeliveryStatus.FEDERATION_REFUSED -> R.string.chat_delivery_federation_refused
    DeliveryStatus.PEER_UNRESOLVED -> R.string.chat_delivery_peer_unresolved
    DeliveryStatus.PEER_UNVERIFIED -> R.string.chat_delivery_peer_unverified
    DeliveryStatus.EXPIRED -> R.string.chat_delivery_expired
    DeliveryStatus.INTERNAL_ERROR -> R.string.chat_delivery_internal_error
}

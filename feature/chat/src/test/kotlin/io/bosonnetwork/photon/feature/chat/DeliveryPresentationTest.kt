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

import io.bosonnetwork.photon.feature.chat.model.MessageStatus
import io.bosonnetwork.photon.feature.chat.model.UiDeliveryReceipt
import io.bosonnetwork.photon.feature.chat.model.UiMessage
import io.bosonnetwork.photonmessaging.DeliveryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryPresentationTest {
    @Test
    fun `only a local failed submission offers retry`() {
        val message = UiMessage("message", "hello", true, 1L, status = MessageStatus.FAILED)
        assertTrue(message.canRetrySubmission)
        assertFalse(message.isSubmitting)
        assertFalse(message.copy(fromMe = false).canRetrySubmission)
        assertTrue(message.copy(status = MessageStatus.SENDING).isSubmitting)
        assertFalse(message.copy(status = MessageStatus.SENDING).canRetrySubmission)
        assertFalse(message.copy(status = MessageStatus.SENT).canRetrySubmission)
        for (status in DeliveryStatus.entries) {
            val receipt = UiDeliveryReceipt(status, "server details", 123L)
            assertFalse(message.copy(deliveryReceipt = receipt).canRetrySubmission)
            assertFalse(message.copy(status = MessageStatus.SENDING, deliveryReceipt = receipt).isSubmitting)
        }
    }

    @Test
    fun `every terminal failure has localized text and expiry never claims non-delivery`() {
        for (status in DeliveryStatus.entries) {
            if (status == DeliveryStatus.DELIVERED) assertNull(status.failureTextRes())
            else assertNotNull(status.failureTextRes())
        }
        assertEquals(R.string.chat_delivery_expired, DeliveryStatus.EXPIRED.failureTextRes())
    }
}

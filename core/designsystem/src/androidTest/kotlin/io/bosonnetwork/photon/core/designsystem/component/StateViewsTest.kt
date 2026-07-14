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

package io.bosonnetwork.photon.core.designsystem.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Compose UI tests for the shared state placeholders (X-T4). These are the empty/loading/error/
 * content renderers used by every feature screen, so covering them here validates the state look
 * once for the whole app. Run on a device/emulator (D1 gate).
 */
class StateViewsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyState_showsTheGivenText() {
        composeRule.setContent { EmptyState(text = "No conversations yet") }
        composeRule.onNodeWithText("No conversations yet").assertIsDisplayed()
    }

    @Test
    fun emptyState_withIcon_stillShowsTheText() {
        composeRule.setContent { EmptyState(text = "Nothing here", icon = Icons.Filled.Info) }
        composeRule.onNodeWithText("Nothing here").assertIsDisplayed()
    }

    @Test
    fun errorState_withoutRetry_showsMessageAndNoRetryButton() {
        composeRule.setContent { ErrorState(message = "Something broke") }
        composeRule.onNodeWithText("Something broke").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test
    fun errorState_withRetry_invokesCallbackOnTap() {
        var retried = false
        composeRule.setContent { ErrorState(message = "Can't reach the server", onRetry = { retried = true }) }
        composeRule.onNodeWithText("Retry").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        assertTrue(retried)
    }
}

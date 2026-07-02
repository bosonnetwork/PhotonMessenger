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

package io.photonmessenger.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Constrains content to a comfortable reading width and centers it on wide screens (X-A3). On a phone
 * the content fills the width unchanged; on a tablet or unfolded device it stops stretching edge to
 * edge (long list rows and text lines are hard to scan), matching Material's large-screen guidance
 * without a full list-detail refactor.
 *
 * This deliberately avoids the material3-window-size-class dependency: it reacts to the actual space
 * the composable is given (via [BoxWithConstraints]), so it also behaves correctly inside split-screen
 * and foldable postures, not just at the top level.
 */
@Composable
fun ResponsiveContent(
    modifier: Modifier = Modifier,
    maxContentWidth: Dp = 640.dp,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val constrained = maxWidth > maxContentWidth
        Box(
            modifier = Modifier
                .then(if (constrained) Modifier.widthIn(max = maxContentWidth) else Modifier.fillMaxWidth())
                .fillMaxSize()
                .align(Alignment.TopCenter),
        ) {
            content()
        }
    }
}

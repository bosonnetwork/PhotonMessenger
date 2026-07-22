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

package io.bosonnetwork.photon.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import io.bosonnetwork.photon.feature.settings.R

/**
 * Shows THIS device's device key as a QR (and text). Gated behind an explicit reveal tap with a clear
 * security warning: the device key authorizes this device on the messaging service, so anyone who
 * captures it can connect as this device. Unlike the identity key, a leaked device key can be revoked
 * by removing this device from the Devices list. Reached from the Devices screen's current-device row.
 */
@Composable
fun ShowKeyScreen(
    onBack: () -> Unit,
    viewModel: ShowKeyViewModel = hiltViewModel(),
) {
    val keyBase58 = remember { viewModel.deviceKeyBase58() }
    RevealKeyScaffold(
        title = stringResource(R.string.settings_device_key_title),
        warning = stringResource(R.string.settings_device_key_warning),
        keyBase58 = keyBase58,
        qrContentDescription = stringResource(R.string.settings_device_key_qr_content_description),
        emptyMessage = stringResource(R.string.settings_device_key_empty),
        onBack = onBack,
    )
}

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

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.bosonnetwork.photon.core.designsystem.component.EmptyState
import io.bosonnetwork.photon.core.designsystem.component.ResponsiveContent
import io.bosonnetwork.photon.core.qr.rememberQrBitmap

/**
 * Shows THIS device's device key as a QR (and text). Gated behind an explicit reveal tap with a clear
 * security warning: the device key authorizes this device on the messaging service, so anyone who
 * captures it can connect as this device. Unlike the identity key, a leaked device key can be revoked
 * by removing this device from the Devices list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShowKeyScreen(
    onBack: () -> Unit,
    viewModel: ShowKeyViewModel = hiltViewModel(),
) {
    val keyBase58 = remember { viewModel.deviceKeyBase58() }
    var revealed by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Show device key") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        ResponsiveContent(modifier = Modifier.padding(padding)) {
            if (keyBase58 == null) {
                EmptyState("No device key on this device")
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "This is this device's key. It authorizes this device to connect to the " +
                            "messaging service and receive your messages - it is not your identity key. " +
                            "Anyone who captures this QR or text can connect as this device and read the " +
                            "messages delivered to it. Unlike your identity, a leaked device key can be " +
                            "revoked: remove this device from your Devices list to cut it off. Only reveal " +
                            "it on a device you own, in a private place.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(24.dp))
                    if (!revealed) {
                        Button(onClick = { revealed = true }) {
                            Text("Reveal key")
                        }
                    } else {
                        rememberQrBitmap(keyBase58)?.let { qr ->
                            Image(
                                bitmap = qr,
                                contentDescription = "Device key QR",
                                modifier = Modifier.size(260.dp),
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        SelectionContainer {
                            Text(
                                text = keyBase58,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

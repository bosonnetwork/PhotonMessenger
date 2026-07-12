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

package io.photonmessenger.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import io.photonmessenger.feature.chat.model.UiAttachment
import kotlin.math.abs

/**
 * Full-screen image viewer shown over the chat. Supports pinch-zoom + pan, double-tap to toggle zoom,
 * tap to toggle the chrome, and (at natural size) a vertical drag to dismiss. The overflow menu offers
 * Save / Share / Forward. [model] is the resolved image (a cache [java.io.File], inline bytes, or a
 * local content-uri string); it is null while a remote image is still downloading, in which case a
 * spinner is shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerDialog(
    attachment: UiAttachment,
    model: Any?,
    onDismiss: () -> Unit,
    onSaveAs: () -> Unit,
    onShare: () -> Unit,
    onForward: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var chromeVisible by remember { mutableStateOf(true) }
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        var dragY by remember { mutableFloatStateOf(0f) }

        val dismissThreshold = with(androidx.compose.ui.platform.LocalDensity.current) { 140.dp.toPx() }
        // Fade the black scrim as the image is dragged away, so a swipe-down reads as a dismissal.
        val scrimAlpha = (1f - (abs(dragY) / (dismissThreshold * 3f))).coerceIn(0.4f, 1f)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrimAlpha)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                model == null -> CircularProgressIndicator(color = Color.White)
                else -> AsyncImage(
                    model = model,
                    contentDescription = attachment.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = if (scale > 1f) offset.x else 0f
                            translationY = if (scale > 1f) offset.y else dragY
                        }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val next = (scale * zoom).coerceIn(1f, 4f)
                                scale = next
                                if (next > 1f) {
                                    offset += pan
                                } else {
                                    offset = Offset.Zero
                                    dragY += pan.y
                                    if (abs(dragY) > dismissThreshold) onDismiss()
                                }
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { chromeVisible = !chromeVisible },
                                onDoubleTap = {
                                    if (scale > 1f) {
                                        scale = 1f
                                        offset = Offset.Zero
                                    } else {
                                        scale = 2f
                                    }
                                    dragY = 0f
                                },
                            )
                        },
                )
            }

            AnimatedVisibility(
                visible = chromeVisible,
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                var menuOpen by remember { mutableStateOf(false) }
                val iconColors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
                TopAppBar(
                    title = {
                        Text(
                            attachment.name,
                            color = Color.White,
                            maxLines = 1,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss, colors = iconColors) {
                            Icon(Icons.Filled.Close, contentDescription = "Close")
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { menuOpen = true }, colors = iconColors) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Save") },
                                    leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                                    onClick = { menuOpen = false; onSaveAs() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Share") },
                                    leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                                    onClick = { menuOpen = false; onShare() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Forward") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Forward, contentDescription = null) },
                                    onClick = { menuOpen = false; onForward() },
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    modifier = Modifier.fillMaxWidth().padding(0.dp),
                )
            }
        }
    }
}

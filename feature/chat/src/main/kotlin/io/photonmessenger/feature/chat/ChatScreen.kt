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

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.photonmessenger.core.designsystem.component.ResponsiveContent
import io.photonmessenger.feature.chat.model.AttachmentKind
import io.photonmessenger.feature.chat.model.AttachmentSource
import io.photonmessenger.feature.chat.model.MessageStatus
import io.photonmessenger.feature.chat.model.UiAttachment
import io.photonmessenger.feature.chat.model.UiMessage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenChannelDetail: (String) -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val header by viewModel.header.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val loadingOlder by viewModel.loadingOlder.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var draft by remember { mutableStateOf("") }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.sendAttachment(uri.toString())
    }

    LaunchedEffect(Unit) { viewModel.errors.collect { snackbar.showSnackbar(it) } }

    // Failed text sends offer an inline retry (M3-8).
    LaunchedEffect(Unit) {
        viewModel.sendFailures.collect { failure ->
            val result = snackbar.showSnackbar(
                message = failure.message,
                actionLabel = "Retry",
                withDismissAction = true,
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.retrySend(failure)
        }
    }

    // Load older history when the user scrolls to the top (M3-5).
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { index -> if (index == 0) viewModel.loadOlder() }
    }

    // Auto-scroll to the newest message only when one is appended (not when older pages prepend).
    val newestId = state.messages.lastOrNull()?.id
    LaunchedEffect(newestId) {
        if (newestId != null) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            val openDetail = { onOpenChannelDetail(viewModel.conversationId) }
            TopAppBar(
                title = {
                    val titleModifier = if (header.isChannel) Modifier.clickable(onClick = openDetail) else Modifier
                    Column(modifier = titleModifier) {
                        Text(header.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        header.subtitle?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (header.isChannel) {
                        IconButton(onClick = openDetail) {
                            Icon(Icons.Filled.Groups, contentDescription = "Channel members")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            MessageInput(
                value = draft,
                onValueChange = { draft = it },
                onSend = {
                    viewModel.send(draft)
                    draft = ""
                },
                onAttach = { pickMedia.launch("*/*") },
            )
        },
    ) { padding ->
        ResponsiveContent(modifier = Modifier.padding(padding)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (loadingOlder) {
                    item(key = "loading-older") {
                        Box(modifier = Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        }
                    }
                }
                items(state.messages, key = { it.id }) { msg ->
                    val download = msg.attachment?.let { att ->
                        (att.source as? AttachmentSource.Remote)?.let { downloads[it.contentId] }
                    }
                    MessageBubble(
                        message = msg,
                        download = download,
                        onDownload = { viewModel.download(it) },
                        onRetry = { viewModel.retrySend(SendFailure("", it.text, it.id)) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: UiMessage,
    download: AttachmentDownload?,
    onDownload: (UiAttachment) -> Unit,
    onRetry: (UiMessage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val alignment = if (message.fromMe) Alignment.End else Alignment.Start
    val bubbleAlignment = if (message.fromMe) Alignment.CenterEnd else Alignment.CenterStart
    val failed = message.status == MessageStatus.FAILED
    val baseColor = if (message.fromMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val color = if (failed) MaterialTheme.colorScheme.errorContainer else baseColor
    val onColor = when {
        failed -> MaterialTheme.colorScheme.onErrorContainer
        message.fromMe -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    // A sending bubble is slightly muted until it is confirmed on the live stream (M3-4).
    val bubbleModifier = Modifier
        .widthIn(max = 280.dp)
        .then(if (message.status == MessageStatus.SENDING) Modifier.alpha(0.7f) else Modifier)
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = bubbleAlignment) {
            Surface(
                color = color,
                shape = RoundedCornerShape(16.dp),
                modifier = bubbleModifier,
            ) {
                if (message.attachment != null) {
                    AttachmentContent(message.attachment, download, onColor, onDownload)
                } else {
                    Text(
                        text = message.text,
                        color = onColor,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
        MessageStatusLabel(message, onRetry)
    }
}

/** Shows delivery state under outgoing bubbles: a "Sending" hint, or a tap-to-retry failure (M3-4). */
@Composable
private fun MessageStatusLabel(message: UiMessage, onRetry: (UiMessage) -> Unit) {
    if (!message.fromMe) return
    when (message.status) {
        MessageStatus.SENDING -> Text(
            text = "Sending...",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )
        MessageStatus.FAILED -> Text(
            text = "Not delivered. Tap to retry",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .clickable { onRetry(message) }
                .padding(horizontal = 12.dp, vertical = 2.dp),
        )
        MessageStatus.SENT -> Unit
    }
}

@Composable
private fun AttachmentContent(
    attachment: UiAttachment,
    download: AttachmentDownload?,
    onColor: androidx.compose.ui.graphics.Color,
    onDownload: (UiAttachment) -> Unit,
) {
    val source = attachment.source
    when {
        attachment.kind == AttachmentKind.IMAGE && source is AttachmentSource.Inline ->
            AttachmentImage(model = source.bytes, attachment = attachment)

        attachment.kind == AttachmentKind.IMAGE && source is AttachmentSource.Remote -> {
            // Auto-fetch remote images so they render in place.
            LaunchedEffect(source.contentId) { onDownload(attachment) }
            when (download) {
                is AttachmentDownload.Ready -> AttachmentImage(model = download.file, attachment = attachment)
                is AttachmentDownload.Failed -> FileChip(attachment, onColor, "Tap to retry") { onDownload(attachment) }
                else -> ImagePlaceholder(attachment)
            }
        }

        else -> {
            val label = when (download) {
                is AttachmentDownload.Loading -> "Downloading..."
                is AttachmentDownload.Ready -> "Saved to cache"
                is AttachmentDownload.Failed -> "Tap to retry"
                else -> formatSize(attachment.size)
            }
            FileChip(attachment, onColor, label) {
                if (source is AttachmentSource.Remote) onDownload(attachment)
            }
        }
    }
}

@Composable
private fun AttachmentImage(model: Any, attachment: UiAttachment) {
    val ratio = if (attachment.width != null && attachment.height != null && attachment.height > 0)
        attachment.width.toFloat() / attachment.height else 1f
    AsyncImage(
        model = model,
        contentDescription = attachment.name,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .widthIn(max = 240.dp)
            .aspectRatio(ratio.coerceIn(0.5f, 2f)),
    )
}

@Composable
private fun ImagePlaceholder(attachment: UiAttachment) {
    Box(
        modifier = Modifier.size(160.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun FileChip(
    attachment: UiAttachment,
    onColor: androidx.compose.ui.graphics.Color,
    secondary: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, tint = onColor)
        Column(modifier = Modifier.widthIn(max = 200.dp)) {
            Text(attachment.name, color = onColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(secondary, color = onColor, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
private fun MessageInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
) {
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onAttach) {
                Icon(Icons.Default.AttachFile, contentDescription = "Attach")
            }
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message") },
                maxLines = 4,
            )
            IconButton(onClick = onSend, enabled = value.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

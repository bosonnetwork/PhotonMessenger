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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.photonmessenger.core.designsystem.component.PhotonAvatar
import io.photonmessenger.core.designsystem.component.ResponsiveContent
import io.photonmessenger.core.designsystem.component.formatBubbleTime
import io.photonmessenger.core.designsystem.component.formatDayHeader
import io.photonmessenger.core.designsystem.component.identityColor
import io.photonmessenger.core.designsystem.component.sameDay
import io.photonmessenger.feature.chat.model.AttachmentKind
import io.photonmessenger.feature.chat.model.AttachmentSource
import io.photonmessenger.feature.chat.model.MessageStatus
import io.photonmessenger.feature.chat.model.UiAttachment
import io.photonmessenger.feature.chat.model.UiMessage
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenChannelDetail: (String) -> Unit = {},
    onOpenContactDetail: (String) -> Unit = {},
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
            .collect { index -> if (index == 0 && state.messages.isNotEmpty()) viewModel.loadOlder() }
    }

    // Follow new messages only when the user is already reading the newest ones; never yank
    // someone who scrolled up into history back to the bottom.
    val nearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount == 0 || last >= info.totalItemsCount - 3
        }
    }
    val newestId = state.messages.lastOrNull()?.id
    val newestFromMe = state.messages.lastOrNull()?.fromMe == true
    LaunchedEffect(newestId) {
        if (newestId != null && (nearBottom || newestFromMe) && state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    val topBarScroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.imePadding().nestedScroll(topBarScroll.nestedScrollConnection),
        topBar = {
            val openDetail = {
                if (header.isChannel) onOpenChannelDetail(viewModel.conversationId)
                else onOpenContactDetail(viewModel.conversationId)
            }
            TopAppBar(
                scrollBehavior = topBarScroll,
                title = {
                    Row(
                        modifier = Modifier.clickable(onClick = openDetail),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PhotonAvatar(
                            model = header.avatarUrl,
                            name = header.title,
                            colorKey = viewModel.conversationId,
                            isChannel = header.isChannel,
                            size = 38.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                header.title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            header.subtitle?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            // Subtle tint behind the message list separates the conversation canvas from the chrome.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (loadingOlder) {
                        item(key = "loading-older") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                    itemsIndexed(state.messages, key = { _, msg -> msg.id }) { index, msg ->
                        val prev = state.messages.getOrNull(index - 1)
                        val download = msg.attachment?.let { att ->
                            (att.source as? AttachmentSource.Remote)?.let { downloads[it.contentId] }
                        }
                        Column(modifier = Modifier.animateItem()) {
                            if (prev == null || !sameDay(prev.createdAt, msg.createdAt)) {
                                DayHeader(msg.createdAt)
                            }
                            MessageBubble(
                                message = msg,
                                isChannel = header.isChannel,
                                showSender = msg.senderName != null &&
                                    (prev == null || prev.senderId != msg.senderId ||
                                        !sameDay(prev.createdAt, msg.createdAt)),
                                download = download,
                                onDownload = { viewModel.download(it) },
                                onRetry = { viewModel.retrySend(SendFailure("", it.text, it.id)) },
                            )
                        }
                    }
                }

                // Jump back to the newest message after scrolling up into history.
                val scope = rememberCoroutineScope()
                AnimatedVisibility(
                    visible = !nearBottom && state.messages.isNotEmpty(),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut(),
                ) {
                    SmallFloatingActionButton(
                        onClick = {
                            scope.launch {
                                listState.animateScrollToItem(state.messages.lastIndex)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Scroll to newest")
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(epochMillis: Long) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                text = formatDayHeader(epochMillis),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: UiMessage,
    isChannel: Boolean,
    showSender: Boolean,
    download: AttachmentDownload?,
    onDownload: (UiAttachment) -> Unit,
    onRetry: (UiMessage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val alignment = if (message.fromMe) Alignment.End else Alignment.Start
    val failed = message.status == MessageStatus.FAILED
    val baseColor = if (message.fromMe) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceContainerHigh
    val color = if (failed) MaterialTheme.colorScheme.errorContainer else baseColor
    val onColor = when {
        failed -> MaterialTheme.colorScheme.onErrorContainer
        message.fromMe -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    // Telegram-style asymmetric corners: the corner nearest the sender is tightened.
    val shape = if (message.fromMe) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 6.dp, bottomStart = 18.dp)
    } else {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 6.dp)
    }
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current

    // A sending bubble is slightly muted until it is confirmed on the live stream (M3-4).
    val bubbleModifier = Modifier
        .widthIn(max = 300.dp)
        .then(if (message.status == MessageStatus.SENDING) Modifier.alpha(0.75f) else Modifier)

    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        Surface(
            color = color,
            shape = shape,
            modifier = bubbleModifier.combinedClickable(
                onClick = {},
                onLongClick = {
                    if (message.text.isNotBlank()) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        clipboard.setText(AnnotatedString(message.text))
                    }
                },
            ),
        ) {
            Column {
                if (isChannel && showSender && !message.fromMe && message.senderName != null) {
                    Text(
                        text = message.senderName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = identityColor(message.senderId),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp),
                    )
                }
                if (message.attachment != null) {
                    AttachmentContent(message.attachment, download, onColor, onDownload)
                    BubbleMeta(message, onColor, Modifier.align(Alignment.End))
                } else {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    ) {
                        Text(
                            text = message.text,
                            color = onColor,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(8.dp))
                        BubbleMeta(message, onColor)
                    }
                }
            }
        }
        if (failed) {
            Text(
                text = "Not delivered. Tap to retry",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .clickable { onRetry(message) }
                    .padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
    }
}

/** Time + delivery state rendered inside the bubble's trailing corner. */
@Composable
private fun BubbleMeta(message: UiMessage, onColor: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = if (message.attachment != null) {
            modifier.padding(end = 10.dp, bottom = 4.dp, start = 10.dp)
        } else {
            modifier
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = formatBubbleTime(message.createdAt),
            style = MaterialTheme.typography.labelSmall,
            color = onColor.copy(alpha = 0.75f),
        )
        if (message.fromMe) {
            val icon = when (message.status) {
                MessageStatus.SENDING -> Icons.Filled.Schedule
                MessageStatus.SENT -> Icons.Filled.Check
                MessageStatus.FAILED -> Icons.Filled.ErrorOutline
            }
            Icon(
                icon,
                contentDescription = when (message.status) {
                    MessageStatus.SENDING -> "Sending"
                    MessageStatus.SENT -> "Sent"
                    MessageStatus.FAILED -> "Failed"
                },
                modifier = Modifier.size(13.dp),
                tint = onColor.copy(alpha = 0.75f),
            )
        }
    }
}

@Composable
private fun AttachmentContent(
    attachment: UiAttachment,
    download: AttachmentDownload?,
    onColor: Color,
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
                else -> ImagePlaceholder()
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
            .widthIn(max = 260.dp)
            .aspectRatio(ratio.coerceIn(0.5f, 2f)),
    )
}

@Composable
private fun ImagePlaceholder() {
    Box(
        modifier = Modifier.size(180.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun FileChip(
    attachment: UiAttachment,
    onColor: Color,
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
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            IconButton(onClick = onAttach) {
                Icon(
                    Icons.Default.AttachFile,
                    contentDescription = "Attach",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message") },
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )
            Spacer(Modifier.width(6.dp))
            FilledIconButton(
                onClick = onSend,
                enabled = value.isNotBlank(),
                shape = CircleShape,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

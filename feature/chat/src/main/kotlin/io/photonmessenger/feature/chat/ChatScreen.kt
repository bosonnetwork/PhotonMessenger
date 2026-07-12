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

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
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
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.photonmessenger.core.designsystem.component.ConfirmDialog
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
    onForwardMessage: () -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val header by viewModel.header.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val loadingOlder by viewModel.loadingOlder.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val uiScope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    // Message pending a delete confirmation (long-press -> Delete). Confirming removes it locally.
    var deleteTarget by remember { mutableStateOf<UiMessage?>(null) }
    // Image bubble opened full-screen (tap on an image).
    var viewerTarget by remember { mutableStateOf<UiMessage?>(null) }
    // Message whose Save-As is waiting on a legacy storage-permission grant (API 26-28).
    var pendingSave by remember { mutableStateOf<UiMessage?>(null) }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.sendAttachment(uri.toString())
    }

    // Legacy Save-As (API < 29) needs WRITE_EXTERNAL_STORAGE; 29+ uses MediaStore with no permission.
    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val target = pendingSave
        pendingSave = null
        if (granted && target != null) viewModel.saveAttachment(target)
        else if (!granted) uiScope.launch { snackbar.showSnackbar("Storage permission is needed to save") }
    }

    fun requestSave(message: UiMessage) {
        val granted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.saveAttachment(message)
        } else {
            pendingSave = message
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    LaunchedEffect(Unit) { viewModel.errors.collect { snackbar.showSnackbar(it) } }

    // Open a resolved attachment file in an external app.
    LaunchedEffect(Unit) {
        viewModel.openFile.collect { (file, mime) ->
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime.ifBlank { "*/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                snackbar.showSnackbar("No app can open this file")
            }
        }
    }

    // Hand a resolved attachment file to the system share sheet.
    LaunchedEffect(Unit) {
        viewModel.shareFile.collect { (file, mime) ->
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime.ifBlank { "*/*" }
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { context.startActivity(Intent.createChooser(intent, null)) }
        }
    }

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
    // Initial open: jump straight to the newest message once the list first has content. This must
    // NOT depend on nearBottom - that reads listState.layoutInfo, which races the first layout: on
    // some devices the list has already laid out at the top by the time the follow effect below runs,
    // leaving nearBottom false and the screen stuck at the oldest message. A dedicated one-shot jump
    // (no animation) positions us reliably regardless of frame timing.
    var initialized by remember { mutableStateOf(false) }
    LaunchedEffect(state.messages.isEmpty()) {
        if (!initialized && state.messages.isNotEmpty()) {
            listState.scrollToItem(state.messages.lastIndex)
            initialized = true
        }
    }

    // After the initial positioning, follow new messages only when the reader is already at the
    // bottom (or the new message is our own send); never yank someone who scrolled up into history.
    val newestId = state.messages.lastOrNull()?.id
    val newestFromMe = state.messages.lastOrNull()?.fromMe == true
    LaunchedEffect(newestId) {
        if (initialized && newestId != null && (nearBottom || newestFromMe) && state.messages.isNotEmpty()) {
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
                                onRetry = { retryMessage ->
                                    if (retryMessage.attachment?.source is AttachmentSource.Local)
                                        viewModel.retryAttachment(retryMessage)
                                    else
                                        viewModel.retrySend(SendFailure("", retryMessage.text, retryMessage.id))
                                },
                                onForward = { viewModel.prepareForward(it); onForwardMessage() },
                                onDelete = { deleteTarget = it },
                                onSaveAs = { requestSave(it) },
                                onOpen = { viewModel.openAttachment(it) },
                                onImageClick = { viewerTarget = it },
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

    deleteTarget?.let { target ->
        ConfirmDialog(
            title = "Delete message?",
            text = "This removes the message from this device only.",
            confirmLabel = "Delete",
            onConfirm = { viewModel.deleteMessage(target) },
            onDismiss = { deleteTarget = null },
        )
    }

    viewerTarget?.let { target ->
        val att = target.attachment
        if (att == null || att.kind != AttachmentKind.IMAGE) {
            viewerTarget = null
        } else {
            // Resolve the image: inline bytes / local uri render immediately; a remote image is fetched
            // (auto-download) and shows a spinner until it lands in the downloads map.
            val model: Any? = when (val src = att.source) {
                is AttachmentSource.Inline -> src.bytes
                is AttachmentSource.Local -> src.uri
                is AttachmentSource.Remote -> {
                    LaunchedEffect(src.contentId) { viewModel.download(att) }
                    (downloads[src.contentId] as? AttachmentDownload.Ready)?.file
                }
            }
            ImageViewerDialog(
                attachment = att,
                model = model,
                onDismiss = { viewerTarget = null },
                onSaveAs = { requestSave(target) },
                onShare = { viewModel.shareAttachment(target) },
                onForward = {
                    viewModel.prepareForward(target)
                    viewerTarget = null
                    onForwardMessage()
                },
            )
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
    onForward: (UiMessage) -> Unit,
    onDelete: (UiMessage) -> Unit,
    onSaveAs: (UiMessage) -> Unit,
    onOpen: (UiMessage) -> Unit,
    onImageClick: (UiMessage) -> Unit,
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
    // Asymmetric corners: the corner nearest the sender is tightened.
    val shape = if (message.fromMe) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 6.dp, bottomStart = 18.dp)
    } else {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 6.dp)
    }
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    // Long-press opens the message action menu. Reset per message id.
    var menuOpen by remember(message.id) { mutableStateOf(false) }
    // Shared long-press handler so the menu opens from anywhere on the bubble - including image and
    // file content, whose own tap handlers would otherwise swallow the parent's long-press.
    val openMenu = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        menuOpen = true
    }

    // Cap the bubble at a share of the window rather than a fixed 300.dp, so wide
    // screens use the available width instead of wrapping text early into a narrow column. Still
    // wrap-to-content for short messages; the fraction only bounds the growth.
    val maxBubbleWidth = (LocalConfiguration.current.screenWidthDp * 0.82f).dp

    // A sending bubble is slightly muted until it is confirmed on the live stream (M3-4).
    val bubbleModifier = Modifier
        .widthIn(max = maxBubbleWidth)
        .then(if (message.status == MessageStatus.SENDING) Modifier.alpha(0.75f) else Modifier)

    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        Box {
            Surface(
                color = color,
                shape = shape,
                modifier = bubbleModifier.combinedClickable(
                    onClick = {},
                    onLongClick = openMenu,
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
                        AttachmentContent(
                            attachment = message.attachment,
                            download = download,
                            sending = message.status == MessageStatus.SENDING,
                            onColor = onColor,
                            onDownload = onDownload,
                            onImageClick = { onImageClick(message) },
                            onLongPress = openMenu,
                        )
                        BubbleMeta(message, onColor, Modifier.align(Alignment.End))
                    } else {
                        BubbleTextContent(
                            message = message,
                            onColor = onColor,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        )
                    }
                }
            }
            // Selected-state tint while the action menu is open.
            if (menuOpen) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                )
            }
            val attachment = message.attachment
            val isLocal = attachment?.source is AttachmentSource.Local
            MessageActionMenu(
                expanded = menuOpen,
                showCopy = message.text.isNotBlank(),
                showSaveAs = attachment != null && !isLocal,
                showForward = message.text.isNotBlank() || (attachment != null && !isLocal),
                showOpen = attachment != null && attachment.kind == AttachmentKind.FILE && !isLocal,
                onDismiss = { menuOpen = false },
                onCopy = { clipboard.setText(AnnotatedString(message.text)) },
                onSaveAs = { onSaveAs(message) },
                onForward = { onForward(message) },
                onOpen = { onOpen(message) },
                onDelete = { onDelete(message) },
            )
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

/**
 * The long-press action menu for a message bubble. Anchored to the pressed bubble and intentionally
 * list-driven so future actions (Reply, Select, ...) slot in as extra items. Each action is gated by
 * a flag so text and attachment bubbles share one menu: Copy (text only), Save As / Open (attachment
 * only), Forward (text or a sent attachment), and Delete (always).
 */
@Composable
private fun MessageActionMenu(
    expanded: Boolean,
    showCopy: Boolean,
    showSaveAs: Boolean,
    showForward: Boolean,
    showOpen: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onSaveAs: () -> Unit,
    onForward: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (showCopy) {
            DropdownMenuItem(
                text = { Text("Copy") },
                leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                onClick = { onDismiss(); onCopy() },
            )
        }
        if (showSaveAs) {
            DropdownMenuItem(
                text = { Text("Save As") },
                leadingIcon = { Icon(Icons.Filled.SaveAlt, contentDescription = null) },
                onClick = { onDismiss(); onSaveAs() },
            )
        }
        if (showForward) {
            DropdownMenuItem(
                text = { Text("Forward") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Forward, contentDescription = null) },
                onClick = { onDismiss(); onForward() },
            )
        }
        if (showOpen) {
            DropdownMenuItem(
                text = { Text("Open") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
                onClick = { onDismiss(); onOpen() },
            )
        }
        DropdownMenuItem(
            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
            leadingIcon = {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            onClick = { onDismiss(); onDelete() },
        )
    }
}

/**
 * A text message with its timestamp dropped into the bottom-right corner: the timestamp shares the
 * last text line when there is room, and wraps onto its own trailing line when there is not. Unlike a
 * plain [Row] of text + meta (which reserves a full-height strip beside the text for the timestamp,
 * leaving long messages wrapping early with a blank right margin), the paragraph here lays out at the
 * full bubble width and only the last line yields space to the timestamp.
 */
@Composable
private fun BubbleTextContent(message: UiMessage, onColor: Color, modifier: Modifier = Modifier) {
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    SubcomposeLayout(modifier) { constraints ->
        val metaPlaceable = subcompose("meta") { BubbleMeta(message, onColor) }
            .first().measure(Constraints())

        var layout: TextLayoutResult? = null
        val textPlaceable = subcompose("text") {
            Text(
                text = message.text,
                color = onColor,
                style = MaterialTheme.typography.bodyLarge,
                onTextLayout = { layout = it },
            )
        }.first().measure(constraints)

        val lastLineWidth = layout?.let { ceil(it.getLineRight(it.lineCount - 1)).toInt() }
            ?: textPlaceable.width
        val maxWidth = constraints.maxWidth
        val fitsInline = lastLineWidth + gap + metaPlaceable.width <= maxWidth

        if (fitsInline) {
            val width = maxOf(textPlaceable.width, lastLineWidth + gap + metaPlaceable.width)
                .coerceAtMost(maxWidth)
            layout(width, textPlaceable.height) {
                textPlaceable.placeRelative(0, 0)
                metaPlaceable.placeRelative(width - metaPlaceable.width, textPlaceable.height - metaPlaceable.height)
            }
        } else {
            val width = maxOf(textPlaceable.width, metaPlaceable.width).coerceAtMost(maxWidth)
            layout(width, textPlaceable.height + metaPlaceable.height) {
                textPlaceable.placeRelative(0, 0)
                metaPlaceable.placeRelative(width - metaPlaceable.width, textPlaceable.height)
            }
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
    sending: Boolean,
    onColor: Color,
    onDownload: (UiAttachment) -> Unit,
    onImageClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val source = attachment.source
    if (attachment.kind == AttachmentKind.IMAGE) {
        when (source) {
            is AttachmentSource.Inline ->
                AttachmentImage(source.bytes, attachment, sending, onImageClick, onLongPress)

            is AttachmentSource.Local ->
                // Optimistic outgoing image: render the picked content uri directly while it uploads.
                AttachmentImage(source.uri, attachment, sending, onImageClick, onLongPress)

            is AttachmentSource.Remote -> {
                // Auto-fetch remote images so they render in place.
                LaunchedEffect(source.contentId) { onDownload(attachment) }
                when (download) {
                    is AttachmentDownload.Ready ->
                        AttachmentImage(download.file, attachment, false, onImageClick, onLongPress)
                    is AttachmentDownload.Failed ->
                        FileAttachment(attachment, onColor, download, sending, onLongPress) { onDownload(attachment) }
                    else -> ImagePlaceholder()
                }
            }
        }
    } else {
        FileAttachment(attachment, onColor, download, sending, onLongPress) {
            if (source is AttachmentSource.Remote) onDownload(attachment)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AttachmentImage(
    model: Any,
    attachment: UiAttachment,
    sending: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val ratio = if (attachment.width != null && attachment.height != null && attachment.height > 0)
        attachment.width.toFloat() / attachment.height else 1f
    Box(contentAlignment = Alignment.Center) {
        AsyncImage(
            model = model,
            contentDescription = attachment.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .widthIn(max = 260.dp)
                .aspectRatio(ratio.coerceIn(0.5f, 2f))
                .combinedClickable(onClick = onClick, onLongClick = onLongPress),
        )
        // An outgoing image shows an upload spinner over a dim scrim until it is confirmed.
        if (sending) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.25f)),
            )
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(36.dp))
        }
    }
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

/**
 * A Telegram-style bubble for a non-inline attachment: a circular type glyph (or a download / progress
 * / retry affordance that folds into the same circle) beside the file name and its size + type. Tapping
 * downloads-then-opens (files) or retries a failed transfer.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileAttachment(
    attachment: UiAttachment,
    onColor: Color,
    download: AttachmentDownload?,
    sending: Boolean,
    onLongPress: () -> Unit,
    onClick: () -> Unit,
) {
    val isRemote = attachment.source is AttachmentSource.Remote
    val ready = download is AttachmentDownload.Ready
    val loading = sending || download is AttachmentDownload.Loading
    val failed = download is AttachmentDownload.Failed
    val ext = extLabel(attachment.name, attachment.mime)
    val secondary = when {
        sending -> "Sending..."
        loading -> "Downloading..."
        failed -> "Tap to retry"
        else -> if (ext.isEmpty()) formatSize(attachment.size) else "${formatSize(attachment.size)} . $ext"
    }
    Row(
        modifier = Modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { contentDescription = "$ext ${attachment.name}, $secondary" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(onColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                loading -> CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = onColor,
                )
                failed -> Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = onColor)
                isRemote && !ready -> Icon(Icons.Filled.Download, contentDescription = null, tint = onColor)
                else -> Icon(fileGlyph(attachment.mime), contentDescription = null, tint = onColor)
            }
        }
        Column(modifier = Modifier.widthIn(max = 200.dp)) {
            Text(attachment.name, color = onColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(secondary, color = onColor.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Picks a file-type glyph from the MIME type for the file bubble. */
private fun fileGlyph(mime: String): ImageVector = when {
    mime == "application/pdf" -> Icons.Filled.PictureAsPdf
    mime.startsWith("audio/") -> Icons.Filled.Audiotrack
    mime.startsWith("video/") -> Icons.Filled.Movie
    mime.startsWith("text/") || mime.contains("word") || mime.contains("document") -> Icons.Filled.Description
    mime.contains("zip") || mime.contains("compressed") || mime.contains("tar") ||
        mime.contains("rar") || mime.contains("7z") -> Icons.Filled.FolderZip
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}

/** A short uppercase type label from the file extension, falling back to the MIME subtype. */
private fun extLabel(name: String, mime: String): String {
    val ext = name.substringAfterLast('.', "")
    if (ext.isNotEmpty() && ext.length <= 5) return ext.uppercase()
    val sub = mime.substringAfterLast('/', "")
    return sub.takeIf { it.isNotEmpty() && it.length <= 6 }?.uppercase() ?: ""
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

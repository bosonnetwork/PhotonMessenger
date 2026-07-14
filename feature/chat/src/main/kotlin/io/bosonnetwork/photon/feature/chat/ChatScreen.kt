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

package io.bosonnetwork.photon.feature.chat

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
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.Button
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
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onSizeChanged
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
import io.bosonnetwork.photon.core.boson.ChannelInvite
import io.bosonnetwork.photon.core.model.InviteAction
import io.bosonnetwork.photon.core.designsystem.component.ConfirmDialog
import io.bosonnetwork.photon.core.designsystem.component.PhotonAvatar
import io.bosonnetwork.photon.core.designsystem.component.ResponsiveContent
import io.bosonnetwork.photon.core.designsystem.component.formatBubbleTime
import io.bosonnetwork.photon.core.designsystem.component.formatDayHeader
import io.bosonnetwork.photon.core.designsystem.component.identityColor
import io.bosonnetwork.photon.core.designsystem.component.sameDay
import io.bosonnetwork.photon.feature.chat.data.VoicePlayer
import io.bosonnetwork.photon.feature.chat.model.AttachmentKind
import io.bosonnetwork.photon.feature.chat.model.AttachmentSource
import io.bosonnetwork.photon.feature.chat.model.MessageStatus
import io.bosonnetwork.photon.feature.chat.model.UiAttachment
import io.bosonnetwork.photon.feature.chat.model.UiMessage
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenChannelDetail: (String) -> Unit = {},
    onOpenContactDetail: (String) -> Unit = {},
    onForwardMessage: () -> Unit = {},
    onOpenChannel: (String) -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val header by viewModel.header.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val loadingOlder by viewModel.loadingOlder.collectAsStateWithLifecycle()
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val voicePlayback by viewModel.voicePlayback.collectAsStateWithLifecycle()
    val inviteActions by viewModel.inviteActions.collectAsStateWithLifecycle()
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

    // Voice recording needs the microphone. We check-then-request; recording starts on the next mic
    // press once granted (the user releases and holds again), so the gesture never races the prompt.
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val audioPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasAudioPermission = granted
        if (!granted) uiScope.launch {
            snackbar.showSnackbar("Microphone permission is needed to record voice messages")
        }
    }

    LaunchedEffect(Unit) { viewModel.errors.collect { snackbar.showSnackbar(it) } }

    // Accepting a channel invitation joins and then opens that channel's conversation.
    LaunchedEffect(Unit) { viewModel.joinedChannel.collect { onOpenChannel(it) } }

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
                voiceSupported = viewModel.voiceSupported,
                hasAudioPermission = hasAudioPermission,
                recording = recording,
                onRequestAudioPermission = { audioPermission.launch(Manifest.permission.RECORD_AUDIO) },
                onStartRecord = viewModel::startRecording,
                onLockRecord = viewModel::lockRecording,
                onCancelRecord = viewModel::cancelRecording,
                onSendRecord = viewModel::stopAndSendRecording,
                onRecordTooShort = {
                    uiScope.launch { snackbar.showSnackbar("Hold to record, release to send") }
                },
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
                                playback = voicePlayback,
                                inviteAction = inviteActions[msg.id],
                                onDownload = { viewModel.download(it) },
                                onJoinInvite = { viewModel.joinInvite(it) },
                                onIgnoreInvite = { viewModel.ignoreInvite(it) },
                                onRetry = { retryMessage ->
                                    val att = retryMessage.attachment
                                    when {
                                        att?.kind == AttachmentKind.VOICE && att.source is AttachmentSource.Local ->
                                            viewModel.retryVoice(retryMessage)
                                        att?.source is AttachmentSource.Local ->
                                            viewModel.retryAttachment(retryMessage)
                                        else ->
                                            viewModel.retrySend(SendFailure("", retryMessage.text, retryMessage.id))
                                    }
                                },
                                onForward = { viewModel.prepareForward(it); onForwardMessage() },
                                onDelete = { deleteTarget = it },
                                onSaveAs = { requestSave(it) },
                                onOpen = { viewModel.openAttachment(it) },
                                onImageClick = { viewerTarget = it },
                                onToggleVoice = { viewModel.toggleVoice(it) },
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
    playback: VoicePlayer.Playback,
    inviteAction: InviteAction?,
    onDownload: (UiAttachment) -> Unit,
    onJoinInvite: (UiMessage) -> Unit,
    onIgnoreInvite: (UiMessage) -> Unit,
    onRetry: (UiMessage) -> Unit,
    onForward: (UiMessage) -> Unit,
    onDelete: (UiMessage) -> Unit,
    onSaveAs: (UiMessage) -> Unit,
    onOpen: (UiMessage) -> Unit,
    onImageClick: (UiMessage) -> Unit,
    onToggleVoice: (UiMessage) -> Unit,
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
                    if (message.invite != null) {
                        InviteCard(
                            invite = message.invite,
                            fromMe = message.fromMe,
                            action = inviteAction,
                            onColor = onColor,
                            onJoin = { onJoinInvite(message) },
                            onIgnore = { onIgnoreInvite(message) },
                        )
                        BubbleMeta(message, onColor, Modifier.align(Alignment.End))
                    } else if (message.attachment != null) {
                        // Voice playback state for this bubble: the player reports a single active note,
                        // so a note that isn't the active one shows its length and a play affordance.
                        val voiceActive = playback.messageId == message.id
                        val voice = VoiceBubbleState(
                            playing = voiceActive && playback.isPlaying,
                            preparing = voiceActive && playback.preparing,
                            positionMs = if (voiceActive) playback.positionMs else 0,
                            durationMs = if (voiceActive && playback.durationMs > 0) playback.durationMs
                            else (message.attachment.durationMs?.toInt() ?: 0),
                        )
                        AttachmentContent(
                            attachment = message.attachment,
                            download = download,
                            sending = message.status == MessageStatus.SENDING,
                            onColor = onColor,
                            voice = voice,
                            onDownload = onDownload,
                            onImageClick = { onImageClick(message) },
                            onToggleVoice = { onToggleVoice(message) },
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
                showSave = attachment != null && !isLocal,
                showForward = message.text.isNotBlank() || (attachment != null && !isLocal),
                showOpen = attachment != null && attachment.kind == AttachmentKind.FILE && !isLocal,
                onDismiss = { menuOpen = false },
                onCopy = { clipboard.setText(AnnotatedString(message.text)) },
                onSave = { onSaveAs(message) },
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
 * a flag so text and attachment bubbles share one menu: Copy (text only), Save / Open (attachment
 * only), Forward (text or a sent attachment), and Delete (always).
 */
@Composable
private fun MessageActionMenu(
    expanded: Boolean,
    showCopy: Boolean,
    showSave: Boolean,
    showForward: Boolean,
    showOpen: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onSave: () -> Unit,
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
        if (showSave) {
            DropdownMenuItem(
                text = { Text("Save") },
                leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                onClick = { onDismiss(); onSave() },
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
 * A channel-invite bubble. The recipient sees the channel name and, while the invite is actionable,
 * inline Join / Ignore controls (the discoverable primary path - deliberately not buried in the
 * long-press menu). Ignore is a soft dismiss: the card collapses but stays joinable until it expires.
 * The sender's own copy is passive ("Invitation sent"); joined / expired invites drop their buttons.
 */
@Composable
private fun InviteCard(
    invite: ChannelInvite,
    fromMe: Boolean,
    action: InviteAction?,
    onColor: Color,
    onJoin: () -> Unit,
    onIgnore: () -> Unit,
) {
    val expired = invite.expiresAt in 1 until System.currentTimeMillis()
    val mutedColor = onColor.copy(alpha = 0.7f)
    Column(
        modifier = Modifier
            .widthIn(min = 208.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Groups,
                contentDescription = null,
                tint = mutedColor,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    "Channel invitation",
                    style = MaterialTheme.typography.labelSmall,
                    color = mutedColor,
                )
                Text(
                    invite.channelName.ifBlank { "Channel" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = onColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            fromMe -> InviteStatus("Invitation sent", mutedColor)
            action == InviteAction.JOINED -> InviteStatus("Joined", mutedColor)
            expired -> InviteStatus("Invitation expired", mutedColor)
            action == InviteAction.IGNORED ->
                // Soft dismiss: the prompt collapses but joining stays available until the ticket expires.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Invitation dismissed",
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedColor,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onJoin) { Text("Join") }
                }
            else ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onJoin) { Text("Join") }
                    TextButton(onClick = onIgnore) { Text("Ignore") }
                }
        }
    }
}

@Composable
private fun InviteStatus(text: String, color: Color) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
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

/** Voice-note playback state projected onto a single bubble (see [MessageBubble]). */
private data class VoiceBubbleState(
    val playing: Boolean,
    val preparing: Boolean,
    val positionMs: Int,
    val durationMs: Int,
)

@Composable
private fun AttachmentContent(
    attachment: UiAttachment,
    download: AttachmentDownload?,
    sending: Boolean,
    onColor: Color,
    voice: VoiceBubbleState,
    onDownload: (UiAttachment) -> Unit,
    onImageClick: () -> Unit,
    onToggleVoice: () -> Unit,
    onLongPress: () -> Unit,
) {
    val source = attachment.source
    when (attachment.kind) {
        AttachmentKind.IMAGE -> when (source) {
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

        AttachmentKind.VOICE ->
            VoiceAttachment(attachment, voice, sending, onColor, onToggleVoice, onLongPress)

        AttachmentKind.FILE ->
            FileAttachment(attachment, onColor, download, sending, onLongPress) {
                if (source is AttachmentSource.Remote) onDownload(attachment)
            }
    }
}

/**
 * A voice-note bubble: a circular play/pause button beside a progress track and elapsed/total time.
 * Structurally mirrors [FileAttachment]; a real waveform is deferred, so the track is a plain bar.
 * Tapping toggles playback; long-press opens the message menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VoiceAttachment(
    attachment: UiAttachment,
    voice: VoiceBubbleState,
    sending: Boolean,
    onColor: Color,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
) {
    val totalMs = if (voice.durationMs > 0) voice.durationMs else (attachment.durationMs?.toInt() ?: 0)
    val progress = if (totalMs > 0) (voice.positionMs.toFloat() / totalMs).coerceIn(0f, 1f) else 0f
    // Show elapsed while a play position exists, otherwise the full length.
    val timeLabel = formatDuration(if (voice.positionMs in 1 until totalMs) voice.positionMs else totalMs)
    val a11y = "Voice message, ${formatDuration(totalMs)}, " +
        if (voice.playing) "playing, tap to pause" else "tap to play"
    Row(
        modifier = Modifier
            .combinedClickable(onClick = onToggle, onLongClick = onLongPress)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { contentDescription = a11y },
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
                sending || voice.preparing -> CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = onColor,
                )
                voice.playing -> Icon(Icons.Filled.Pause, contentDescription = null, tint = onColor)
                else -> Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = onColor)
            }
        }
        Column(modifier = Modifier.widthIn(min = 140.dp, max = 200.dp)) {
            // A minimal progress track; the filled portion tracks playback position.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(onColor.copy(alpha = 0.25f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(onColor),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                timeLabel,
                color = onColor.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** Formats a millisecond length/position as m:ss for a voice bubble. */
private fun formatDuration(ms: Int): String {
    val totalSeconds = (ms.coerceAtLeast(0)) / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
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
 * A file-style bubble for a non-inline attachment: a circular type glyph (or a download / progress
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

// Voice-recording gesture thresholds (from the mic-button down point) and the minimum hold that
// counts as a real recording rather than an accidental tap.
private val RECORD_CANCEL_SLIDE = 72.dp
private val RECORD_LOCK_SLIDE = 72.dp
private const val MIN_RECORD_MS = 800L

/**
 * The composer. When idle it is the familiar attach + text field + send row; when the field is empty
 * (and the device can record) the trailing button becomes a press-and-hold mic. While recording, the
 * text field is replaced by a recording bar (elapsed time + mic level) and the trailing area offers
 * release-to-send / slide-to-cancel / slide-up-to-lock, then Delete + Send once locked.
 */
@Composable
private fun MessageInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    voiceSupported: Boolean,
    hasAudioPermission: Boolean,
    recording: RecordingState,
    onRequestAudioPermission: () -> Unit,
    onStartRecord: () -> Unit,
    onLockRecord: () -> Unit,
    onCancelRecord: () -> Unit,
    onSendRecord: () -> Unit,
    onRecordTooShort: () -> Unit,
) {
    val isRecording = recording !is RecordingState.Idle
    val locked = recording is RecordingState.Ready ||
        (recording is RecordingState.Active && recording.locked)
    // How far the finger has dragged left toward cancel (px, <= 0); drives the "slide to cancel" hint.
    var slideOffset by remember { mutableStateOf(0f) }
    // Measured height of the (empty) text field, reused for the recording bar so the composer keeps the
    // same height across modes. Measured (not hardcoded) so it tracks the user's font scale, not just
    // screen density. Seeded with the Material default until the first measure.
    val density = LocalDensity.current
    var inputHeight by remember { mutableStateOf(56.dp) }
    // Live mic level (0..1) for the halo behind the mic while held.
    val micLevel = (recording as? RecordingState.Active)
        ?.takeIf { !it.locked }
        ?.let { (it.amplitude / 8000f).coerceIn(0f, 1f) } ?: 0f
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isRecording) {
                // Match the measured text-field height so the composer keeps the same height - and the
                // centered mic keeps the same position - when switching to recording mode.
                RecordingBar(
                    recording,
                    locked,
                    slideOffset,
                    modifier = Modifier.weight(1f).heightIn(min = inputHeight),
                )
            } else {
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
                    modifier = Modifier
                        .weight(1f)
                        // Remember the single-line field height (blank draft) to size the recording bar.
                        .onSizeChanged { if (value.isBlank()) inputHeight = with(density) { it.height.toDp() } },
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
            }
            Spacer(Modifier.width(6.dp))
            // Trailing slot. Kept a single structural position so the mic button preserves its
            // press-and-hold gesture across the idle -> recording transition.
            when {
                locked -> {
                    IconButton(onClick = onCancelRecord) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Delete recording",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    FilledIconButton(onClick = onSendRecord, shape = CircleShape) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send voice message")
                    }
                }
                value.isNotBlank() && !isRecording ->
                    FilledIconButton(onClick = onSend, shape = CircleShape) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                voiceSupported ->
                    MicButton(
                        hasPermission = hasAudioPermission,
                        level = micLevel,
                        onRequestPermission = onRequestAudioPermission,
                        onStart = onStartRecord,
                        onSlide = { slideOffset = it },
                        onLock = onLockRecord,
                        onCancel = onCancelRecord,
                        onSend = onSendRecord,
                        onTooShort = onRecordTooShort,
                    )
                else ->
                    FilledIconButton(onClick = onSend, enabled = false, shape = CircleShape) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
            }
        }
    }
}

/**
 * The press-and-hold mic button. Down begins recording (after a one-time permission prompt); a drag
 * left past [RECORD_CANCEL_SLIDE] cancels, a drag up past [RECORD_LOCK_SLIDE] locks for hands-free,
 * and release sends (unless it was a too-short accidental tap). The gesture lives in a
 * [pointerInput] keyed on Unit so it survives recomposition while the finger is down.
 */
@Composable
private fun MicButton(
    hasPermission: Boolean,
    level: Float,
    onRequestPermission: () -> Unit,
    onStart: () -> Unit,
    onSlide: (Float) -> Unit,
    onLock: () -> Unit,
    onCancel: () -> Unit,
    onSend: () -> Unit,
    onTooShort: () -> Unit,
) {
    val hasPerm by rememberUpdatedState(hasPermission)
    val requestPerm by rememberUpdatedState(onRequestPermission)
    val start by rememberUpdatedState(onStart)
    val slide by rememberUpdatedState(onSlide)
    val lock by rememberUpdatedState(onLock)
    val cancel by rememberUpdatedState(onCancel)
    val send by rememberUpdatedState(onSend)
    val tooShort by rememberUpdatedState(onTooShort)
    val haptics = LocalHapticFeedback.current

    Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        // A soft halo that grows with the mic level while recording; kept subtle and scaled via a
        // graphics layer so it never affects layout.
        if (level > 0f) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .graphicsLayer {
                        val s = 1f + level * 0.6f
                        scaleX = s
                        scaleY = s
                        alpha = 0.25f
                    }
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .pointerInput(Unit) {
                    val cancelPx = RECORD_CANCEL_SLIDE.toPx()
                    val lockPx = RECORD_LOCK_SLIDE.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (!hasPerm) {
                            requestPerm()
                            // Drain the gesture so releasing after the prompt starts no phantom recording.
                            do {
                                val e = awaitPointerEvent()
                            } while (e.changes.any { it.pressed })
                            return@awaitEachGesture
                        }
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        val startedAt = System.currentTimeMillis()
                        start()
                        slide(0f)
                        var isLocked = false
                        var isCancelled = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                            if (!isLocked && !isCancelled) {
                                val dx = change.position.x - down.position.x
                                val dy = change.position.y - down.position.y
                                // Report leftward drag so the "slide to cancel" hint follows the finger.
                                slide(dx.coerceAtMost(0f))
                                if (dx <= -cancelPx && dx <= dy) {
                                    isCancelled = true
                                    cancel()
                                } else if (dy <= -lockPx) {
                                    isLocked = true
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    lock()
                                }
                            }
                            if (!change.pressed) break
                        }
                        slide(0f)
                        if (!isCancelled && !isLocked) {
                            if (System.currentTimeMillis() - startedAt < MIN_RECORD_MS) {
                                cancel()
                                tooShort()
                            } else {
                                send()
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = "Record voice message",
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

/**
 * The in-composer recording indicator: a blinking red dot, a running m:ss,cc timer, and a
 * "Slide to cancel" hint that follows the finger left and fades toward the cancel threshold. When
 * locked (hands-free), the dot stays lit and the hint is replaced by a lock glyph.
 */
@Composable
private fun RecordingBar(
    recording: RecordingState,
    locked: Boolean,
    slideOffset: Float,
    modifier: Modifier = Modifier,
) {
    val elapsedMs = when (recording) {
        is RecordingState.Active -> recording.elapsedMs
        is RecordingState.Ready -> recording.durationMs
        RecordingState.Idle -> 0L
    }
    val blink = rememberInfiniteTransition(label = "rec-blink")
    val dotAlpha by blink.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "rec-dot-alpha",
    )
    val cancelPx = with(LocalDensity.current) { RECORD_CANCEL_SLIDE.toPx() }
    val clamped = slideOffset.coerceIn(-cancelPx, 0f)
    val cancelFraction = if (cancelPx > 0f) (-clamped / cancelPx).coerceIn(0f, 1f) else 0f

    Row(
        modifier = modifier.padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .graphicsLayer { alpha = if (locked) 1f else dotAlpha }
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            formatDurationLong(elapsedMs),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        if (locked) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = "Recording locked",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Row(
                modifier = Modifier.graphicsLayer {
                    translationX = clamped
                    alpha = 1f - cancelFraction
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.ChevronLeft,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    "Slide to cancel",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

/** Formats a recording length as m:ss,cc (centiseconds), e.g. "0:02,40". */
private fun formatDurationLong(ms: Long): String {
    val total = ms.coerceAtLeast(0)
    return "%d:%02d,%02d".format(total / 60000, (total / 1000) % 60, (total % 1000) / 10)
}

package com.nidoham.bondhu.presentation.screen.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nidoham.bondhu.presentation.viewmodel.ChatUiState
import com.nidoham.bondhu.ui.theme.CustomTypography
import com.nidoham.bondhu.ui.theme.LocalCustomColors
import kotlinx.coroutines.launch
import org.nidoham.server.data.util.DEFAULT_RECENT_EMOJIS
import org.nidoham.server.data.util.EMOJI_CATEGORIES
import org.nidoham.server.data.util.EmojiCategory
import org.nidoham.server.data.util.emojiCount
import org.nidoham.server.data.util.isOnlyEmoji
import org.nidoham.server.domain.model.Message
import org.nidoham.server.domain.model.MessageType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ─── Responsive Breakpoints ───────────────────────────────────────────────────

private enum class WindowWidthClass { Compact, Medium, Expanded }

private fun Dp.toWindowWidthClass(): WindowWidthClass = when {
    this < 600.dp -> WindowWidthClass.Compact
    this < 840.dp -> WindowWidthClass.Medium
    else          -> WindowWidthClass.Expanded
}

// ─── Emoji Recent List (runtime state) ───────────────────────────────────────
// All emoji data (EmojiCategory, EMOJI_CATEGORIES, helpers) live in EmojiData.kt.
// Only the mutable recent list is kept here because it changes during the session.

private val recentEmojis: MutableList<String> = DEFAULT_RECENT_EMOJIS.toMutableList()

// ─── ChatScreen ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    uiState: ChatUiState,
    onBack: () -> Unit,
    onInputChanged: (String) -> Unit,
    onSend: () -> Unit,
    isMine: (Message) -> Boolean,
    isReadByPeer: (Message) -> Boolean
) {
    val listState      = rememberLazyListState()
    var showEmojiSheet by rememberSaveable { mutableStateOf(false) }
    val sheetState     = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope          = rememberCoroutineScope()

    LaunchedEffect(uiState.messages.size, uiState.isLoading) {
        if (!uiState.isLoading && uiState.messages.isNotEmpty())
            listState.animateScrollToItem(uiState.messages.size - 1)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val windowClass = maxWidth.toWindowWidthClass()

        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                ChatTopBar(
                    name        = uiState.peerName,
                    avatarUrl   = uiState.peerAvatarUrl,
                    isOnline    = uiState.isPeerOnline,
                    lastSeen    = uiState.lastSeen,
                    onBack      = onBack,
                    windowClass = windowClass
                )
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding())
            ) {
                ChatWallpaper()

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding()
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        when {
                            uiState.isLoading -> CircularProgressIndicator(
                                modifier    = Modifier.align(Alignment.Center),
                                color       = MaterialTheme.colorScheme.primary,
                                strokeWidth = 3.dp
                            )
                            uiState.messages.isEmpty() -> { /* empty — intentionally blank */ }
                            else -> MessageList(
                                messages      = uiState.messages,
                                listState     = listState,
                                isMine        = isMine,
                                isReadByPeer  = isReadByPeer,
                                windowClass   = windowClass,
                                peerAvatarUrl = uiState.peerAvatarUrl,
                                peerName      = uiState.peerName,
                                isPeerTyping  = uiState.isPeerTyping
                            )
                        }
                    }

                    ChatInputBar(
                        text         = uiState.inputText,
                        onTextChange = onInputChanged,
                        onSend       = onSend,
                        isSendError  = uiState.isSendError,
                        windowClass  = windowClass,
                        onEmojiClick = {
                            scope.launch { showEmojiSheet = true; sheetState.show() }
                        }
                    )
                }
            }
        }
    }

    if (showEmojiSheet) {
        EmojiBottomSheet(
            sheetState      = sheetState,
            onDismiss       = {
                scope.launch { sheetState.hide() }.invokeOnCompletion { showEmojiSheet = false }
            },
            onEmojiSelected = { emoji ->
                recentEmojis.remove(emoji)
                recentEmojis.add(0, emoji)
                if (recentEmojis.size > 24) recentEmojis.removeLastOrNull()
                onInputChanged(uiState.inputText + emoji)
            }
        )
    }
}

// ─── Top Bar ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    name: String, avatarUrl: String, isOnline: Boolean,
    lastSeen: String, onBack: () -> Unit, windowClass: WindowWidthClass
) {
    val avatarSize = if (windowClass == WindowWidthClass.Compact) 40.dp else 48.dp
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor             = MaterialTheme.colorScheme.background,
            titleContentColor          = MaterialTheme.colorScheme.onBackground,
            navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
            actionIconContentColor     = MaterialTheme.colorScheme.onBackground
        ),
        navigationIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
                PeerAvatar(avatarUrl = avatarUrl, name = name, size = avatarSize, isOnline = isOnline)
                Spacer(Modifier.width(8.dp))
            }
        },
        title = {
            Column {
                Text(
                    text     = name.ifBlank { "Loading…" },
                    style    = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        color      = MaterialTheme.colorScheme.onBackground
                    ),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    text  = if (isOnline) "active" else lastSeen.ifBlank { "" },
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.80f)
                    )
                )
            }
        },
        actions = {
            IconButton(onClick = {}) { Icon(Icons.Default.Call, "Call") }
            IconButton(onClick = {}) { Icon(Icons.Default.MoreVert, "More") }
        }
    )
}

// ─── Peer Avatar ──────────────────────────────────────────────────────────────

@Composable
private fun PeerAvatar(avatarUrl: String, name: String, size: Dp, isOnline: Boolean) {
    val customColors = LocalCustomColors.current
    Box(contentAlignment = Alignment.BottomEnd) {
        if (avatarUrl.isNotBlank()) {
            AsyncImage(
                model = avatarUrl, contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape)
            )
        } else {
            Box(
                modifier         = Modifier.size(size).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleSmall.copy(
                        color      = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                        fontSize   = (size.value * 0.38f).sp
                    )
                )
            }
        }
        if (isOnline) {
            Box(
                modifier = Modifier.size(size * 0.28f).clip(CircleShape)
                    .background(customColors.success)
                    .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape)
            )
        }
    }
}

// ─── Message List ─────────────────────────────────────────────────────────────

@Composable
private fun MessageList(
    messages: List<Message>,
    listState: LazyListState,
    isMine: (Message) -> Boolean,
    isReadByPeer: (Message) -> Boolean,
    windowClass: WindowWidthClass,
    peerAvatarUrl: String,
    peerName: String,
    isPeerTyping: Boolean = false
) {
    val hPadding = when (windowClass) {
        WindowWidthClass.Compact  -> 8.dp
        WindowWidthClass.Medium   -> 24.dp
        WindowWidthClass.Expanded -> 80.dp
    }
    val lastSentId = remember(messages) { messages.lastOrNull { isMine(it) }?.messageId }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val bubbleMax = when (windowClass) {
            WindowWidthClass.Compact  -> maxWidth * 0.78f
            WindowWidthClass.Medium   -> maxWidth * 0.65f
            WindowWidthClass.Expanded -> maxWidth * 0.50f
        }
        LazyColumn(
            state               = listState,
            modifier            = Modifier.fillMaxSize().padding(horizontal = hPadding),
            verticalArrangement = Arrangement.spacedBy(9.dp),
            contentPadding      = PaddingValues(vertical = 12.dp)
        ) {
            val grouped = messages.groupByDate()
            grouped.forEach { (label, dayMessages) ->
                item(key = "date_$label") { DateChip(label) }
                items(dayMessages, key = { it.messageId }) { msg ->
                    var appeared by remember { mutableStateOf(false) }
                    LaunchedEffect(msg.messageId) { appeared = true }
                    AnimatedVisibility(appeared, enter = slideInVertically { it / 2 } + fadeIn()) {
                        MessageBubble(
                            message           = msg,
                            isMine            = isMine(msg),
                            isReadByPeer      = isReadByPeer(msg),
                            isLastSentMessage = msg.messageId == lastSentId,
                            bubbleMaxWidth    = bubbleMax,
                            peerAvatarUrl     = peerAvatarUrl,
                            peerName          = peerName
                        )
                    }
                }
            }
            // Typing indicator — always last in the list
            item(key = "typing_indicator") {
                AnimatedVisibility(
                    visible = isPeerTyping,
                    enter   = slideInVertically { it / 2 } + fadeIn(),
                    exit    = fadeOut()
                ) {
                    TypingIndicatorBubble(peerAvatarUrl = peerAvatarUrl, peerName = peerName)
                }
            }
        }
    }
}

// ─── Date Chip ────────────────────────────────────────────────────────────────

@Composable
private fun DateChip(label: String) {
    Box(
        modifier         = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text     = label,
            modifier = Modifier.clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
                .padding(horizontal = 10.dp, vertical = 3.dp),
            style = CustomTypography.overline.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
        )
    }
}

// ─── Message Bubble ───────────────────────────────────────────────────────────

@Composable
private fun MessageBubble(
    message: Message,
    isMine: Boolean,
    isReadByPeer: Boolean,
    isLastSentMessage: Boolean,
    bubbleMaxWidth: Dp,
    peerAvatarUrl: String,
    peerName: String
) {
    var showTime by remember { mutableStateOf(false) }
    val timestamp = remember(message.timestamp) { message.timestamp?.toDate()?.toTimeString() }

    if (message.deleted) { DeletedMessageBubble(isMine); return }

    Column(Modifier.fillMaxWidth()) {
        // Timestamp shown ABOVE bubble on tap
        AnimatedVisibility(showTime && timestamp != null, enter = fadeIn() + slideInVertically { -it / 2 }, exit = fadeOut()) {
            Box(Modifier.fillMaxWidth().padding(bottom = 3.dp), Alignment.Center) {
                Text(
                    timestamp ?: "",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.50f),
                        fontSize = 12.sp, textAlign = TextAlign.Center
                    )
                )
            }
        }

        if (isMine) {
            Row(Modifier.fillMaxWidth(), Arrangement.End, Alignment.Bottom) {
                SentBubble(message, isLastSentMessage, isReadByPeer, bubbleMaxWidth) { showTime = !showTime }
            }
        } else {
            Row(Modifier.fillMaxWidth(), Arrangement.Start, Alignment.Bottom) {
                Box(Modifier.padding(end = 8.dp, bottom = 3.dp)) {
                    PeerAvatar(peerAvatarUrl, peerName, 36.dp, isOnline = false)
                }
                ReceivedBubble(message, bubbleMaxWidth) { showTime = !showTime }
            }
        }
    }
}

// ─── Sent Bubble ──────────────────────────────────────────────────────────────

@Composable
private fun SentBubble(
    message: Message,
    isLastSentMessage: Boolean,
    isReadByPeer: Boolean,
    bubbleMaxWidth: Dp,
    onTap: () -> Unit
) {
    val onlyEmoji  = remember(message.content) { message.content.isOnlyEmoji() }
    val count      = remember(message.content) { if (onlyEmoji) message.content.emojiCount() else 0 }
    val bubbleShape = RoundedCornerShape(20.dp, 5.dp, 20.dp, 20.dp)

    Column(horizontalAlignment = Alignment.End) {
        if (onlyEmoji) {
            val fs = when { count == 1 -> 78.sp; count <= 3 -> 60.sp; count <= 6 -> 45.sp; else -> 33.sp }
            Text(
                message.content.trim(), fontSize = fs, textAlign = TextAlign.End,
                modifier = Modifier.widthIn(max = bubbleMaxWidth)
                    .clickable(remember<MutableInteractionSource> { MutableInteractionSource() }, null, onClick = onTap)
                    .padding(6.dp)
            )
        } else {
            Box(
                modifier = Modifier.widthIn(96.dp, bubbleMaxWidth)
                    .shadow(1.dp, bubbleShape).clip(bubbleShape)
                    .background(Color(0xFF005C4B))
                    .clickable(remember<MutableInteractionSource> { MutableInteractionSource() }, null, onClick = onTap)
                    .padding(horizontal = 15.dp, vertical = 10.dp)
            ) { BubbleContent(message, Color.White) }
        }
        if (isLastSentMessage && message.timestamp != null) {
            Spacer(Modifier.height(3.dp))
            MessageStatusTick(isReadByPeer)
        }
    }
}

// ─── Received Bubble ──────────────────────────────────────────────────────────

@Composable
private fun ReceivedBubble(message: Message, bubbleMaxWidth: Dp, onTap: () -> Unit) {
    val onlyEmoji  = remember(message.content) { message.content.isOnlyEmoji() }
    val count      = remember(message.content) { if (onlyEmoji) message.content.emojiCount() else 0 }
    val bubbleShape = RoundedCornerShape(5.dp, 20.dp, 20.dp, 20.dp)

    if (onlyEmoji) {
        val fs = when { count == 1 -> 78.sp; count <= 3 -> 60.sp; count <= 6 -> 45.sp; else -> 33.sp }
        Text(
            message.content.trim(), fontSize = fs, textAlign = TextAlign.Start,
            modifier = Modifier.widthIn(max = bubbleMaxWidth)
                .clickable(remember<MutableInteractionSource> { MutableInteractionSource() }, null, onClick = onTap)
                .padding(6.dp)
        )
    } else {
        Box(
            modifier = Modifier.widthIn(96.dp, bubbleMaxWidth)
                .shadow(1.dp, bubbleShape).clip(bubbleShape)
                .background(MaterialTheme.colorScheme.surface)
                .clickable(remember<MutableInteractionSource> { MutableInteractionSource() }, null, onClick = onTap)
                .padding(horizontal = 15.dp, vertical = 10.dp)
        ) { BubbleContent(message, MaterialTheme.colorScheme.onSurface) }
    }
}

// ─── Bubble Content ───────────────────────────────────────────────────────────

@Composable
private fun BubbleContent(message: Message, textColor: Color) {
    Column {
        when (message.toType()) {
            MessageType.IMAGE -> Text("📷 Photo",
                style = MaterialTheme.typography.bodyMedium.copy(color = textColor.copy(.80f), fontSize = 22.sp))
            else -> if (message.content.isNotEmpty())
                Text(message.content,
                    style = MaterialTheme.typography.bodyMedium.copy(color = textColor, fontSize = 22.sp))
        }
        if (message.isEdited) {
            Spacer(Modifier.height(2.dp))
            Text("edited", style = MaterialTheme.typography.labelSmall.copy(color = textColor.copy(.55f), fontSize = 11.sp))
        }
    }
}

// ─── Typing Indicator Bubble ──────────────────────────────────────────────────

/**
 * Messenger-style three-dot typing indicator.
 * Observes the peer's typing field at /messages/{conversationId}/{peerId}/typing
 * via [ChatUiState.isPeerTyping] which the ViewModel keeps live.
 */
@Composable
private fun TypingIndicatorBubble(peerAvatarUrl: String, peerName: String) {
    val bubbleShape = RoundedCornerShape(5.dp, 20.dp, 20.dp, 20.dp)
    val dotColor    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f)

    // Three-dot staggered pulse animation
    val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "typing")
    val dot1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.25f, targetValue = 1f, label = "d1",
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation  = androidx.compose.animation.core.keyframes {
                durationMillis = 1200
                0.25f at 0; 1f at 200; 0.25f at 500
            },
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart
        )
    )
    val dot2Alpha by infiniteTransition.animateFloat(
        initialValue = 0.25f, targetValue = 1f, label = "d2",
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation  = androidx.compose.animation.core.keyframes {
                durationMillis = 1200
                0.25f at 0; 0.25f at 150; 1f at 350; 0.25f at 650
            },
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart
        )
    )
    val dot3Alpha by infiniteTransition.animateFloat(
        initialValue = 0.25f, targetValue = 1f, label = "d3",
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation  = androidx.compose.animation.core.keyframes {
                durationMillis = 1200
                0.25f at 0; 0.25f at 300; 1f at 500; 0.25f at 800
            },
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart
        )
    )

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment     = Alignment.Bottom
    ) {
        Box(Modifier.padding(end = 8.dp, bottom = 3.dp)) {
            PeerAvatar(peerAvatarUrl, peerName, 36.dp, isOnline = false)
        }
        Box(
            modifier = Modifier
                .shadow(1.dp, bubbleShape)
                .clip(bubbleShape)
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment     = Alignment.CenterVertically
            ) {
                listOf(dot1Alpha, dot2Alpha, dot3Alpha).forEach { alpha ->
                    Box(
                        Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(dotColor.copy(alpha = alpha))
                    )
                }
            }
        }
    }
}

// ─── Deleted Placeholder ──────────────────────────────────────────────────────

@Composable
private fun DeletedMessageBubble(isMine: Boolean) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (isMine) Alignment.End else Alignment.Start) {
        Text("🚫 This message was deleted",
            modifier = Modifier.clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(.70f)))
    }
}

// ─── Read Ticks ───────────────────────────────────────────────────────────────

@Composable
private fun MessageStatusTick(isRead: Boolean) {
    val customColors = LocalCustomColors.current
    val color = if (isRead) customColors.info else MaterialTheme.colorScheme.outline
    Box(Modifier.width(20.dp).height(14.dp)) {
        Icon(Icons.Default.Done, null, tint = color, modifier = Modifier.size(14.dp).offset(0.dp))
        Icon(Icons.Default.Done, if (isRead) "Read" else "Sent", tint = color,
            modifier = Modifier.size(14.dp).offset(x = 5.dp))
    }
}

// ─── Chat Input Bar ───────────────────────────────────────────────────────────

@Composable
private fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    isSendError: Boolean = false,
    windowClass: WindowWidthClass,
    onEmojiClick: () -> Unit
) {
    val kb          = LocalSoftwareKeyboardController.current
    val isTyping    = text.isNotBlank()
    val inputH      = if (windowClass == WindowWidthClass.Compact) 44.dp else 48.dp
    val fabSize     = if (windowClass == WindowWidthClass.Compact) 48.dp else 52.dp
    val btnSize     = if (windowClass == WindowWidthClass.Compact) 36.dp else 40.dp
    val iconSize    = if (windowClass == WindowWidthClass.Compact) 22.dp else 24.dp
    val hPad        = when (windowClass) {
        WindowWidthClass.Compact  -> 6.dp
        WindowWidthClass.Medium   -> 16.dp
        WindowWidthClass.Expanded -> 80.dp
    }

    Surface(
        shadowElevation = 0.dp,
        color = if (isSendError) MaterialTheme.colorScheme.errorContainer.copy(.15f) else Color.Transparent
    ) {
        Column {
            AnimatedVisibility(isSendError) {
                Text("⚠ Failed to send — tap send to retry",
                    modifier = Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onErrorContainer))
            }
            Row(
                modifier              = Modifier.fillMaxWidth().navigationBarsPadding()
                    .padding(horizontal = hPad, vertical = 6.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 3.dp
                ) {
                    Row(Modifier.padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onEmojiClick, modifier = Modifier.size(btnSize)) {
                            Icon(Icons.Default.EmojiEmotions, "Emoji",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(iconSize))
                        }
                        TextField(
                            value = text, onValueChange = onTextChange,
                            modifier = Modifier.weight(1f).defaultMinSize(minHeight = inputH),
                            placeholder = {
                                Text("Message",
                                    color = MaterialTheme.colorScheme.onSurface.copy(.40f),
                                    style = MaterialTheme.typography.bodyMedium)
                            },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor   = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor   = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                cursorColor             = MaterialTheme.colorScheme.primary,
                                focusedTextColor        = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor      = MaterialTheme.colorScheme.onSurface
                            ),
                            textStyle       = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                                imeAction      = ImeAction.Send
                            ),
                            keyboardActions = KeyboardActions(onSend = { onSend(); kb?.hide() }),
                            maxLines = 5
                        )
                        AnimatedVisibility(!isTyping) {
                            IconButton(onClick = {}, Modifier.size(btnSize)) {
                                Icon(Icons.Default.AttachFile, "Attach",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(iconSize).graphicsLayer { rotationZ = -45f })
                            }
                        }
                        AnimatedVisibility(!isTyping) {
                            IconButton(onClick = {}, Modifier.size(btnSize)) {
                                Icon(Icons.Default.CameraAlt, "Camera",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(iconSize))
                            }
                        }
                    }
                }
                FloatingActionButton(
                    onClick        = { if (isTyping) { onSend(); kb?.hide() } },
                    modifier       = Modifier.size(fabSize),
                    shape          = CircleShape,
                    containerColor = Color(0xFF25D366),
                    contentColor   = Color.White,
                    elevation      = FloatingActionButtonDefaults.elevation(4.dp)
                ) {
                    AnimatedContent(isTyping,
                        transitionSpec = {
                            (scaleIn(initialScale = 0.72f) + fadeIn()) togetherWith
                                    (scaleOut(targetScale = 0.72f) + fadeOut())
                        }, label = "fab") { typing ->
                        if (typing) Icon(Icons.AutoMirrored.Filled.Send, "Send", Modifier.size(iconSize))
                        else        Icon(Icons.Default.Mic, "Voice", Modifier.size(iconSize + 2.dp))
                    }
                }
            }
        }
    }
}

// ─── Emoji Bottom Sheet ───────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmojiBottomSheet(
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onEmojiSelected: (String) -> Unit
) {
    val isDark      = isSystemInDarkTheme()
    val sheetBg     = if (isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7)
    val searchBg    = if (isDark) Color(0xFF2C2C2E) else Color(0xFFE5E5EA)
    val activeColor = if (isDark) Color.White       else Color(0xFF007AFF)
    val mutedColor  = Color(0xFF8E8E93)

    var query          by remember { mutableStateOf("") }
    var selectedCatIdx by remember { mutableIntStateOf(0) }

    // Prepend live recent list as category 0
    val allCategories = remember<List<EmojiCategory>> {
        listOf(EmojiCategory("Recent", "🕐", emptyList())) + EMOJI_CATEGORIES
    }

    val displayedEmojis: List<String> = when {
        query.isNotBlank() -> EMOJI_CATEGORIES.flatMap { it.emojis }
            .filter { it.contains(query, ignoreCase = true) }.distinct()
        selectedCatIdx == 0 -> recentEmojis.toList()
        else -> allCategories.getOrNull(selectedCatIdx)?.emojis ?: emptyList()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = sheetBg,
        dragHandle = {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp), Alignment.Center) {
                Box(Modifier.width(36.dp).height(4.dp).clip(CircleShape)
                    .background(if (isDark) Color(0xFF3A3A3C) else Color(0xFFC7C7CC)))
            }
        }
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {

            // ── Search ────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(12.dp)).background(searchBg)
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Search, null, tint = mutedColor, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                TextField(
                    value = query, onValueChange = { query = it },
                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = 40.dp),
                    placeholder = {
                        Text("Search emoji", style = MaterialTheme.typography.bodySmall.copy(color = mutedColor))
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor   = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor   = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor             = activeColor,
                        focusedTextColor        = if (isDark) Color.White else Color.Black,
                        unfocusedTextColor      = if (isDark) Color.White else Color.Black
                    ),
                    textStyle       = MaterialTheme.typography.bodySmall,
                    singleLine      = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
                )
            }

            // ── Category tabs (hidden during search) ──────────────────────
            AnimatedVisibility(query.isBlank()) {
                Column {
                    LazyRow(
                        modifier              = Modifier.fillMaxWidth(),
                        contentPadding        = PaddingValues(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        itemsIndexed(allCategories) { idx, cat ->
                            val selected = idx == selectedCatIdx
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { selectedCatIdx = idx }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text(cat.tabIcon, fontSize = 22.sp)
                                Spacer(Modifier.height(3.dp))
                                Box(
                                    Modifier.height(2.5.dp).width(26.dp).clip(CircleShape)
                                        .background(if (selected) activeColor else Color.Transparent)
                                )
                            }
                        }
                    }
                    HorizontalDivider(
                        color     = if (isDark) Color(0xFF3A3A3C) else Color(0xFFD1D1D6),
                        thickness = 0.5.dp
                    )
                }
            }

            // ── Section label ────────────────────────────────────────────
            val label = if (query.isNotBlank()) "Search results"
            else allCategories.getOrNull(selectedCatIdx)?.label ?: ""
            Text(
                label.uppercase(),
                modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 4.dp),
                style    = MaterialTheme.typography.labelSmall.copy(
                    color         = mutedColor,
                    fontSize      = 11.sp,
                    fontWeight    = FontWeight.SemiBold,
                    letterSpacing = 0.6.sp
                )
            )

            // ── Emoji grid ────────────────────────────────────────────────
            if (displayedEmojis.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(180.dp), Alignment.Center) {
                    Text(
                        if (query.isNotBlank()) "No results for \"$query\"" else "No recent emojis yet",
                        style = MaterialTheme.typography.bodySmall.copy(color = mutedColor)
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns               = GridCells.Adaptive(46.dp),
                    modifier              = Modifier.fillMaxWidth().height(280.dp),
                    contentPadding        = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement   = Arrangement.spacedBy(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    gridItems(displayedEmojis) { emoji ->
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier         = Modifier.size(46.dp).clip(RoundedCornerShape(8.dp))
                                .clickable { onEmojiSelected(emoji) }
                        ) {
                            Text(emoji, fontSize = 26.sp, textAlign = TextAlign.Center)
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

// ─── Wallpaper ────────────────────────────────────────────────────────────────

@Composable
private fun ChatWallpaper() {
    val bg    = MaterialTheme.colorScheme.background
    val dot   = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.60f)
    val scrim = Color.Black.copy(alpha = if (isSystemInDarkTheme()) 0.10f else 0.18f)
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        drawRect(bg)
        val step = 24.dp.toPx(); val r = 1.2.dp.toPx()
        var x = 0f
        while (x < size.width) {
            var y = 0f
            while (y < size.height) { drawCircle(dot, r, Offset(x, y)); y += step }
            x += step
        }
        drawRect(scrim)
    }
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

private fun Date.toTimeString(): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(this)

private fun List<Message>.groupByDate(): Map<String, List<Message>> {
    val today     = Calendar.getInstance()
    val yesterday = Calendar.getInstance().also { it.add(Calendar.DAY_OF_YEAR, -1) }
    val fmt       = SimpleDateFormat("MMM d", Locale.getDefault())
    return groupBy { msg ->
        val date = msg.timestamp?.toDate()
        val cal  = Calendar.getInstance().also { if (date != null) it.time = date }
        when {
            cal.isSameDay(today)     -> "TODAY"
            cal.isSameDay(yesterday) -> "YESTERDAY"
            else                     -> date?.let { fmt.format(it) }?.uppercase() ?: "UNKNOWN"
        }
    }
}

private fun Calendar.isSameDay(other: Calendar): Boolean =
    get(Calendar.YEAR) == other.get(Calendar.YEAR) &&
            get(Calendar.DAY_OF_YEAR) == other.get(Calendar.DAY_OF_YEAR)

// ─── Private emoji helpers (mirror of EmojiData.kt — kept here to guarantee
//     resolution regardless of Kapt / KSP incremental compilation order) ──────

private fun String.isOnlyEmoji(): Boolean {
    if (isBlank()) return false
    val cleaned = trim()
        .replace(Regex("[\u200D\uFE0F\u20E3]"), "")
        .replace(Regex("[\\uD83C][\\uDFFB-\\uDFFF]"), "")
    if (cleaned.isEmpty()) return false
    var i = 0
    while (i < cleaned.length) {
        val cp = cleaned.codePointAt(i)
        val ok = cp in 0x1F300..0x1FAFF || cp in 0x2600..0x27BF ||
                cp in 0x1F900..0x1F9FF || cp in 0xFE00..0xFE0F ||
                cp == 0x200D || Character.isWhitespace(cp) ||
                Character.getType(cp) == Character.OTHER_SYMBOL.toInt()
        if (!ok) return false
        i += Character.charCount(cp)
    }
    return true
}

private fun String.emojiCount(): Int {
    val cleaned = trim()
        .replace(Regex("[\u200D\uFE0F\u20E3]"), "")
        .replace(Regex("[\\uD83C][\\uDFFB-\\uDFFF]"), "")
        .replace(Regex("\\s"), "")
    var count = 0; var i = 0
    while (i < cleaned.length) { count++; i += Character.charCount(cleaned.codePointAt(i)) }
    return count
}
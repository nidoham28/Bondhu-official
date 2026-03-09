package com.nidoham.bondhu.presentation.screen.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nidoham.bondhu.presentation.viewmodel.ChatUiState
import com.nidoham.bondhu.ui.theme.AppColors
import com.nidoham.bondhu.ui.theme.CustomTypography
import com.nidoham.bondhu.ui.theme.LocalCustomColors
import org.nidoham.server.domain.model.Message
import org.nidoham.server.domain.model.MessageType  // Fixed: was Message.ContentType
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

// ─── ChatScreen ───────────────────────────────────────────────────────────────

@Composable
fun ChatScreen(
    uiState: ChatUiState,
    onBack: () -> Unit,
    onInputChanged: (String) -> Unit,
    onSend: () -> Unit,
    isMine: (Message) -> Boolean,
    isReadByPeer: (Message) -> Boolean
) {
    val listState = rememberLazyListState()

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
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
                        if (uiState.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.Center),
                                color    = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            MessageList(
                                messages     = uiState.messages,
                                listState    = listState,
                                isMine       = isMine,
                                isReadByPeer = isReadByPeer,
                                windowClass  = windowClass
                            )
                        }
                    }

                    ChatInputBar(
                        text        = uiState.inputText,
                        onTextChange = onInputChanged,
                        onSend      = onSend,
                        isSendError = uiState.isSendError,
                        windowClass = windowClass
                    )
                }
            }
        }
    }
}

// ─── Top Bar ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    name: String,
    avatarUrl: String,
    isOnline: Boolean,
    lastSeen: String,
    onBack: () -> Unit,
    windowClass: WindowWidthClass
) {
    val avatarSize = if (windowClass == WindowWidthClass.Compact) 40.dp else 48.dp

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor          = MaterialTheme.colorScheme.background,
            titleContentColor       = MaterialTheme.colorScheme.onPrimary,
            navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
            actionIconContentColor  = MaterialTheme.colorScheme.onPrimary
        ),
        navigationIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                PeerAvatar(
                    avatarUrl = avatarUrl,
                    name      = name,
                    size      = avatarSize,
                    isOnline  = isOnline
                )
                Spacer(Modifier.width(8.dp))
            }
        },
        title = {
            Column {
                Text(
                    text      = name.ifBlank { "Loading…" },
                    style     = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        color      = MaterialTheme.colorScheme.onBackground
                    ),
                    maxLines  = 1,
                    overflow  = TextOverflow.Ellipsis
                )
                Text(
                    text  = if (isOnline) "online" else lastSeen.ifBlank { "last seen recently" },
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.80f)
                    )
                )
            }
        },
        actions = {
            IconButton(onClick = {}) {
                Icon(Icons.Default.Call, "Call", tint = MaterialTheme.colorScheme.onBackground)
            }
            IconButton(onClick = {}) {
                Icon(Icons.Default.MoreVert, "More", tint = MaterialTheme.colorScheme.onBackground)
            }
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
                model              = avatarUrl,
                contentDescription = name,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.size(size).clip(CircleShape)
            )
        } else {
            Box(
                modifier         = Modifier.size(size).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text  = name.take(1).uppercase(),
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
                modifier = Modifier
                    .size(size * 0.28f)
                    .clip(CircleShape)
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
    windowClass: WindowWidthClass
) {
    val hPadding = when (windowClass) {
        WindowWidthClass.Compact  -> 8.dp
        WindowWidthClass.Medium   -> 24.dp
        WindowWidthClass.Expanded -> 80.dp
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val bubbleMax = when (windowClass) {
            WindowWidthClass.Compact  -> maxWidth * 0.76f
            WindowWidthClass.Medium   -> maxWidth * 0.60f
            WindowWidthClass.Expanded -> maxWidth * 0.45f
        }

        LazyColumn(
            state           = listState,
            modifier        = Modifier.fillMaxSize().padding(horizontal = hPadding),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding  = PaddingValues(vertical = 8.dp)
        ) {
            val grouped = messages.groupByDate()
            grouped.forEach { (label, dayMessages) ->
                item(key = "date_$label") { DateChip(label = label) }

                items(dayMessages, key = { it.messageId }) { msg ->
                    var appeared by remember { mutableStateOf(false) }
                    LaunchedEffect(msg.messageId) { appeared = true }

                    AnimatedVisibility(
                        visible = appeared,
                        enter   = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn()
                    ) {
                        MessageBubble(
                            message        = msg,
                            isMine         = isMine(msg),
                            isReadByPeer   = isReadByPeer(msg),
                            bubbleMaxWidth = bubbleMax
                        )
                    }
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
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
                .padding(horizontal = 10.dp, vertical = 3.dp),
            style    = CustomTypography.overline.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
    }
}

// ─── Message Bubble ───────────────────────────────────────────────────────────

@Composable
private fun MessageBubble(
    message: Message,
    isMine: Boolean,
    isReadByPeer: Boolean,
    bubbleMaxWidth: Dp
) {
    // Fixed: was message.isDeleted — correct field name is message.deleted (Boolean in model)
    if (message.deleted) {
        DeletedMessageBubble(isMine = isMine)
        return
    }

    val bubbleColor    = if (isMine) AppColors.LightGreen else MaterialTheme.colorScheme.surface
    val textColor      = if (isMine) Color.White else MaterialTheme.colorScheme.onSurface
    val timestampColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val alignment      = if (isMine) Alignment.End else Alignment.Start
    val bubbleShape    = if (isMine)
        RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
    else
        RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp)

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        Box(
            modifier = Modifier
                .widthIn(min = 80.dp, max = bubbleMaxWidth)
                .shadow(elevation = 1.dp, shape = bubbleShape)
                .clip(bubbleShape)
                .background(bubbleColor)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Column {
                // Fixed: was Message.ContentType.IMAGE etc — correct type is MessageType (domain enum)
                // Fixed: was message.text — correct field name is message.content (from Message model)
                when (message.toType()) {
                    MessageType.IMAGE -> MediaLabel("📷 Photo", textColor)
                    else              -> if (message.content.isNotEmpty()) {
                        Text(
                            text  = message.content,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color    = textColor,
                                fontSize = 15.sp
                            )
                        )
                    }
                }

                Spacer(Modifier.height(2.dp))

                Row(
                    modifier             = Modifier.align(Alignment.End),
                    verticalAlignment    = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // Fixed: was message.isEdited() called as a function —
                    //        it's a computed property (val isEdited: Boolean) in the model
                    if (message.isEdited) {
                        Text(
                            "edited",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = timestampColor, fontSize = 10.sp
                            )
                        )
                    }

                    Text(
                        text  = message.timestamp?.toDate()?.toTimeString() ?: "Sending…",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = timestampColor, fontSize = 11.sp
                        )
                    )

                    if (isMine && message.timestamp != null) {
                        MessageStatusTick(isRead = isReadByPeer)
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaLabel(text: String, color: Color) {
    Text(
        text  = text,
        style = MaterialTheme.typography.bodyMedium.copy(
            color    = color.copy(alpha = 0.80f),
            fontSize = 15.sp
        )
    )
}

// ─── Deleted Placeholder ──────────────────────────────────────────────────────

@Composable
private fun DeletedMessageBubble(isMine: Boolean) {
    Column(
        modifier            = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isMine) Alignment.End else Alignment.Start
    ) {
        Text(
            text     = "🚫 This message was deleted",
            modifier = Modifier
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            style    = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.70f)
            )
        )
    }
}

// ─── Read Receipt Ticks ───────────────────────────────────────────────────────

@Composable
private fun MessageStatusTick(isRead: Boolean) {
    val customColors = LocalCustomColors.current
    val tickColor    = if (isRead) customColors.info else MaterialTheme.colorScheme.outline

    Box(modifier = Modifier.width(20.dp).height(14.dp)) {
        Icon(
            Icons.Default.Done, null, tint = tickColor,
            modifier = Modifier.size(14.dp).offset(x = 0.dp)
        )
        Icon(
            Icons.Default.Done, if (isRead) "Read" else "Sent", tint = tickColor,
            modifier = Modifier.size(14.dp).offset(x = 5.dp)
        )
    }
}

// ─── Chat Input Bar ───────────────────────────────────────────────────────────

@Composable
private fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    isSendError: Boolean = false,
    windowClass: WindowWidthClass
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val isTyping           = text.isNotBlank()

    val inputMinHeight = if (windowClass == WindowWidthClass.Compact) 44.dp else 48.dp
    val fabSize        = if (windowClass == WindowWidthClass.Compact) 48.dp else 52.dp
    val iconBtnSize    = if (windowClass == WindowWidthClass.Compact) 36.dp else 40.dp
    val iconSize       = if (windowClass == WindowWidthClass.Compact) 22.dp else 24.dp
    val hPadding       = when (windowClass) {
        WindowWidthClass.Compact  -> 6.dp
        WindowWidthClass.Medium   -> 16.dp
        WindowWidthClass.Expanded -> 80.dp
    }

    val pillBg   = MaterialTheme.colorScheme.surface
    val fabGreen = Color(0xFF25D366)

    Surface(
        shadowElevation = 0.dp,
        color = if (isSendError) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
        else Color.Transparent
    ) {
        Column {
            AnimatedVisibility(visible = isSendError) {
                Text(
                    text     = "⚠ Failed to send — tap send to retry",
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    style    = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                )
            }

            Row(
                modifier              = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = hPadding, vertical = 6.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Surface(
                    modifier         = Modifier.weight(1f),
                    shape            = RoundedCornerShape(24.dp),
                    color            = pillBg,
                    shadowElevation  = 3.dp,
                    tonalElevation   = 0.dp
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier          = Modifier.padding(horizontal = 2.dp, vertical = 0.dp)
                    ) {
                        IconButton(onClick = {}, modifier = Modifier.size(iconBtnSize)) {
                            Icon(
                                Icons.Default.EmojiEmotions, "Emoji",
                                tint     = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(iconSize)
                            )
                        }

                        TextField(
                            value         = text,
                            onValueChange = onTextChange,
                            modifier      = Modifier.weight(1f).defaultMinSize(minHeight = inputMinHeight),
                            placeholder   = {
                                Text(
                                    "Message",
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.40f),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            },
                            colors        = TextFieldDefaults.colors(
                                focusedContainerColor   = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor   = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                cursorColor             = MaterialTheme.colorScheme.primary,
                                focusedTextColor        = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor      = MaterialTheme.colorScheme.onSurface
                            ),
                            textStyle     = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                                imeAction      = ImeAction.Send
                            ),
                            keyboardActions = KeyboardActions(
                                onSend = { onSend(); keyboardController?.hide() }
                            ),
                            maxLines = 5
                        )

                        AnimatedVisibility(visible = !isTyping) {
                            IconButton(onClick = {}, modifier = Modifier.size(iconBtnSize)) {
                                Icon(
                                    Icons.Default.AttachFile, "Attach",
                                    tint     = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(iconSize).graphicsLayer { rotationZ = -45f }
                                )
                            }
                        }

                        AnimatedVisibility(visible = !isTyping) {
                            IconButton(onClick = {}, modifier = Modifier.size(iconBtnSize)) {
                                Icon(
                                    Icons.Default.CameraAlt, "Camera",
                                    tint     = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(iconSize)
                                )
                            }
                        }
                    }
                }

                FloatingActionButton(
                    onClick        = { if (isTyping) { onSend(); keyboardController?.hide() } },
                    modifier       = Modifier.size(fabSize),
                    shape          = CircleShape,
                    containerColor = fabGreen,
                    contentColor   = Color.White,
                    elevation      = FloatingActionButtonDefaults.elevation(4.dp)
                ) {
                    AnimatedContent(
                        targetState  = isTyping,
                        transitionSpec = {
                            (scaleIn(initialScale = 0.72f) + fadeIn()) togetherWith
                                    (scaleOut(targetScale = 0.72f) + fadeOut())
                        },
                        label = "fab_icon"
                    ) { typing ->
                        if (typing) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Send", modifier = Modifier.size(iconSize))
                        } else {
                            Icon(Icons.Default.Mic, "Voice message", modifier = Modifier.size(iconSize + 2.dp))
                        }
                    }
                }
            }
        }
    }
}

// ─── Wallpaper ────────────────────────────────────────────────────────────────

@Composable
private fun ChatWallpaper() {
    val bgColor    = MaterialTheme.colorScheme.background
    val dotColor   = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.60f)
    val isDark     = isSystemInDarkTheme()
    val scrimColor = Color.Black.copy(alpha = if (isDark) 0.10f else 0.18f)

    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(color = bgColor)
        val step      = 24.dp.toPx()
        val dotRadius = 1.2.dp.toPx()
        var x = 0f
        while (x < size.width) {
            var y = 0f
            while (y < size.height) {
                drawCircle(color = dotColor, radius = dotRadius, center = Offset(x, y))
                y += step
            }
            x += step
        }
        drawRect(color = scrimColor)
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
            else                     -> fmt.format(date).uppercase()
        }
    }
}

private fun Calendar.isSameDay(other: Calendar): Boolean =
    get(Calendar.YEAR) == other.get(Calendar.YEAR) &&
            get(Calendar.DAY_OF_YEAR) == other.get(Calendar.DAY_OF_YEAR)
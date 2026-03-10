package com.nidoham.bondhu.presentation.component.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Adaptive message input bar rendered at the bottom of the conversation screen.
 *
 * Layout (left to right):
 * - **Emoji button** — opens the emoji picker sheet.
 * - **Text field** — multi-line draft input, capped at 5 lines.
 * - **Attach / Camera buttons** — visible only when the draft is empty;
 *   animate out when the user starts typing.
 * - **FAB (Send / Mic)** — switches between a Send icon when text is present
 *   and a Mic icon when the field is empty, using a scale-and-fade crossfade.
 *
 * An optional error banner is shown above the row when [isSendError] is `true`,
 * prompting the user to retry.
 *
 * All dimensions (input height, FAB size, horizontal padding) respond to
 * [windowSizeClass] so the bar feels appropriately proportioned on phones,
 * large-screen phones, and tablets.
 *
 * @param messageText       The current draft text value.
 * @param onMessageTextChange Invoked with the updated text on every keystroke.
 * @param onSend            Triggers message delivery; only called when [messageText] is non-blank.
 * @param isSendError       When `true`, displays a retry banner above the input row.
 * @param windowSizeClass   Controls adaptive sizing and horizontal padding.
 * @param onEmojiClick      Invoked when the emoji icon button is tapped.
 */
@Composable
fun ChatInputBar(
    messageText: String,
    onMessageTextChange: (String) -> Unit,
    onSend: () -> Unit,
    isSendError: Boolean = false,
    windowSizeClass: WindowWidthClass,
    onEmojiClick: () -> Unit
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val hasText = messageText.isNotBlank()

    // Adaptive dimensions based on window size class
    val inputFieldMinHeight = if (windowSizeClass == WindowWidthClass.Compact) 44.dp else 48.dp
    val actionFabSize = if (windowSizeClass == WindowWidthClass.Compact) 48.dp else 52.dp
    val iconButtonSize = if (windowSizeClass == WindowWidthClass.Compact) 36.dp else 40.dp
    val vectorIconSize = if (windowSizeClass == WindowWidthClass.Compact) 22.dp else 24.dp

    val contentPaddingHorizontal = when (windowSizeClass) {
        WindowWidthClass.Compact -> 6.dp
        WindowWidthClass.Medium -> 16.dp
        WindowWidthClass.Expanded -> 80.dp
    }

    // Design constants
    val sendButtonBackgroundColor = Color(0xFF25D366)

    Surface(
        shadowElevation = 0.dp,
        color = if (isSendError) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
        else Color.Transparent
    ) {
        Column {
            // Error retry banner
            AnimatedVisibility(visible = isSendError) {
                Text(
                    text = "⚠ Failed to send — tap send to retry",
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = contentPaddingHorizontal, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // ── Input Pill ────────────────────────────────────────────────
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 3.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onEmojiClick,
                            modifier = Modifier.size(iconButtonSize)
                        ) {
                            Icon(
                                imageVector = Icons.Default.EmojiEmotions,
                                contentDescription = "Open emoji picker",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(vectorIconSize)
                            )
                        }

                        TextField(
                            value = messageText,
                            onValueChange = onMessageTextChange,
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = inputFieldMinHeight),
                            placeholder = {
                                Text(
                                    "Message",
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.40f),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                cursorColor = MaterialTheme.colorScheme.primary,
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                            ),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                                imeAction = ImeAction.Send
                            ),
                            keyboardActions = KeyboardActions(onSend = {
                                onSend()
                                keyboardController?.hide()
                            }),
                            maxLines = 5
                        )

                        // Attach and Camera buttons slide out when the user starts typing.
                        AnimatedVisibility(visible = !hasText) {
                            IconButton(
                                onClick = { /* TODO: attachment picker */ },
                                modifier = Modifier.size(iconButtonSize)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AttachFile,
                                    contentDescription = "Attach file",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .size(vectorIconSize)
                                        .graphicsLayer { rotationZ = -45f }
                                )
                            }
                        }
                        AnimatedVisibility(visible = !hasText) {
                            IconButton(
                                onClick = { /* TODO: camera capture */ },
                                modifier = Modifier.size(iconButtonSize)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CameraAlt,
                                    contentDescription = "Open camera",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(vectorIconSize)
                                )
                            }
                        }
                    }
                }

                // ── Send / Mic FAB ────────────────────────────────────────────
                FloatingActionButton(
                    onClick = {
                        if (hasText) {
                            onSend()
                            keyboardController?.hide()
                        }
                    },
                    modifier = Modifier.size(actionFabSize),
                    shape = CircleShape,
                    containerColor = sendButtonBackgroundColor,
                    contentColor = Color.White,
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp)
                ) {
                    AnimatedContent(
                        targetState = hasText,
                        transitionSpec = {
                            (scaleIn(initialScale = 0.72f) + fadeIn()) togetherWith
                                    (scaleOut(targetScale = 0.72f) + fadeOut())
                        },
                        label = "SendMicIconToggle"
                    ) { showSendIcon ->
                        if (showSendIcon) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send message",
                                modifier = Modifier.size(vectorIconSize)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "Record voice message",
                                modifier = Modifier.size(vectorIconSize + 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
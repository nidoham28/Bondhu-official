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
 * Layout (left → right):
 * - **Emoji button** — opens the emoji picker sheet.
 * - **Text field** — multi-line draft input, capped at 5 lines.
 * - **Attach + Camera** — shown as a single animated unit; hidden while typing.
 * - **FAB** — crossfades between Send (text present) and Mic (empty field).
 *
 * An error banner slides in above the row when [isSendError] is `true`.
 * All sizing adapts to [windowSizeClass].
 *
 * @param messageText         Current draft value.
 * @param onMessageTextChange Emitted on every keystroke.
 * @param onSend              Called when the user confirms sending; only fires on non-blank text.
 * @param isSendError         Shows a retry banner when `true`.
 * @param windowSizeClass     Drives adaptive sizing and horizontal margins.
 * @param onEmojiClick        Called when the emoji button is tapped.
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

    val iconSize = if (windowSizeClass == WindowWidthClass.Compact) 20.dp else 22.dp
    val iconButtonSize = if (windowSizeClass == WindowWidthClass.Compact) 36.dp else 40.dp
    val fabSize = if (windowSizeClass == WindowWidthClass.Compact) 44.dp else 48.dp

    val horizontalPadding = when (windowSizeClass) {
        WindowWidthClass.Compact -> 8.dp
        WindowWidthClass.Medium -> 16.dp
        WindowWidthClass.Expanded -> 80.dp
    }

    Surface(
        color = if (isSendError)
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.12f)
        else Color.Transparent
    ) {
        Column {
            // ── Error banner ──────────────────────────────────────────────────
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

            // ── Input row ─────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = horizontalPadding, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // ── Input pill ────────────────────────────────────────────────
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    shadowElevation = 0.dp, // flat inside the bar; bar itself can have elevation
                    tonalElevation = 2.dp
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        // Emoji
                        IconButton(
                            onClick = onEmojiClick,
                            modifier = Modifier.size(iconButtonSize)
                        ) {
                            Icon(
                                imageVector = Icons.Default.EmojiEmotions,
                                contentDescription = "Open emoji picker",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(iconSize)
                            )
                        }

                        // Text field — no defaultMinSize so the pill stays compact
                        TextField(
                            value = messageText,
                            onValueChange = onMessageTextChange,
                            modifier = Modifier.weight(1f),
                            placeholder = {
                                Text(
                                    "Message",
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
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
                                if (hasText) {
                                    onSend()
                                    keyboardController?.hide()
                                }
                            }),
                            maxLines = 5,
                            singleLine = false
                        )

                        // Attach + Camera — one visibility block so they animate as a unit
                        AnimatedVisibility(
                            visible = !hasText,
                            enter = scaleIn(initialScale = 0.8f) + fadeIn(),
                            exit = scaleOut(targetScale = 0.8f) + fadeOut()
                        ) {
                            Row {
                                IconButton(
                                    onClick = { /* TODO: attachment picker */ },
                                    modifier = Modifier.size(iconButtonSize)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AttachFile,
                                        contentDescription = "Attach file",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .size(iconSize)
                                            .graphicsLayer { rotationZ = -45f }
                                    )
                                }
                                IconButton(
                                    onClick = { /* TODO: camera capture */ },
                                    modifier = Modifier.size(iconButtonSize)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CameraAlt,
                                        contentDescription = "Open camera",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(iconSize)
                                    )
                                }
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
                    modifier = Modifier.size(fabSize),
                    shape = CircleShape,
                    containerColor = Color(0xFF25D366),
                    contentColor = Color.White,
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp)
                ) {
                    AnimatedContent(
                        targetState = hasText,
                        transitionSpec = {
                            (scaleIn(initialScale = 0.72f) + fadeIn()) togetherWith
                                    (scaleOut(targetScale = 0.72f) + fadeOut())
                        },
                        label = "SendMicIconToggle"
                    ) { showSend ->
                        // BUG FIX: was always rendering Send regardless of state
                        if (showSend) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send message",
                                modifier = Modifier.size(iconSize)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "Record voice message",
                                modifier = Modifier.size(iconSize)
                            )
                        }
                    }
                }
            }
        }
    }
}
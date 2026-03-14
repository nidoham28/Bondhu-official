package com.nidoham.bondhu.presentation.component.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Replacement bubble rendered when a message has been soft-deleted.
 *
 * Displays a standard "This message was deleted" notice aligned to the same
 * side as the original message (right for sent, left for received), preserving
 * the visual rhythm of the conversation without revealing the original content.
 *
 * The scrim background keeps the notice legible over any wallpaper without
 * requiring theme-aware color tokens.
 *
 * @param isMine `true` when the deleted message was sent by the local user;
 *               controls horizontal alignment.
 */
@Composable
internal fun DeletedMessageBubble(isMine: Boolean) {
    Column(
        modifier            = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isMine) Alignment.End else Alignment.Start
    ) {
        Text(
            text     = "🚫 This message was deleted",
            modifier = Modifier
                .clip(MaterialTheme.shapes.medium)
                .background(Color.Black.copy(alpha = 0.25f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall.copy(
                color = Color.White.copy(alpha = 0.80f)
            )
        )
    }
}
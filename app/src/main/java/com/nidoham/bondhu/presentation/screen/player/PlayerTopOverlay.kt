package com.nidoham.bondhu.presentation.screen.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Top gradient bar rendered inside the controls overlay.
 *
 * Contains a back/close button, the video title, and a three-dot overflow menu
 * icon. A vertical gradient fades from semi-opaque black to transparent so the
 * video beneath remains visible at the bottom edge of this bar.
 *
 * In portrait mode the back button pops the activity. In landscape mode it
 * collapses to portrait (fullscreen toggle), so the same [onBack] callback is
 * used in both orientations — the caller decides what it does.
 *
 * @param title       Video title shown in the bar; hidden when blank.
 * @param onBack      Called when the back/close icon is tapped.
 * @param isLandscape When true, applies [statusBarsPadding] for immersive layout.
 * @param modifier    Applied to the root [Box].
 */
@Composable
fun PlayerTopOverlay(
    title       : String,
    onBack      : () -> Unit,
    isLandscape : Boolean,
    modifier    : Modifier = Modifier,
) {
    val gradient = Brush.verticalGradient(
        colors = listOf(Color(0xCC000000), Color.Transparent),
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(gradient)
            .then(if (isLandscape) Modifier.statusBarsPadding() else Modifier)
            .padding(bottom = 16.dp),
    ) {
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector        = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint               = Color.White,
                )
            }

            if (title.isNotBlank()) {
                Text(
                    text       = title,
                    style      = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color      = Color.White,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                    modifier   = Modifier
                        .weight(1f)
                        .padding(end = 4.dp),
                )
            } else {
                Box(Modifier.weight(1f))
            }

            IconButton(onClick = { /* overflow menu — wire to sheet if needed */ }) {
                Icon(
                    imageVector        = Icons.Rounded.MoreVert,
                    contentDescription = "More options",
                    tint               = Color.White,
                )
            }
        }
    }
}
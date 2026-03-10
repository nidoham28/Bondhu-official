package com.nidoham.bondhu.presentation.component.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nidoham.bondhu.ui.theme.CustomTypography

/**
 * A centred, pill-shaped chip that displays a date-group label (e.g. "TODAY",
 * "YESTERDAY", "JAN 5") between message runs in the conversation list.
 *
 * The chip uses a semi-transparent black scrim so it remains legible over any
 * wallpaper colour or image, without requiring knowledge of the background.
 *
 * @param label The date string to display. Callers should pass an uppercased
 *              value; no transformation is applied here.
 */
@Composable
internal fun DateChip(label: String) {
    Box(
        modifier         = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text     = label,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.30f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            style = CustomTypography.overline.copy(
                color      = Color.White,
                fontSize   = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        )
    }
}
package com.nidoham.bondhu.presentation.screen.main.tab

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MessageScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Placeholder UI
        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(64.dp))
    }
}
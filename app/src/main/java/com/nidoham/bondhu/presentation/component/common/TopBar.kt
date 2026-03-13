package com.nidoham.bondhu.presentation.component.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Top app bar with a leading Add action, a centered title, and a trailing
 * Notifications action.
 *
 * Layout: [Add]  title (centered)  [Notifications]
 *
 * The container is transparent so the parent surface colour shows through,
 * keeping the bar consistent across light, dark, and dynamic-colour themes.
 *
 * @param title               Text displayed as the bar heading.
 * @param onAddClick          Callback for the leading Add action.
 * @param onNotificationClick Callback for the trailing Notifications action.
 * @param modifier            Optional modifier applied to the bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBar(
    title               : String,
    onAddClick          : () -> Unit,
    onNotificationClick : () -> Unit,
    modifier            : Modifier = Modifier
) {
    CenterAlignedTopAppBar(
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onAddClick) {
                Icon(
                    imageVector        = Icons.Default.Add,
                    contentDescription = "Add new item",
                    tint               = MaterialTheme.colorScheme.primary
                )
            }
        },
        title = {
            Text(
                text  = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
        },
        actions = {
            IconButton(onClick = onNotificationClick) {
                Icon(
                    imageVector        = Icons.Default.Notifications,
                    contentDescription = "Notifications",
                    tint               = MaterialTheme.colorScheme.primary
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor         = Color.Transparent,
            scrolledContainerColor = Color.Transparent
        )
    )
}
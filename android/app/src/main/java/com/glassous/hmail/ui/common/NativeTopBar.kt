package com.glassous.hmail.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.glassous.hmail.MailIcon

/** Fixed, compact MD3 bar; its background stays transparent, including while scrolling. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: String = "back",
    navigationDescription: String = "返回",
    onNavigationClick: (() -> Unit)? = null,
    actions: List<GlassAction> = emptyList()
) {
    TopAppBar(
        modifier = modifier,
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (onNavigationClick != null) Surface(
                modifier = Modifier.padding(start = 8.dp),
                shape = RoundedCornerShape(percent = 50),
                color = MaterialTheme.colorScheme.background.copy(alpha = 1f)
            ) {
                IconButton(onClick = onNavigationClick) {
                    MailIcon(navigationIcon, contentDescription = navigationDescription)
                }
            }
        },
        actions = {
            if (actions.isNotEmpty()) Surface(
                modifier = Modifier.padding(end = 8.dp),
                shape = RoundedCornerShape(percent = 50),
                color = MaterialTheme.colorScheme.background.copy(alpha = 1f)
            ) {
                Row {
                    actions.forEach { action ->
                        if (action.icon != null) IconButton(onClick = action.onClick, enabled = action.enabled) {
                            MailIcon(action.icon, contentDescription = action.label)
                        } else TextButton(onClick = action.onClick, enabled = action.enabled) {
                            Text(action.label, maxLines = 1)
                        }
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent
        )
    )
}

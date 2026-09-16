package com.glassous.hmail.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.glassous.hmail.MailIcon

/** 返回键 + 标题合用的胶囊最大宽度，与主页玻璃顶栏的尺度保持一致。 */
private val BarMaxWidth = 220.dp

/** Fixed, compact MD3 bar; its background stays transparent, including while scrolling. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: String = "back",
    navigationDescription: String = "返回",
    onNavigationClick: (() -> Unit)? = null,
    actions: List<GlassAction> = emptyList(),
    /** 返回键图标的修饰符（共享元素过渡用）。 */
    navigationIconModifier: Modifier = Modifier,
    /** 标题文字的修饰符（共享元素过渡用）。 */
    titleTextModifier: Modifier = Modifier
) {
    TopAppBar(
        modifier = modifier,
        // 标题不再孤立在返回键右侧：返回键与标题合并进同一块不透明胶囊，整块随标题长度自适应。
        title = {
            Surface(
                modifier = Modifier.widthIn(max = BarMaxWidth),
                shape = RoundedCornerShape(percent = 50),
                color = MaterialTheme.colorScheme.background.copy(alpha = 1f)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onNavigationClick != null) {
                        IconButton(onClick = onNavigationClick) {
                            MailIcon(
                                navigationIcon,
                                contentDescription = navigationDescription,
                                modifier = navigationIconModifier
                            )
                        }
                    }
                    Text(
                        text = title,
                        // 共享元素边界对齐文字本身：padding 放在过渡修饰符外侧。
                        modifier = Modifier.padding(
                            start = if (onNavigationClick == null) 20.dp else 0.dp,
                            end = 20.dp
                        ).then(titleTextModifier),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
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

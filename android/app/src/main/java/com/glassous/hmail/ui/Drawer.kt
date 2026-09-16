package com.glassous.hmail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glassous.hmail.MailIcon
import com.glassous.hmail.MailModel
import com.glassous.hmail.MailboxState
import com.glassous.hmail.folders
import com.glassous.hmail.ui.common.SecondaryAction
import com.glassous.hmail.ui.theme.Brand
import com.glassous.hmail.ui.theme.HmailTheme

/** 抽屉宽度：与重构前一致，最多 336dp 且至少给主界面留 48dp。 */
@Composable
fun drawerWidth() = minOf(336.dp, LocalConfiguration.current.screenWidthDp.dp - 48.dp)

@Composable
fun DrawerContent(
    model: MailModel,
    onClose: () -> Unit,
    onNavigate: (String) -> Unit
) {
    // 抽屉不在 NavHost 内，自身不会随 model.revision 重组；不订阅的话账号、文件夹与标签
    // 会停留在首次组合（会话还在恢复、accounts 为空）时的快照，表现为一直显示"尚未连接邮箱"。
    revisionOf(model)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = 20.dp, bottom = 12.dp)
    ) {
        Text(
            text = "Hmail",
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            color = Brand,
            fontSize = 27.sp,
            fontWeight = FontWeight.Bold
        )
        AccountSelector(model.mailbox, { model.switchAccount(it) }, model::retryAccounts)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.padding(horizontal = 20.dp)) {
            SecondaryAction("连接邮箱") {
                onClose()
                onNavigate(Routes.Connect)
            }
        }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            folders.forEach { (key, name) ->
                DrawerItem(icon = key, label = name, selected = model.folder == key) {
                    onClose()
                    model.chooseFolder(key)
                }
            }
            if (model.mailbox.labelsLoading) {
                Text("正在加载标签", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = HmailTheme.colors.muted)
            }
            model.mailbox.labelsError?.let { error ->
                Text(error, Modifier.clickable { model.refreshLabels() }.padding(24.dp), color = MaterialTheme.colorScheme.error)
            }
            val userLabels = model.labels.filter { it.type == "user" }
            if (userLabels.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = HmailTheme.colors.outline)
                userLabels.forEach { label ->
                    DrawerItem(icon = "tag", label = label.name, selected = model.folder == label.id) {
                        onClose()
                        model.chooseFolder(label.id)
                    }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = HmailTheme.colors.outline)
            DrawerItem(icon = "tag", label = "管理标签", selected = false) {
                onClose()
                onNavigate(Routes.Labels)
            }
            DrawerItem(icon = "settings", label = "设置", selected = false) {
                onClose()
                onNavigate(Routes.Settings)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun AccountSelector(state: MailboxState, onSelect: (String) -> Unit, onRetry: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current = state.accounts.firstOrNull { it.id == state.active }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        if (state.accountsError != null) {
            Text(state.accountsError, Modifier.clickable(onClick = onRetry).padding(vertical = 8.dp), color = MaterialTheme.colorScheme.error)
        }
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(HmailTheme.colors.field)
                    .clickable(enabled = state.accounts.isNotEmpty()) { expanded = true }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = current?.email ?: when {
                        state.accountsLoading -> "正在加载邮箱"
                        state.accountsError != null -> "邮箱加载失败"
                        else -> "尚未连接邮箱"
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                MailIcon("chevron_down", contentDescription = "切换邮箱", tint = HmailTheme.colors.muted)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                state.accounts.forEach { account ->
                    DropdownMenuItem(
                        text = { Text(account.email, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            expanded = false
                            if (account.id != state.active) onSelect(account.id)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerItem(icon: String?, label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .height(48.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(if (selected) HmailTheme.colors.selected else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            MailIcon(icon, tint = HmailTheme.colors.muted)
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text = label,
            color = if (selected) HmailTheme.colors.onSelected else MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

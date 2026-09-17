package com.glassous.hmail.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glassous.hmail.ALL_ACCOUNTS
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
    var accountsExpanded by remember { mutableStateOf(false) }
    // 展开的邮箱列表要浮在下方组件之上而不是把它们推走：正文按「折叠态头部高度」整体下移，
    // 头部（标题 + 选择器）单独一层叠在正文之上，展开时只覆盖正文、不参与正文布局。
    var headerHeight by remember { mutableStateOf(0) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = 20.dp, bottom = 12.dp)
    ) {
        // 正文先声明：画在下层，且只在顶部留出折叠态头部的高度。
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(with(LocalDensity.current) { headerHeight.toDp() }))
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
                // 统一视图下标签属于各邮箱私有，隐藏标签区避免跨账户歧义。
                if (!model.allAccounts) {
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
                }
                HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = HmailTheme.colors.outline)
                if (!model.allAccounts) {
                    DrawerItem(icon = "tag", label = "管理标签", selected = false) {
                        onClose()
                        onNavigate(Routes.Labels)
                    }
                }
                DrawerItem(icon = "settings", label = "设置", selected = false) {
                    onClose()
                    onNavigate(Routes.Settings)
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        // 头部后声明：画在上层并优先接收触摸。高度只记录一次：首次布局必定是折叠态、没有动画；
        // 之后展开/收起的过渡帧也会触发 onSizeChanged，若跟着更新会把正文顶来顶去。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .onSizeChanged { if (!accountsExpanded && headerHeight == 0) headerHeight = it.height }
        ) {
            Text(
                text = "Hmail",
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                color = Brand,
                fontSize = 27.sp,
                fontWeight = FontWeight.Bold
            )
            AccountSelector(
                state = model.mailbox,
                expanded = accountsExpanded,
                onExpandedChange = { accountsExpanded = it },
                onSelect = { model.switchAccount(it) },
                onRetry = model::retryAccounts
            )
        }
    }
}

/**
 * 账户选择器：收起时只显示当前邮箱，点击该行即展开；展开后列出全部邮箱
 * （邮箱地址 + 选中勾选），点某一项即切换并收起。整块区域不显示波浪纹。
 * 展开状态由调用方持有，便于把展开的内容叠在正文之上而不是把正文推走。
 */
@Composable
private fun AccountSelector(
    state: MailboxState,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    onRetry: () -> Unit
) {
    // 展开/折叠区域不显示波浪纹，整块卡片保持安静。
    val interaction = remember { MutableInteractionSource() }
    // 连接两个以上邮箱时才提供「全部账户」统一视图，与 Web 端保持一致。
    val options = buildList {
        if (state.accounts.size >= 2) add(AccountOption(ALL_ACCOUNTS, "全部账户"))
        state.accounts.forEach { account -> add(AccountOption(account.id, account.email)) }
    }
    val rows = if (expanded) options else options.filter { it.id == state.active }
    val placeholder = when {
        state.active == ALL_ACCOUNTS -> "全部账户"
        state.accountsLoading -> "正在加载邮箱"
        state.accountsError != null -> "邮箱加载失败"
        else -> "尚未连接邮箱"
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        state.accountsError?.let { message ->
            Text(message, Modifier.clickable(onClick = onRetry).padding(vertical = 8.dp), color = MaterialTheme.colorScheme.error)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(HmailTheme.colors.field)
                .animateContentSize()
                .padding(vertical = 4.dp)
        ) {
            if (rows.isEmpty()) {
                Text(
                    text = placeholder,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            rows.forEach { option ->
                AccountOptionRow(
                    label = option.label,
                    highlighted = expanded && option.id == state.active,
                    interaction = interaction,
                    onClick = {
                        if (expanded) {
                            onExpandedChange(false)
                            if (option.id != state.active) onSelect(option.id)
                        } else {
                            onExpandedChange(true)
                        }
                    }
                )
            }
        }
    }
}

private data class AccountOption(val id: String, val label: String)

@Composable
private fun AccountOptionRow(label: String, highlighted: Boolean, interaction: MutableInteractionSource, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (highlighted) HmailTheme.colors.selected else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (highlighted) HmailTheme.colors.onSelected else MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (highlighted) MailIcon("check", contentDescription = "当前邮箱", modifier = Modifier.size(15.dp), tint = HmailTheme.colors.onSelected)
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

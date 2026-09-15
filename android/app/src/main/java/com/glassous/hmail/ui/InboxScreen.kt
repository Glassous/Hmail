package com.glassous.hmail.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glassous.hmail.MailIcon
import com.glassous.hmail.MailModel
import com.glassous.hmail.folders
import com.glassous.hmail.shortDate
import com.glassous.hmail.str
import com.glassous.hmail.ui.common.GlassAction
import com.glassous.hmail.ui.common.GlassTopBar
import com.glassous.hmail.ui.common.TopBarExpandedHeight
import com.glassous.hmail.ui.common.TopBarTopGap
import com.glassous.hmail.ui.common.bottomInset
import com.glassous.hmail.ui.common.rememberCollapseFraction
import com.glassous.hmail.ui.common.topInset
import com.glassous.hmail.ui.glass.GlassPill
import com.glassous.hmail.ui.glass.GlassSurface
import com.glassous.hmail.ui.glass.glassSource
import com.glassous.hmail.ui.glass.rememberGlassBackdrop
import com.glassous.hmail.ui.theme.HmailTheme
import com.kyant.backdrop.backdrops.LayerBackdrop

private val BoxSize = 56.dp
private val BlockGap = 12.dp
private val FabHeight = 56.dp

/** 底部悬浮区总高度：写邮件按钮 + 间距 + 翻页栏。 */
private val BottomClusterHeight = FabHeight + BlockGap + BoxSize

@Composable
fun InboxScreen(
    model: MailModel,
    onOpenDrawer: () -> Unit,
    onSearch: () -> Unit,
    onOpenThread: (String, String) -> Unit,
    onCompose: () -> Unit,
    onConnect: () -> Unit,
    onLabelPick: () -> Unit
) {
    val revision = revisionOf(model)
    val mails = remember(revision) { model.items.toList() }
    val selected = remember(revision) { model.selected.toSet() }
    val colors = HmailTheme.colors
    val background = MaterialTheme.colorScheme.background
    val backdrop = rememberGlassBackdrop(background)
    val top = topInset()
    val bottom = bottomInset()

    // 列表滚动位置跨页面保留，与重构前 model.listPosition/listOffset 语义一致。
    val listState = rememberLazyListState(model.listPosition, model.listOffset)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                model.listPosition = index
                model.listOffset = offset
            }
    }
    // 切换文件夹、账号、搜索词或翻页后回到列表顶部；首次进入沿用恢复出的位置。
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(model.active, model.folder, model.query, model.page) {
        if (!restored) {
            restored = true
            return@LaunchedEffect
        }
        listState.scrollToItem(0)
    }

    val barTop = top + TopBarTopGap
    // 折叠进度只在这里创建；真正的读取发生在顶栏内部，列表滚动时不会整屏重组。
    val collapse = rememberCollapseFraction(listState)
    val title = when {
        selected.isNotEmpty() -> "已选择 ${selected.size} 封"
        model.query.isNotBlank() -> "搜索结果"
        else -> folders[model.folder] ?: model.labels.find { it.id == model.folder }?.name ?: "邮件"
    }
    val actions = if (selected.isEmpty()) {
        listOf(
            GlassAction("搜索", "search") { onSearch() },
            GlassAction("刷新", "refresh", enabled = !model.loading) { model.loadList(sync = true) }
        )
    } else {
        listOf(
            GlassAction("全选") {
                model.selected.clear()
                model.selected.addAll(model.items.map { it.threadId })
                model.changed()
            },
            GlassAction("取消选择") {
                model.selected.clear()
                model.changed()
            },
            GlassAction("归档") { model.work { model.modify(remove = listOf("INBOX")) } },
            GlassAction("标为已读") { model.work { model.modify(remove = listOf("UNREAD")) } },
            GlassAction("标为未读") { model.work { model.modify(add = listOf("UNREAD")) } },
            GlassAction("标记垃圾邮件") { model.work { model.modify(add = listOf("SPAM"), remove = listOf("INBOX")) } },
            GlassAction(if (model.folder == "TRASH") "恢复邮件" else "移入回收站") {
                model.work { model.modify(action = if (model.folder == "TRASH") "untrash" else "trash") }
            },
            GlassAction("标签") { onLabelPick() }
        )
    }

    Box(Modifier.fillMaxSize().background(background)) {
        // 采样源：列表内容在玻璃后方滚动穿透。
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().glassSource(backdrop),
            // 顶部留白按展开态高度保留，首条邮件不会被展开的玻璃挡住；底部为悬浮区（含草稿入口）让位。
            contentPadding = PaddingValues(
                top = barTop + TopBarExpandedHeight + BlockGap,
                bottom = bottom + BottomClusterHeight + (if (model.hasDraft) FabHeight + BlockGap else 0.dp) + 16.dp
            )
        ) {
            val activeAccount = model.accounts.find { it.id == model.active }
            if (activeAccount?.status == "reconnect") {
                item("reconnect") {
                    Text(
                        text = "连接已失效，点此重新连接",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                model.form("ConnectFragment")["email"] = activeAccount.email
                                onConnect()
                            }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 14.sp
                    )
                }
            }
            items(mails, key = { it.threadId }) { mail ->
                MailRow(
                    checked = mail.threadId in selected,
                    anySelected = selected.isNotEmpty(),
                    unread = "UNREAD" in mail.labels,
                    starred = "STARRED" in mail.labels,
                    sender = mail.from.substringBefore('<').replace("\"", "").trim().ifBlank { mail.from },
                    count = mail.raw.optInt("count", 1),
                    subject = mail.subject,
                    snippet = mail.raw.str("snippet"),
                    date = shortDate(mail.raw.str("date")),
                    onToggle = {
                        if (!model.selected.add(mail.threadId)) model.selected.remove(mail.threadId)
                        model.changed()
                    },
                    onOpen = {
                        if (!model.busy) {
                            if (model.folder == "DRAFT") {
                                model.work {
                                    model.openDraft(mail)
                                    onCompose()
                                }
                            } else {
                                model.messages = emptyList()
                                onOpenThread(model.active, mail.threadId)
                            }
                        }
                    },
                    onStar = {
                        val starred = "STARRED" in mail.labels
                        model.work {
                            model.modify(
                                add = if (starred) emptyList() else listOf("STARRED"),
                                remove = if (starred) listOf("STARRED") else emptyList(),
                                ids = listOf(mail.id)
                            )
                        }
                    }
                )
            }
        }

        if (mails.isEmpty()) {
            val empty = when {
                model.loading || model.starting -> "正在加载"
                model.active.isBlank() -> "连接邮箱"
                model.query.isNotBlank() -> "没有找到邮件"
                else -> "暂无邮件"
            }
            Text(
                text = empty,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 32.dp)
                    .clickable(enabled = model.active.isBlank() && !model.starting) { onConnect() },
                fontSize = 18.sp,
                color = colors.muted
            )
        }

        GlassTopBar(
            backdrop = backdrop,
            title = title,
            collapse = collapse,
            actions = actions,
            onNavigationClick = onOpenDrawer,
            topPadding = barTop,
            modifier = Modifier.align(Alignment.TopStart)
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = bottom + 16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(BlockGap)
        ) {
            // 有未完成草稿时，在"写邮件"上方再加一块玻璃入口。
            if (model.hasDraft) {
                GlassPill(
                    backdrop = backdrop,
                    modifier = Modifier.height(FabHeight),
                    onClick = onCompose
                ) {
                    Text(
                        "继续写信",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
            GlassPill(
                backdrop = backdrop,
                modifier = Modifier.height(FabHeight),
                onClick = {
                    if (model.active.isBlank()) onConnect()
                    else {
                        model.startCompose()
                        onCompose()
                    }
                }
            ) {
                MailIcon("edit", tint = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.width(8.dp))
                Text("写邮件", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            }
            PaginationBar(
                backdrop = backdrop,
                page = model.page,
                canPrevious = !model.loading && model.page > 0,
                canNext = !model.loading && model.next.isNotBlank(),
                onPrevious = { model.paginate(-1) },
                onNext = { model.paginate(1) }
            )
        }

        if (model.loading || model.busy) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(Alignment.TopStart)
            )
        }
    }
}

/** 靠右的三块玻璃：[上一页] [页码] [下一页]，不满宽。 */
@Composable
private fun PaginationBar(
    backdrop: LayerBackdrop,
    page: Int,
    canPrevious: Boolean,
    canNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    val onBackground = MaterialTheme.colorScheme.onBackground
    Row(
        horizontalArrangement = Arrangement.spacedBy(BlockGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 禁用态只淡化文字：给玻璃叠加 alpha 图层会让离屏合成失效，玻璃会退化成半透明的色块。
        GlassPill(
            backdrop = backdrop,
            modifier = Modifier.height(BoxSize),
            enabled = canPrevious,
            onClick = onPrevious
        ) {
            Text(
                "上一页",
                maxLines = 1,
                style = MaterialTheme.typography.labelLarge,
                color = onBackground.copy(alpha = if (canPrevious) 1f else 0.4f)
            )
        }
        GlassSurface(
            backdrop = backdrop,
            modifier = Modifier.size(BoxSize),
            shape = RoundedCornerShape(percent = 50)
        ) {
            Text(
                text = "${page + 1}",
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.titleMedium,
                color = onBackground
            )
        }
        GlassPill(
            backdrop = backdrop,
            modifier = Modifier.height(BoxSize),
            enabled = canNext,
            onClick = onNext
        ) {
            Text(
                "下一页",
                maxLines = 1,
                style = MaterialTheme.typography.labelLarge,
                color = onBackground.copy(alpha = if (canNext) 1f else 0.4f)
            )
        }
    }
}

/** 行只接收不可变值：`Mail` 内部是可变的 JSONObject，直接传对象会让 Compose 跳过已变化的行。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MailRow(
    checked: Boolean,
    anySelected: Boolean,
    unread: Boolean,
    starred: Boolean,
    sender: String,
    count: Int,
    subject: String,
    snippet: String,
    date: String,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onStar: () -> Unit
) {
    val colors = HmailTheme.colors
    val weight = if (unread) FontWeight.Bold else FontWeight.Normal
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 未选中时行与页面背景完全融为一体，只有选中态才浮起高亮底色。
            .background(if (checked) colors.selected else Color.Transparent)
            .combinedClickable(
                onClick = { if (anySelected) onToggle() else onOpen() },
                onLongClick = onToggle
            )
            .padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(colors.selected)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (checked) "✓" else sender.take(1).uppercase(),
                color = colors.onSelected,
                fontSize = 18.sp
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = sender + if (count > 1) " · $count" else "",
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 15.sp,
                    fontWeight = weight,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(date, fontSize = 11.sp, color = colors.muted)
            }
            Text(
                text = subject,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 14.sp,
                fontWeight = weight,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = snippet,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp,
                color = colors.muted
            )
        }
        IconButton(onClick = onStar, modifier = Modifier.size(48.dp)) {
            MailIcon("star", contentDescription = "切换星标", tint = if (starred) colors.star else colors.muted)
        }
    }
}

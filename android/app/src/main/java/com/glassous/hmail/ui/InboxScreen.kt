package com.glassous.hmail.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glassous.hmail.Mail
import com.glassous.hmail.MailIcon
import com.glassous.hmail.MailModel
import com.glassous.hmail.folders
import com.glassous.hmail.shortDate
import com.glassous.hmail.str
import com.glassous.hmail.ui.common.GlassAction
import com.glassous.hmail.ui.common.GlassActionsRow
import com.glassous.hmail.ui.common.GlassTopBar
import com.glassous.hmail.ui.common.SelectionActionCard
import com.glassous.hmail.ui.common.TopBarCollapsedHeight
import com.glassous.hmail.ui.common.TopBarExpandedHeight
import com.glassous.hmail.ui.common.TopBarTopGap
import com.glassous.hmail.ui.common.bottomInset
import com.glassous.hmail.ui.common.rememberCollapseFraction
import com.glassous.hmail.ui.common.topInset
import com.glassous.hmail.ui.glass.GlassPill
import com.glassous.hmail.ui.glass.glassSource
import com.glassous.hmail.ui.glass.rememberGlassBackdrop
import com.glassous.hmail.ui.theme.HmailTheme
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.delay

private val BlockGap = 12.dp
private val FabHeight = 56.dp

/** 底部仅保留写信按钮；加载更多随列表滚动。 */
private val BottomClusterHeight = FabHeight

private class AnimatedMailEntry(mail: Mail, initiallyVisible: Boolean = false) {
    var mail by mutableStateOf(mail)
    val visibility = MutableTransitionState(initiallyVisible).apply { targetState = true }
}

@Composable
fun InboxScreen(
    model: MailModel,
    onOpenDrawer: () -> Unit,
    onOpenThread: (String, String) -> Unit,
    onCompose: () -> Unit,
    onContinueDraft: () -> Unit,
    onConnect: () -> Unit,
    onLabelPick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope
) {
    val revision = revisionOf(model)
    val mails = remember(revision) { model.items.toList() }
    val selected = remember(revision) { model.selected.toSet() }
    var cardOpen by remember(model.active, model.folder, model.query, selected.isNotEmpty()) { mutableStateOf(false) }

    // 搜索不跳独立页面：顶栏的搜索按钮原地展开成长输入框，提交后仍在主页出结果。
    var searchOpen by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    val searchFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    fun closeSearch() {
        searchOpen = false
        searchText = ""
        focusManager.clearFocus()
        // 正在看搜索结果时，退出搜索即回到普通列表（旧流程没有这条退路）。
        if (model.query.isNotBlank()) model.search("")
    }
    fun submitSearch(value: String) {
        val keyword = value.trim()
        searchOpen = false
        searchText = ""
        focusManager.clearFocus()
        if (keyword.isEmpty()) {
            if (model.query.isNotBlank()) model.search("")
        } else {
            model.search(keyword)
        }
    }
    LaunchedEffect(searchOpen) {
        if (!searchOpen) return@LaunchedEffect
        // 等展开动画把输入框挂进组合后再抢焦点，避免 FocusRequester 尚未初始化。
        delay(160)
        searchFocus.requestFocus()
    }
    BackHandler(enabled = searchOpen) { closeSearch() }
    val listState = rememberLazyListState(model.listPosition, model.listOffset)
    var animatedMails by remember(model.active, model.folder, model.query) {
        // Initial local content is immediately visible. Only later diffs animate.
        mutableStateOf(mails.map { AnimatedMailEntry(it, initiallyVisible = true) })
    }
    val slideDistance = with(LocalDensity.current) { 28.dp.roundToPx() }
    LaunchedEffect(mails, model.active, model.folder, model.query) {
        fun replaceRows(rows: List<AnimatedMailEntry>) {
            val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
            val first = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == listState.firstVisibleItemIndex }
            val anchor = (first?.key as? String)?.removePrefix("mail:")
            val anchorIndex = rows.indexOfFirst { it.mail.threadId == anchor }
            val reconnectRows = if (model.accounts.find { it.id == model.active }?.status == "reconnect") 1 else 0
            // Swap the data once: never expose an empty list with only its footer as the anchor.
            val hadRows = animatedMails.isNotEmpty()
            animatedMails = rows
            if (!listState.isScrollInProgress) {
                when {
                    !hadRows || atTop -> listState.requestScrollToItem(0)
                    anchorIndex >= 0 -> listState.requestScrollToItem(anchorIndex + reconnectRows, listState.firstVisibleItemScrollOffset)
                    rows.isNotEmpty() -> listState.requestScrollToItem(
                        listState.firstVisibleItemIndex.coerceAtMost(rows.lastIndex + reconnectRows),
                        listState.firstVisibleItemScrollOffset
                    )
                }
            }
        }
        val previous = animatedMails
        val byId = previous.associateBy { it.mail.threadId }
        val currentIds = mails.map { it.threadId }.toSet()
        val updated = mails.map { mail ->
            (byId[mail.threadId] ?: AnimatedMailEntry(mail, initiallyVisible = previous.isEmpty())).also {
                it.mail = mail
                it.visibility.targetState = true
            }
        }.toMutableList()
        previous.forEachIndexed { index, entry ->
            if (entry.mail.threadId !in currentIds) {
                entry.visibility.targetState = false
                updated.add(index.coerceAtMost(updated.size), entry)
            }
        }
        replaceRows(updated)
        delay(240)
        val remaining = animatedMails.filter { it.visibility.targetState }
        if (remaining.size != animatedMails.size) replaceRows(remaining)
    }
    val colors = HmailTheme.colors
    val background = MaterialTheme.colorScheme.background
    val backdrop = rememberGlassBackdrop(background)
    val top = topInset()
    val bottom = bottomInset()

    // 写邮件入口与写邮件页的共享元素：整页容器（背景 + 全部组件）、图标与标题各配一对，按入口分键。
    val newPage = sharedTransitionScope.sharedPageElement(ComposeSharedKeys.page(draft = false), animatedVisibilityScope)
    val draftPage = sharedTransitionScope.sharedPageElement(ComposeSharedKeys.page(draft = true), animatedVisibilityScope)
    val newIcon = sharedTransitionScope.sharedTextElement(ComposeSharedKeys.icon(draft = false), animatedVisibilityScope)
    val draftIcon = sharedTransitionScope.sharedTextElement(ComposeSharedKeys.icon(draft = true), animatedVisibilityScope)
    val newTitle = sharedTransitionScope.sharedTextElement(ComposeSharedKeys.title(draft = false), animatedVisibilityScope)
    val draftTitle = sharedTransitionScope.sharedTextElement(ComposeSharedKeys.title(draft = true), animatedVisibilityScope)

    // 返回详情页时保留位置；冷启动由本地列表直接提供首屏。
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                model.listPosition = index
                model.listOffset = offset
            }
    }
    // "写邮件"随滚动方向开合：向下滚只留图标，向上滚重新展开文字。
    var composeExpanded by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        var previous = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { current ->
                if (current == previous) return@collect
                val downwards = if (current.first == previous.first) {
                    current.second > previous.second
                } else {
                    current.first > previous.first
                }
                previous = current
                composeExpanded = !downwards
            }
    }
    // 切换文件夹、账号或搜索词后回到列表顶部；追加邮件保留当前位置；首次进入沿用恢复出的位置。
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(model.active, model.folder, model.query) {
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
    val selectionActions = listOf(
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

    // 选中项的星标状态：全部已加星时按钮转为"取消星标"，否则统一加星。
    val selectedMails = mails.filter { it.threadId in selected }
    val allStarred = selectedMails.isNotEmpty() && selectedMails.all { "STARRED" in it.labels }

    val topActions = if (selected.isEmpty()) listOf(
        GlassAction("搜索", "search") {
            searchText = model.query
            searchOpen = true
        },
        GlassAction("刷新", "refresh", enabled = !model.loading && !model.syncing) { model.loadList(sync = true) }
    ) else listOf(
        // 星标按钮排在三点菜单左边，只作用于选中的邮件。
        GlassAction(
            label = if (allStarred) "取消星标" else "添加星标",
            icon = "star",
            enabled = !model.busy,
            tint = if (allStarred) colors.star else colors.muted
        ) {
            model.work {
                model.modify(
                    add = if (allStarred) emptyList() else listOf("STARRED"),
                    remove = if (allStarred) listOf("STARRED") else emptyList()
                )
            }
        },
        GlassAction("更多操作", "more") { cardOpen = !cardOpen }
    )

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
            items(animatedMails, key = { "mail:${it.mail.threadId}" }) { entry ->
                val mail = entry.mail
                Box(Modifier.animateItem(
                    fadeInSpec = null, fadeOutSpec = null,
                    placementSpec = tween(240, easing = FastOutSlowInEasing)
                )) {
                    AnimatedVisibility(
                        visibleState = entry.visibility,
                        enter = slideInHorizontally(tween(220, easing = FastOutSlowInEasing)) { slideDistance } + fadeIn(tween(180)),
                        exit = slideOutHorizontally(tween(180, easing = FastOutSlowInEasing)) { -slideDistance } + fadeOut(tween(180))
                    ) {
                        MailRow(
                            enabled = entry.visibility.targetState,
                            checked = mail.threadId in selected,
                            anySelected = selected.isNotEmpty(),
                            unread = "UNREAD" in mail.labels,
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
                            }
                        )
                    }
                }
            }
            if (animatedMails.any { it.visibility.targetState }) item("load-more") {
                Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    when {
                        model.loading -> Text(if (model.loadingMore) "正在加载更多…" else "正在更新…", color = colors.muted)
                        model.localOnly -> TextButton(onClick = { model.loadList(sync = true) }) { Text("显示本地邮件 · 点击联网刷新") }
                        model.listError != null -> TextButton(onClick = { model.loadList() }) { Text("刷新失败，点击重试") }
                        model.next.isNotBlank() -> TextButton(enabled = model.canLoadMore, onClick = model::loadMore) {
                            Text(model.moreError ?: "加载更多")
                        }
                        else -> Text("已加载全部邮件", color = colors.muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (mails.isEmpty() && animatedMails.isEmpty()) {
            val empty = when {
                model.loading || model.starting -> "正在加载"
                model.mailbox.accountsError != null -> "邮箱加载失败，点击重试"
                model.listError != null -> model.listError!!
                model.active.isBlank() -> "连接邮箱"
                model.query.isNotBlank() -> "没有找到邮件"
                else -> "暂无邮件"
            }
            Text(
                text = empty,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 32.dp)
                    .clickable(enabled = !model.loading && !model.starting) {
                        when {
                            model.mailbox.accountsError != null -> model.retryAccounts()
                            model.listError != null -> model.loadList()
                            model.active.isBlank() -> onConnect()
                        }
                    },
                fontSize = 18.sp,
                color = colors.muted
            )
        }

        GlassTopBar(
            backdrop = backdrop,
            title = title,
            collapse = collapse,
            onNavigationClick = onOpenDrawer,
            topPadding = barTop,
            modifier = Modifier.align(Alignment.TopStart),
            trailing = {
                // 常规操作项与展开的搜索框在同一块区域互换；搜索框贴着右边缘向左生长。
                AnimatedVisibility(
                    visible = !searchOpen,
                    modifier = Modifier.align(Alignment.TopEnd),
                    enter = fadeIn(tween(160)),
                    exit = fadeOut(tween(100))
                ) {
                    GlassActionsRow(backdrop, topActions)
                }
                AnimatedVisibility(
                    visible = searchOpen,
                    modifier = Modifier.align(Alignment.TopEnd),
                    enter = fadeIn(tween(160)) + expandHorizontally(
                        tween(240, easing = FastOutSlowInEasing),
                        expandFrom = Alignment.End
                    ),
                    exit = fadeOut(tween(120)) + shrinkHorizontally(
                        tween(200, easing = FastOutSlowInEasing),
                        shrinkTowards = Alignment.End
                    )
                ) {
                    SearchBar(
                        backdrop = backdrop,
                        value = searchText,
                        onValueChange = { searchText = it },
                        onSubmit = { submitSearch(searchText) },
                        onClose = { closeSearch() },
                        focusRequester = searchFocus
                    )
                }
            }
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = bottom + 16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(BlockGap)
        ) {
            // 有未完成草稿时，在"写邮件"上方再加一块玻璃入口。
            // 与"写邮件"同色同玻璃质感，但内容固定不随滚动开合。
            if (model.hasDraft) {
                GlassPill(
                    backdrop = backdrop,
                    modifier = Modifier.height(FabHeight).then(draftPage),
                    tint = HmailTheme.card,
                    onClick = onContinueDraft
                ) {
                    MailIcon(
                        "draft_file",
                        // 与写邮件页顶部栏的返回键共享：随过渡一起飞行。
                        modifier = draftIcon,
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "继续写信",
                        modifier = draftTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
            GlassPill(
                backdrop = backdrop,
                modifier = Modifier.height(FabHeight).then(newPage),
                // 只换遮罩颜色：模糊、折射与按压的明暗过渡仍由玻璃本身提供。
                tint = HmailTheme.card,
                onClick = {
                    if (model.active.isBlank()) onConnect()
                    else {
                        model.startCompose()
                        onCompose()
                    }
                }
            ) {
                MailIcon(
                    "edit",
                    // 与写邮件页顶部栏的返回键共享：随过渡一起飞行。
                    modifier = newIcon,
                    tint = MaterialTheme.colorScheme.onBackground
                )
                AnimatedVisibility(
                    visible = composeExpanded,
                    enter = fadeIn(tween(180)) + expandHorizontally(tween(220), expandFrom = Alignment.Start),
                    exit = fadeOut(tween(140)) + shrinkHorizontally(tween(200), shrinkTowards = Alignment.Start)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "写邮件",
                            modifier = newTitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }
        }

        if (model.loading || model.syncing || model.busy) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(Alignment.TopStart)
            )
        }
        SelectionActionCard(
            visible = cardOpen && selected.isNotEmpty(),
            actions = selectionActions.map { it.copy(enabled = it.enabled && !model.busy) },
            topPadding = barTop + TopBarCollapsedHeight + 8.dp,
            onDismiss = { cardOpen = false }
        )
    }
}

/** 行只接收不可变值：`Mail` 内部是可变的 JSONObject，直接传对象会让 Compose 跳过已变化的行。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MailRow(
    enabled: Boolean,
    checked: Boolean,
    anySelected: Boolean,
    unread: Boolean,
    sender: String,
    count: Int,
    subject: String,
    snippet: String,
    date: String,
    onToggle: () -> Unit,
    onOpen: () -> Unit
) {
    val colors = HmailTheme.colors
    val weight = if (unread) FontWeight.Bold else FontWeight.Normal
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 未选中时行与页面背景完全融为一体，只有选中态才浮起高亮底色。
            .background(if (checked) colors.selected else Color.Transparent)
            .combinedClickable(
                enabled = enabled,
                onClick = { if (anySelected) onToggle() else onOpen() },
                onLongClick = onToggle
            )
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(colors.selected)
                .clickable(enabled = enabled, onClick = onToggle),
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
    }
}

/** 主页顶栏里原地展开的搜索框：与顶栏同材质的玻璃胶囊，键盘搜索键即提交。 */
@Composable
private fun SearchBar(
    backdrop: Backdrop,
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClose: () -> Unit,
    focusRequester: FocusRequester
) {
    val foreground = MaterialTheme.colorScheme.onBackground
    GlassPill(
        backdrop = backdrop,
        modifier = Modifier.fillMaxWidth().height(TopBarCollapsedHeight),
        // 胶囊任意位置都能把焦点交回输入框。
        onClick = { focusRequester.requestFocus() }
    ) {
        MailIcon("search", tint = foreground)
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f).focusRequester(focusRequester),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = foreground),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text("搜索邮件", style = MaterialTheme.typography.bodyLarge, color = HmailTheme.colors.muted)
                }
                inner()
            }
        )
        IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
            MailIcon("close", contentDescription = "退出搜索", tint = foreground)
        }
    }
}

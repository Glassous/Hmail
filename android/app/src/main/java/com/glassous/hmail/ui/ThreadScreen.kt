package com.glassous.hmail.ui

import android.content.Context
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.glassous.hmail.Mail
import com.glassous.hmail.MailIcon
import com.glassous.hmail.MailModel
import com.glassous.hmail.SERVER
import com.glassous.hmail.enc
import com.glassous.hmail.openExternal
import com.glassous.hmail.sizeText
import com.glassous.hmail.str
import com.glassous.hmail.ui.common.ConfirmDialog
import com.glassous.hmail.ui.common.GlassAction
import com.glassous.hmail.ui.common.SelectionActionCard
import com.glassous.hmail.ui.common.MailPage
import com.glassous.hmail.ui.common.SecondaryAction
import com.glassous.hmail.ui.common.SectionText
import com.glassous.hmail.ui.theme.HmailTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import kotlin.math.abs

@Composable
fun ThreadScreen(
    model: MailModel,
    account: String,
    threadId: String,
    onBack: () -> Unit,
    onCompose: () -> Unit,
    onLabelPick: () -> Unit
) {
    revisionOf(model)
    val context = LocalContext.current
    val aid = account.ifBlank { model.active }
    var reload by remember { mutableStateOf(0) }
    var downloadPath by remember { mutableStateOf<String?>(null) }
    var pendingTrash by remember { mutableStateOf(false) }
    var cardOpen by remember { mutableStateOf(false) }
    val inTrash = model.folder == "TRASH"

    LaunchedEffect(threadId, aid, reload) {
        if (threadId.isNotBlank()) model.work { model.readThread(aid, threadId) }
    }

    val saveAttachment = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val path = downloadPath
        val resolver = context.applicationContext.contentResolver
        if (uri != null && path != null) {
            model.work {
                val bytes = model.api.download(path)
                withContext(Dispatchers.IO) {
                    resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw java.io.IOException()
                }
            }
        }
        downloadPath = null
    }

    // 与重构前一致：只有当前载入的会话与路由参数匹配时才渲染，避免串会话。
    val messages = if (model.messages.firstOrNull()?.threadId == threadId) model.messages else emptyList()

    // 顶栏只留刷新与三点；其余操作收进与主页共用的实色卡片。
    val cardActions = listOf(
        GlassAction("归档", "archive") {
            model.work {
                model.modify(remove = listOf("INBOX"), threads = listOf(threadId))
                onBack()
            }
        },
        GlassAction("标为未读", "unread") {
            model.work {
                model.modify(add = listOf("UNREAD"), threads = listOf(threadId))
                onBack()
            }
        },
        GlassAction("标记垃圾邮件", "SPAM") {
            model.work {
                model.modify(add = listOf("SPAM"), remove = listOf("INBOX"), threads = listOf(threadId))
                onBack()
            }
        },
        GlassAction(if (inTrash) "恢复邮件" else "移入回收站", if (inTrash) "restore" else "trash") {
            pendingTrash = true
        },
        GlassAction("标签", "tag") { onLabelPick() }
    )

    MailPage(
        title = "邮件",
        onBack = onBack,
        progress = model.busy,
        actions = listOf(
            GlassAction("刷新", "refresh") { reload++ },
            GlassAction("更多操作", "more") { cardOpen = true }
        ),
        overlay = { topSpace ->
            SelectionActionCard(
                visible = cardOpen,
                actions = cardActions.map { it.copy(enabled = it.enabled && !model.busy) },
                topPadding = topSpace + 4.dp,
                onDismiss = { cardOpen = false }
            )
        }
    ) {
        if (messages.isEmpty()) {
            SectionText("正在加载", muted = true)
            return@MailPage
        }
        SectionText(messages.first().subject, size = 24f, bold = true)
        messages.forEach { mail ->
            key(mail.id) {
                ThreadMessage(
                    model = model,
                    mail = mail,
                    aid = aid,
                    context = context,
                    onDownloadAttachment = { path, name ->
                        downloadPath = path
                        saveAttachment.launch(name)
                    },
                    onComposeMode = { mode ->
                        model.startCompose(mode, mail)
                        onCompose()
                    }
                )
                // 分隔线换成留白：底部回复区自带底色，再画线会显得割裂。
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    if (pendingTrash) {
        ConfirmDialog(
            title = if (inTrash) "恢复这封邮件？" else "移入回收站？",
            onDismiss = { pendingTrash = false },
            onConfirm = {
                pendingTrash = false
                model.work {
                    model.modify(
                        action = if (inTrash) "untrash" else "trash",
                        threads = listOf(threadId)
                    )
                    onBack()
                }
            }
        )
    }
}

@Composable
private fun ThreadMessage(
    model: MailModel,
    mail: Mail,
    aid: String,
    context: Context,
    onDownloadAttachment: (String, String) -> Unit,
    onComposeMode: (String) -> Unit
) {
    val starred = "STARRED" in mail.labels
    // 单页会连着显示同一会话的多封邮件，星标属于各自那封：放在发件人标题行右侧。
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = mail.from,
            modifier = Modifier.weight(1f),
            // 发件人较长时换行显示，不再截断；星标随整行文字垂直居中。
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        IconButton(
            onClick = {
                model.work {
                    model.modify(
                        add = if (starred) emptyList() else listOf("STARRED"),
                        remove = if (starred) listOf("STARRED") else emptyList(),
                        ids = listOf(mail.id)
                    )
                }
            },
            modifier = Modifier.size(48.dp)
        ) {
            MailIcon(
                "star",
                contentDescription = if (starred) "取消星标" else "添加星标",
                tint = if (starred) HmailTheme.colors.star else HmailTheme.colors.muted
            )
        }
    }

    // 收件详情不再折叠：发件人下方直接给出收件人/抄送/时间。
    val details = buildString {
        append("收件人：").append(mail.raw.str("to")).append('\n')
        if (mail.raw.str("cc").isNotBlank()) append("抄送：").append(mail.raw.str("cc")).append('\n')
        append(mail.raw.str("date"))
    }
    SelectionContainer { SectionText(details, size = 12f, muted = true) }

    // 标题与收发信息整体与正文之间留出更大的间距。
    Spacer(Modifier.height(12.dp))
    if (mail.raw.str("html").isNotBlank()) {
        HtmlMessage(html = mail.raw.str("html"), aid = aid, context = context, model = model)
    } else {
        SelectionContainer { SectionText(mail.raw.str("text").ifBlank { "（无正文）" }, size = 16f) }
    }

    mail.attachments.forEach { attachment ->
        SecondaryAction("${attachment.name} · ${sizeText(attachment.size)}") {
            onDownloadAttachment(
                model.path("/messages/${enc(mail.id)}/attachments/${enc(attachment.id)}", aid),
                attachment.name
            )
        }
        Spacer(Modifier.height(8.dp))
    }

    Spacer(Modifier.height(20.dp))
    ReplyRow(onComposeMode)
}

/** 回复 / 回复全部 / 转发合并成一行，底色与顶部栏呼出的操作卡片一致。 */
private val ReplyModes = listOf("回复" to "reply", "回复全部" to "replyAll", "转发" to "forward")

@Composable
private fun ReplyRow(onComposeMode: (String) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = HmailTheme.card,
        contentColor = HmailTheme.colors.onSelected
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ReplyModes.forEach { (label, mode) ->
                TextButton(
                    onClick = { onComposeMode(mode) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = HmailTheme.colors.onSelected)
                ) {
                    Text(label, maxLines = 1, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/**
 * 来信 HTML 的隔离渲染：关闭脚本、禁用 Cookie 与文件/内容访问、只放行本站代理的签名图片请求，
 * 按内容高度自适应；触摸协商保证外层列表仍可纵向滚动。安全边界与重构前完全一致。
 */
@Composable
private fun HtmlMessage(html: String, aid: String, context: Context, model: MailModel) {
    val density = LocalDensity.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    var heightPx by remember { mutableStateOf(0) }
    val minHeight = with(density) { 180.dp.toPx() }
    val maxHeight = with(density) { 1800.dp.toPx() }
    val height = if (heightPx > 0) with(density) { heightPx.toDp() } else 420.dp

    AndroidView(
        // WebView 自身是直角，靠外层裁剪收成与卡片一致的圆角。
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(16.dp)),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.apply {
                    javaScriptEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    domStorageEnabled = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    setSupportMultipleWindows(false)
                    builtInZoomControls = true
                    displayZoomControls = false
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                CookieManager.getInstance().setAcceptCookie(false)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        openExternal(context, request.url.toString())
                        return true
                    }

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val uri = request.url
                        val allowed = uri.scheme == "https" && uri.host == "hmail.fiacloud.top" &&
                            (uri.port == -1 || uri.port == 443) && uri.getQueryParameter("sig") != null &&
                            (
                                uri.path == "/api/v1/proxy/image" ||
                                    uri.path.orEmpty().startsWith("/api/v1/gmail-accounts/${enc(aid)}/messages/")
                                )
                        return if (allowed) null else WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        view.postDelayed({
                            val measured = (view.contentHeight * density.density).toInt()
                            if (measured > 0) {
                                val clamped = measured.coerceIn(minHeight.toInt(), maxHeight.toInt())
                                if (clamped != heightPx) heightPx = clamped
                            }
                        }, 200)
                    }
                }
                setBackgroundColor(android.graphics.Color.WHITE)
                var touchDownY = 0f
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            touchDownY = event.y
                            view.onTouchEvent(event)
                        }
                        // 明显的纵向拖动交还外层列表，轻点与横向手势留给 WebView（链接仍可点击）。
                        MotionEvent.ACTION_MOVE ->
                            if (abs(event.y - touchDownY) > touchSlop) false else view.onTouchEvent(event)
                        else -> view.onTouchEvent(event)
                    }
                }
                loadDataWithBaseURL(
                    SERVER + "/",
                    "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                        "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src $SERVER; " +
                        "style-src 'unsafe-inline'; font-src 'none'; form-action 'none'\">" +
                        "<style>body{margin:12px;font:16px/1.65 sans-serif;overflow-wrap:anywhere;color:#1e293b;background:white}" +
                        "img{max-width:100%;height:auto}pre{white-space:pre-wrap}table{max-width:100%}</style></head>" +
                        "<body>$html</body></html>",
                    "text/html",
                    "UTF-8",
                    null
                )
            }
        },
        onRelease = { view ->
            view.stopLoading()
            view.destroy()
        }
    )
}

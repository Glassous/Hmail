package com.glassous.hmail

import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.*
import android.webkit.*
import android.widget.*
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream

class InboxFragment : MailFragment() {
    override val scroll = false
    private lateinit var list: RecyclerView
    private lateinit var adapter: MailRows
    private lateinit var empty: TextView
    private lateinit var reconnect: TextView
    private lateinit var previous: View
    private lateinit var next: View
    private lateinit var page: TextView
    private lateinit var resumeDraft: View
    private var signature = ""
    override fun build() {
        val ctx = requireContext()
        content.setPadding(0, 0, 0, 0)
        toolbar.navigationIcon = MailIcon("menu", ctx.color(R.color.ink)); toolbar.navigationContentDescription = "打开侧栏"
        toolbar.setNavigationOnClickListener { (requireActivity() as MainActivity).showDrawer() }
        reconnect = ctx.label("连接已失效，点此重新连接", 14f).apply {
            setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), ctx.dp(8)); setTextColor(ctx.color(R.color.error)); setOnClickListener { model.form("ConnectFragment")["email"] = model.accounts.find { it.id == model.active }?.email.orEmpty(); go(R.id.connect) }
        }
        content.addView(reconnect)
        resumeDraft = ctx.button("继续写信") { go(R.id.compose) }; content.addView(resumeDraft)
        val frame = FrameLayout(ctx)
        list = RecyclerView(ctx).apply { layoutManager = LinearLayoutManager(ctx); clipToPadding = false; setPadding(0, ctx.dp(4), 0, ctx.dp(92)) }
        adapter = MailRows(); list.adapter = adapter
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val lm = list.layoutManager as LinearLayoutManager
                model.listPosition = lm.findFirstVisibleItemPosition().coerceAtLeast(0); model.listOffset = lm.findViewByPosition(model.listPosition)?.top ?: 0
            }
        })
        frame.addView(list, FrameLayout.LayoutParams(-1, -1))
        empty = ctx.label("", 18f, true).apply { gravity = Gravity.CENTER; setPadding(ctx.dp(24), 0, ctx.dp(24), 0); setOnClickListener { if (model.active.isBlank() && !model.starting) go(R.id.connect) } }
        frame.addView(empty, FrameLayout.LayoutParams(-1, -1))
        val fab = ExtendedFloatingActionButton(ctx).apply {
            text = "写邮件"; icon = MailIcon("edit", ctx.color(R.color.on_selected)); setTextColor(ctx.color(R.color.on_selected)); backgroundTintList = android.content.res.ColorStateList.valueOf(ctx.color(R.color.selected)); elevation = ctx.dp(3).toFloat()
            setOnClickListener { if (model.active.isBlank()) go(R.id.connect) else { model.startCompose(); go(R.id.compose) } }
        }
        frame.addView(fab, FrameLayout.LayoutParams(-2, ctx.dp(56), Gravity.BOTTOM or Gravity.END).apply { setMargins(ctx.dp(16), 0, ctx.dp(20), ctx.dp(20)) })
        content.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        val pages = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(ctx.dp(16), 0, ctx.dp(16), 0) }
        previous = ctx.button("上一页") { model.paginate(-1) }; next = ctx.button("下一页") { model.paginate(1) }
        page = ctx.label("", 12f, true).apply { gravity = Gravity.CENTER }
        pages.addView(previous); pages.addView(page, LinearLayout.LayoutParams(0, -2, 1f)); pages.addView(next)
        content.addView(pages)
        (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(model.listPosition, model.listOffset)
    }
    override fun update() {
        super.update()
        toolbar.title = if (model.selected.isNotEmpty()) "已选择 ${model.selected.size} 封" else if (model.query.isNotBlank()) "搜索结果" else folders[model.folder] ?: model.labels.find { it.id == model.folder }?.name ?: "邮件"
        toolbar.menu.clear()
        if (model.selected.isEmpty()) {
            menu("搜索", "search") { go(R.id.search) }
            menu("刷新", "refresh") { if (!model.loading) model.loadList(sync = true) }
        } else {
            menu("全选") { model.selected.clear(); model.selected.addAll(model.items.map { it.threadId }); model.changed() }
            menu("取消选择") { model.selected.clear(); model.changed() }
            operationMenu()
        }
        progress.visibility = if (model.loading || model.busy) View.VISIBLE else View.GONE
        empty.visibility = if (model.items.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when { model.loading || model.starting -> "正在加载"; model.active.isBlank() -> "连接邮箱"; model.query.isNotBlank() -> "没有找到邮件"; else -> "暂无邮件" }
        reconnect.visibility = if (model.accounts.find { it.id == model.active }?.status == "reconnect") View.VISIBLE else View.GONE
        resumeDraft.visibility = if (model.compose != null) View.VISIBLE else View.GONE
        previous.isEnabled = !model.loading && model.page > 0; next.isEnabled = !model.loading && model.next.isNotBlank(); page.text = "${model.page + 1}"
        val current = model.items.joinToString { it.raw.toString() } + model.selected.toString()
        if (signature != current) { signature = current; adapter.notifyDataSetChanged() }
    }
    private fun operationMenu() {
        menu("归档") { model.work { model.modify(remove = listOf("INBOX")) } }
        menu("标为已读") { model.work { model.modify(remove = listOf("UNREAD")) } }
        menu("标为未读") { model.work { model.modify(add = listOf("UNREAD")) } }
        menu("标记垃圾邮件") { model.work { model.modify(add = listOf("SPAM"), remove = listOf("INBOX")) } }
        menu(if (model.folder == "TRASH") "恢复邮件" else "移入回收站") { model.work { model.modify(action = if (model.folder == "TRASH") "untrash" else "trash") } }
        menu("标签") { go(R.id.label_pick) }
    }
    private fun open(mail: Mail) {
        if (model.busy) return
        if (model.folder == "DRAFT") model.work { model.openDraft(mail); go(R.id.compose) }
        else { model.messages = emptyList(); go(R.id.thread, Bundle().apply { putString("account", model.active); putString("thread", mail.threadId) }) }
    }
    private inner class Row(val root: LinearLayout, val avatar: TextView, val from: TextView, val date: TextView, val subject: TextView, val snippet: TextView, val star: ImageButton) : RecyclerView.ViewHolder(root)
    private inner class MailRows : RecyclerView.Adapter<Row>() {
        override fun getItemCount() = model.items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row {
            val ctx = parent.context
            val root = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP; setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(12), ctx.dp(16)); layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = ctx.dp(1) } }
            val avatar = ctx.label("", 18f).apply { gravity = Gravity.CENTER; background = ctx.round(ctx.color(R.color.selected), 24); setTextColor(ctx.color(R.color.on_selected)) }
            root.addView(avatar, LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44)).apply { marginEnd = ctx.dp(12) })
            val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val header = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
            val from = ctx.label("", 15f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            val date = ctx.label("", 11f, true)
            header.addView(from, LinearLayout.LayoutParams(0, -2, 1f)); header.addView(date)
            val subject = ctx.label("", 14f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            val snippet = ctx.label("", 13f, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            body.addView(header); body.addView(subject); body.addView(snippet)
            root.addView(body, LinearLayout.LayoutParams(0, -2, 1f))
            val star = ImageButton(ctx).apply { background = ctx.round(Color.TRANSPARENT); contentDescription = "切换星标"; setPadding(ctx.dp(12), ctx.dp(12), ctx.dp(12), ctx.dp(12)) }
            root.addView(star, LinearLayout.LayoutParams(ctx.dp(48), ctx.dp(48)))
            return Row(root, avatar, from, date, subject, snippet, star)
        }
        override fun onBindViewHolder(holder: Row, position: Int) {
            val mail = model.items[position]; val checked = mail.threadId in model.selected; val unread = "UNREAD" in mail.labels
            val ctx = holder.root.context
            holder.root.setBackgroundColor(ctx.color(if (checked) R.color.selected else if (unread) R.color.unread else R.color.read))
            val sender = mail.from.substringBefore('<').replace("\"", "").trim().ifBlank { mail.from }
            holder.avatar.text = if (checked) "✓" else sender.take(1).uppercase()
            holder.from.text = sender + if (mail.raw.optInt("count", 1) > 1) " · ${mail.raw.optInt("count")}" else ""
            holder.subject.text = mail.subject; holder.snippet.text = mail.raw.str("snippet")
            holder.from.setTypeface(null, if (unread) Typeface.BOLD else Typeface.NORMAL); holder.subject.setTypeface(null, if (unread) Typeface.BOLD else Typeface.NORMAL)
            holder.date.text = shortDate(mail.raw.str("date"))
            holder.star.setImageDrawable(MailIcon("star", ctx.color(if ("STARRED" in mail.labels) R.color.star else R.color.muted)))
            holder.star.setOnClickListener { model.work { model.modify(add = if ("STARRED" in mail.labels) emptyList() else listOf("STARRED"), remove = if ("STARRED" in mail.labels) listOf("STARRED") else emptyList(), ids = listOf(mail.id)) } }
            fun toggle() { if (!model.selected.add(mail.threadId)) model.selected.remove(mail.threadId); model.changed() }
            holder.avatar.setOnClickListener { toggle() }
            holder.root.setOnLongClickListener { toggle(); true }
            holder.root.setOnClickListener { if (model.selected.isNotEmpty()) toggle() else open(mail) }
        }
    }
}

fun shortDate(value: String): String {
    val instant = runCatching { java.time.Instant.parse(value) }.getOrNull()
        ?: runCatching { java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
    return instant?.atZone(java.time.ZoneId.systemDefault())?.let { "${it.monthValue}月${it.dayOfMonth}日" } ?: value.take(16)
}

class SearchFragment : MailFragment() {
    override val title = "搜索邮件"
    override fun build() {
        val input = formField("query", "搜索", default = model.query)
        action("搜索", true) { model.search(input.text.toString()); back() }
        input.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        input.setOnEditorActionListener { _, action, _ -> if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { model.search(input.text.toString()); back(); true } else false }
    }
}

class ThreadFragment : MailFragment() {
    override val title = "邮件"
    private var rendered = ""
    private val browsers = mutableListOf<WebView>()
    private val aid get() = arguments?.getString("account") ?: model.active
    private val tid get() = arguments?.getString("thread").orEmpty()
    private var downloadPath: String? = null
    private val saveAttachment = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val path = downloadPath
        val resolver = context?.applicationContext?.contentResolver
        if (uri != null && path != null && resolver != null) model.work {
            val bytes = model.api.download(path)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw java.io.IOException() }
            model.notice("附件已保存")
        }
        downloadPath = null
    }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); downloadPath = savedInstanceState?.getString("download") }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.putString("download", downloadPath) }
    override fun build() {
        menu("归档") { model.work { model.modify(remove = listOf("INBOX"), threads = listOf(tid)); back() } }
        menu("标为未读") { model.work { model.modify(add = listOf("UNREAD"), threads = listOf(tid)); back() } }
        menu("标记垃圾邮件") { model.work { model.modify(add = listOf("SPAM"), remove = listOf("INBOX"), threads = listOf(tid)); back() } }
        menu(if (model.folder == "TRASH") "恢复邮件" else "移入回收站") { model.work { model.modify(action = if (model.folder == "TRASH") "untrash" else "trash", threads = listOf(tid)); back() } }
        menu("标签") { go(R.id.label_pick, Bundle().apply { putString("thread", tid) }) }
        menu("刷新", "refresh") { load() }
        text("正在加载", muted = true)
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) { super.onViewCreated(view, savedInstanceState); if (model.messages.firstOrNull()?.threadId != tid) load() }
    private fun load() { model.work { model.readThread(aid, tid) } }
    override fun update() {
        super.update(); if (model.messages.firstOrNull()?.threadId != tid) return
        val signature = model.messages.joinToString { it.raw.toString() }
        if (signature == rendered) return
        rendered = signature; browsers.forEach { it.destroy() }; browsers.clear(); content.removeAllViews()
        text(model.messages.first().subject, 24f)
        model.messages.forEach { mail ->
            text(mail.from, 16f).setTypeface(null, Typeface.BOLD)
            val details = requireContext().label("收件人：${mail.raw.str("to")}\n" + (if (mail.raw.str("cc").isNotBlank()) "抄送：${mail.raw.str("cc")}\n" else "") + mail.raw.str("date"), 12f, true).apply { visibility = View.GONE; setTextIsSelectable(true) }
            action("收件详情") { details.visibility = if (details.visibility == View.VISIBLE) View.GONE else View.VISIBLE }; content.addView(details)
            action(if ("STARRED" in mail.labels) "取消星标" else "添加星标") { model.work { model.modify(add = if ("STARRED" in mail.labels) emptyList() else listOf("STARRED"), remove = if ("STARRED" in mail.labels) listOf("STARRED") else emptyList(), ids = listOf(mail.id)) } }
            if (mail.raw.str("html").isNotBlank()) addHtml(mail.raw.str("html")) else text(mail.raw.str("text").ifBlank { "（无正文）" }, 16f).setTextIsSelectable(true)
            mail.attachments.forEach { attachment -> action("${attachment.name} · ${sizeText(attachment.size)}") {
                downloadPath = model.path("/messages/${enc(mail.id)}/attachments/${enc(attachment.id)}", aid); saveAttachment.launch(attachment.name)
            } }
            listOf("回复" to "reply", "回复全部" to "replyAll", "转发" to "forward").forEach { (label, mode) -> action(label) { model.startCompose(mode, mail); go(R.id.compose) } }
            content.addView(View(requireContext()).apply { setBackgroundColor(requireContext().color(R.color.outline)) }, LinearLayout.LayoutParams(-1, requireContext().dp(1)).apply { topMargin = requireContext().dp(24); bottomMargin = requireContext().dp(24) })
        }
    }
    private fun addHtml(html: String) {
        val browser = WebView(requireContext())
        browser.settings.apply {
            javaScriptEnabled = false; allowFileAccess = false; allowContentAccess = false; domStorageEnabled = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW; setSupportMultipleWindows(false); builtInZoomControls = true; displayZoomControls = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, false)
        CookieManager.getInstance().setAcceptCookie(false)
        browser.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean { openExternal(request.url.toString()); return true }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                val allowed = uri.scheme == "https" && uri.host == "hmail.fiacloud.top" && (uri.port == -1 || uri.port == 443) && uri.getQueryParameter("sig") != null &&
                    (uri.path == "/api/v1/proxy/image" || uri.path.orEmpty().startsWith("/api/v1/gmail-accounts/${enc(aid)}/messages/"))
                return if (allowed) null else WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
            }
            override fun onPageFinished(view: WebView, url: String) {
                view.postDelayed({ if (view.parent != null) { val h = (view.contentHeight * resources.displayMetrics.density).toInt(); if (h > 0) view.layoutParams = view.layoutParams.apply { height = h.coerceIn(requireContext().dp(180), requireContext().dp(1800)) } } }, 200)
            }
        }
        browser.setBackgroundColor(Color.WHITE)
        var touchY = 0f
        browser.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { touchY = event.y; view.parent.requestDisallowInterceptTouchEvent(true) }
                MotionEvent.ACTION_MOVE -> { view.parent.requestDisallowInterceptTouchEvent(view.canScrollVertically(if (touchY > event.y) 1 else -1)); touchY = event.y }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        content.addView(browser, LinearLayout.LayoutParams(-1, requireContext().dp(420)))
        browsers.add(browser)
        browser.loadDataWithBaseURL(SERVER + "/", "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src $SERVER; style-src 'unsafe-inline'; font-src 'none'; form-action 'none'\"><style>body{margin:12px;font:16px/1.65 sans-serif;overflow-wrap:anywhere;color:#1e293b;background:white}img{max-width:100%;height:auto}pre{white-space:pre-wrap}table{max-width:100%}</style></head><body>$html</body></html>", "text/html", "UTF-8", null)
    }
    override fun onDestroyView() { browsers.forEach { it.stopLoading(); it.destroy() }; browsers.clear(); rendered = ""; super.onDestroyView() }
}

fun sizeText(size: Long) = if (size >= 1048576) String.format(java.util.Locale.ROOT, "%.1f MB", size / 1048576.0) else "${(size / 1024).coerceAtLeast(1)} KB"

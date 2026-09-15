package com.glassous.hmail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

class MailModel private constructor(app: Application) : AndroidViewModel(app) {
    val vault = Vault(app)
    val api = MailApi(vault)
    /** 本地是否存在未过期的会话 Cookie；只用于决定首屏，不发起网络请求。 */
    val loggedIn get() = vault.read("session") != null
    val revision = MutableStateFlow(0)
    private val notices = Channel<String>(Channel.BUFFERED)
    val events = notices.receiveAsFlow()
    var initialized = false
    /** 会话恢复进行中：首页在此期间显示加载态，不用空数据提示误导用户。 */
    var starting = false
    var user: JSONObject? = null
    var config = JSONObject()
    var accounts = emptyList<Account>()
    var labels = emptyList<MailLabel>()
    var active = ""
    var folder = "INBOX"
    var query = ""
    var items = emptyList<Mail>()
    var messages = emptyList<Mail>()
    val selected = linkedSetOf<String>()
    var next = ""
    val cursors = mutableListOf("")
    var page = 0
    var listPosition = 0
    var listOffset = 0
    var loading = false
    var busy = false
    var composeBusy = false
    var uploading = false
    var compose: ComposeState? = null
    /**
     * 是否真的存在可继续编辑的草稿。
     * 新建邮件后完全没有编辑（收件人/抄送/密送/主题/正文都为空且无附件）不算草稿，
     * 既不会落地到本地，也不会在主页出现"继续写信"入口。
     */
    val hasDraft: Boolean
        get() = compose?.let { state ->
            listOf("to", "cc", "bcc", "subject", "text").any { state.payload.str(it).isNotBlank() } ||
                state.attachments.isNotEmpty()
        } ?: false
    var savedText = ""
    var theme = vault.read("theme") ?: "system"
    val forms = mutableMapOf<String, MutableMap<String, String>>()
    val cooldowns = mutableMapOf<String, Long>()
    private val draftMutex = Mutex()
    private var autosave: Job? = null
    private var listJob: Job? = null
    private var listGeneration = 0
    private var authGeneration = 0
    fun changed() { revision.value++ }
    fun notice(value: String) { notices.trySend(value) }
    fun path(suffix: String, aid: String = active) = "/gmail-accounts/${enc(aid)}$suffix"
    fun form(name: String) = forms.getOrPut(name) { mutableMapOf() }
    fun fail(error: Throwable) {
        if (error is CancellationException) return
        if (error is ApiFailure && error.code in listOf("unauthorized", "csrf")) clearSession()
        val message = when {
            error is ApiFailure && error.code == "csrf" -> "请重新登录后重试"
            error is ApiFailure && error.code == "origin" -> "暂时无法连接，请稍后重试"
            error is ApiFailure && error.code in listOf("not_configured", "internal", "unavailable") -> "服务暂时不可用，请稍后重试"
            error is ApiFailure && error.code.startsWith("oauth_") -> "连接未完成，请重试"
            error is ApiFailure -> error.message ?: "操作失败，请重试"
            error is java.io.IOException -> "网络连接失败，请重试"
            else -> "操作失败，请重试"
        }
        notice(message)
    }
    fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true; changed()
        viewModelScope.launch { try { block() } catch (e: Exception) { fail(e) } finally { busy = false; changed() } }
    }
    fun boot() {
        if (initialized) return // 进程存活期间只做一次冷启动恢复。
        initialized = true; starting = true
        val generation = authGeneration
        viewModelScope.launch {
            // 首屏已由本地会话决定，服务能力预热与会话恢复并行，界面不必等待网络。
            launch {
                try { config = api.json("/config"); changed() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* 离线时认证页仍可使用。 */ }
            }
            if (generation == authGeneration && !busy && user == null && loggedIn) {
                try {
                    // 冷启动同步一次：仅此一次，之后只响应手动刷新。
                    val session = api.json("/me")
                    if (generation == authGeneration && !busy && user == null) setSession(session, sync = true)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* 会话过期由界面回到认证页。 */ }
            }
            starting = false; changed()
        }
    }
    suspend fun setSession(result: JSONObject, sync: Boolean = false) {
        user = result.getJSONObject("user"); api.csrf = result.str("csrf"); authGeneration++
        theme = user!!.str("theme").ifBlank { "system" }; vault.write("theme", theme)
        compose = vault.read("draft:${user!!.str("id")}")?.let { runCatching { ComposeState.restore(it) }.getOrNull() }
        if (compose?.owner != user!!.str("id")) compose = null
        refreshAccounts(sync); initialized = true; changed()
    }
    fun clearSession() {
        persistCompose(); authGeneration++; listGeneration++; listJob?.cancel(); autosave?.cancel()
        api.clear(); user = null; compose = null; accounts = emptyList(); active = ""; labels = emptyList()
        items = emptyList(); messages = emptyList(); selected.clear(); forms.clear(); initialized = true; starting = false; changed()
    }
    suspend fun refreshAccounts(sync: Boolean = false) {
        val generation = authGeneration
        val result = api.list("/gmail-accounts").map(Account::from)
        if (generation != authGeneration) return
        accounts = result
        if (accounts.none { it.id == active }) switchAccount(accounts.firstOrNull()?.id ?: "", sync)
        else {
            val aid = active
            val updated = api.list(path("/labels", aid)).map(MailLabel::from)
            if (generation == authGeneration && aid == active) { labels = updated; changed() }
        }
    }
    fun switchAccount(id: String, sync: Boolean = false) {
        listJob?.cancel(); listGeneration++; active = id; folder = "INBOX"; query = ""; labels = emptyList()
        messages = emptyList(); resetPages(); loadList(sync = sync)
    }
    fun resetPages() { cursors.clear(); cursors.add(""); page = 0; next = ""; items = emptyList(); selected.clear(); listPosition = 0; listOffset = 0 }
    fun chooseFolder(id: String) { folder = id; query = ""; resetPages(); loadList() }
    fun search(value: String) { query = value.trim(); resetPages(); loadList() }
    fun paginate(direction: Int) {
        if (loading || (direction < 0 && page == 0) || (direction > 0 && next.isBlank())) return
        if (direction > 0) { if (cursors.size <= page + 1) cursors.add(next) else cursors[page + 1] = next }
        page += direction; selected.clear(); listPosition = 0; listOffset = 0; loadList()
    }
    fun loadList(sync: Boolean = false, quiet: Boolean = false) {
        listJob?.cancel()
        val generation = ++listGeneration; val aid = active; val chosenFolder = folder
        if (aid.isBlank()) { loading = false; changed(); return }
        val suffix = "/threads?folder=${enc(chosenFolder)}&q=${enc(query)}&cursor=${enc(cursors[page])}"
        loading = true; changed()
        listJob = viewModelScope.launch {
            try {
                if (sync) api.json(path("/sync?folder=${enc(chosenFolder)}", aid), "POST")
                val result = api.json(path(suffix, aid))
                val newLabels = api.list(path("/labels", aid)).map(MailLabel::from)
                if (generation == listGeneration) {
                    items = result.array("items").objects().map(::Mail); next = result.str("nextCursor"); labels = newLabels
                    selected.retainAll(items.map { it.threadId }.toSet())
                }
            } catch (e: Exception) { if (generation == listGeneration && (!quiet || e is ApiFailure && e.code == "unauthorized")) fail(e) }
            finally { if (generation == listGeneration) { loading = false; changed() } }
        }
    }
    suspend fun readThread(aid: String, tid: String) {
        val generation = authGeneration
        val result = api.list(path("/threads/${enc(tid)}", aid)).map(::Mail)
        if (generation != authGeneration || aid != active) return
        messages = result; changed()
        val unread = result.filter { "UNREAD" in it.labels }.map { it.id }
        if (unread.isNotEmpty()) {
            api.json(path("/messages/modify", aid), "POST", obj("ids" to JSONArray(unread), "remove" to JSONArray(listOf("UNREAD"))))
            items.filter { it.threadId == tid }.forEach { it.raw.put("labels", JSONArray(it.labels - "UNREAD")) }; changed()
        }
    }
    suspend fun modify(add: List<String> = emptyList(), remove: List<String> = emptyList(), action: String = "labels", ids: List<String> = emptyList(), threads: List<String> = selected.toList()) {
        if (ids.isEmpty() && threads.isEmpty()) return
        api.json(path("/messages/modify"), "POST", obj("ids" to JSONArray(ids), "threadIds" to JSONArray(if (ids.isEmpty()) threads else emptyList<String>()), "add" to JSONArray(add), "remove" to JSONArray(remove), "action" to action))
        messages.filter { ids.contains(it.id) || threads.contains(it.threadId) }.forEach { it.raw.put("labels", JSONArray((it.labels - remove.toSet() + add).distinct())) }
        selected.clear(); loadList(); notice("邮件已更新"); changed()
    }
    fun persistCompose() {
        val state = compose ?: return
        if (!hasDraft) return // 没编辑过就不写本地草稿：避免留下空草稿与多余的继续编辑入口。
        vault.write("draft:${state.owner}", state.stored())
    }
    fun startCompose(mode: String = "", mail: Mail? = null) {
        if (compose != null) { notice("已恢复未完成的邮件"); return }
        val owner = user?.str("id") ?: return
        if (active.isBlank()) return
        val state = ComposeState(owner, active)
        if (mail != null) {
            val p = state.payload; val m = mail.raw
            p.put("subject", if (Regex("^(re|fwd):", RegexOption.IGNORE_CASE).containsMatchIn(mail.subject)) mail.subject else (if (mode == "forward") "Fwd: " else "Re: ") + mail.subject)
            fun address(s: String) = Regex("<([^>]+)>").find(s)?.groupValues?.get(1) ?: s.trim()
            if (mode != "forward") {
                p.put("to", address(mail.from)); p.put("inReplyTo", m.str("messageId")); p.put("references", (m.str("references") + " " + m.str("messageId")).trim()); p.put("threadId", mail.threadId)
                if (mode == "replyAll") {
                    val own = accounts.find { it.id == active }?.email.orEmpty()
                    p.put("cc", (m.str("to") + "," + m.str("cc")).split(',').map(::address).filter { it.isNotBlank() && !it.equals(own, true) && !it.equals(p.str("to"), true) }.distinctBy { it.lowercase() }.joinToString(", "))
                }
            } else state.attachments = mail.attachments.map { it.copy(messageId = mail.id) }
            p.put("text", "\n\n" + (if (mode == "forward") "---------- 转发邮件 ----------" else "在 ${m.str("date")}，${mail.from} 写道：") + "\n" + m.str("text").lines().joinToString("\n") { "> $it" })
            state.dirty = true
        }
        compose = state; savedText = ""; persistCompose(); if (state.dirty) dirty(); changed()
    }
    suspend fun openDraft(mail: Mail) {
        if (compose != null) { notice("已恢复未完成的邮件"); return }
        val drafts = api.list(path("/drafts"))
        var found = drafts.find { it.str("messageId") == mail.id || it.str("id") == mail.id }
        if (found == null) for (draft in drafts) {
            if (api.json(path("/drafts/${enc(draft.str("id"))}")).str("threadId") == mail.threadId) { found = draft; break }
        }
        val id = found?.str("id") ?: throw ApiFailure("not_found", 404, "草稿未找到，请刷新后重试")
        val message = Mail(api.json(path("/drafts/${enc(id)}")))
        startCompose()
        compose!!.apply {
            listOf("to", "cc", "bcc", "subject", "text", "references", "threadId").forEach { payload.put(it, message.raw.str(it)) }
            payload.put("draftId", id)
            if (payload.str("subject") in listOf("(无主题)", "（无主题）")) payload.put("subject", "")
            attachments = message.attachments.map { it.copy(messageId = message.id) }
        }
        savedText = "已保存"; persistCompose(); changed()
    }
    fun dirty() {
        val state = compose ?: return
        if (state.uncertain) return
        state.dirty = true; savedText = ""; persistCompose(); changed(); autosave?.cancel()
        autosave = viewModelScope.launch {
            delay(2000)
            // Cancelling a debounce must not cancel a write already sent to the server.
            viewModelScope.launch { try { saveDraft() } catch (e: Exception) { fail(e) } }
        }
    }
    suspend fun saveDraft() = draftMutex.withLock {
        do { saveDraftLocked() } while (compose?.let { it.dirty && !it.uncertain } == true && !uploading)
    }
    suspend fun closeCompose() {
        autosave?.cancel(); saveDraft()
        val state = compose ?: return
        if (!state.dirty && !state.uncertain) {
            // 草稿已存在服务端，返回主页不触发拉取，列表等手动刷新或冷启动再更新。
            vault.write("draft:${state.owner}", null); compose = null; savedText = ""; changed()
        }
    }
    private suspend fun saveDraftLocked() {
        val state = compose ?: return
        if (!state.dirty || state.uncertain || uploading) return
        // 内容被清空回初始状态（等于没有编辑过）时不再写入草稿，避免留下空草稿。
        if (!hasDraft) {
            state.dirty = false
            savedText = ""
            return
        }
        composeBusy = true; savedText = "正在保存"; changed()
        state.payload.put("version", state.payload.optInt("version", 1) + 1)
        val snapshot = JSONObject(state.payload.toString()); state.dirty = false
        try {
            val result = api.json(path("/drafts", state.account), "PUT", snapshot)
            state.payload.put("draftId", result.str("id"))
            val old = snapshot.array("attachments").objects().map(Attachment::from)
            val updated = result.array("attachments").objects().map(Attachment::from)
            state.attachments = state.attachments.map { a -> val i = old.indexOfFirst { it.id == a.id && it.messageId == a.messageId }; updated.getOrNull(i) ?: a }
            savedText = if (state.dirty) "" else "已保存"
        } catch (e: Exception) { state.dirty = true; savedText = "未保存至邮箱"; throw e }
        finally { composeBusy = false; persistCompose(); changed() }
    }
    suspend fun send() = draftMutex.withLock {
        val state = compose ?: return@withLock
        if (state.uncertain || uploading || composeBusy) return@withLock
        if (state.payload.str("to").isBlank() && state.payload.str("cc").isBlank() && state.payload.str("bcc").isBlank()) throw ApiFailure("validation", 422, "请填写收件人")
        autosave?.cancel(); saveDraftLocked(); composeBusy = true
        // Persist before dispatch: process death must not allow an accidental resend.
        state.uncertain = true; persistCompose(); changed()
        try {
            val result = api.json(path("/send", state.account), "POST", state.payload)
            val refused = result.optJSONObject("result")?.optJSONArray("refused")
            notice(result.str("warning").ifBlank { if (refused != null && refused.length() > 0) "邮件已发送，部分收件人未能接收" else "邮件已发送" })
            vault.write("draft:${state.owner}", null); compose = null
            if (folder == "DRAFT") loadList() // 仅草稿箱需要立即移除已发送的草稿。
        } catch (e: Exception) {
            state.uncertain = e !is ApiFailure || e.code == "send_uncertain" || e.status >= 500
            persistCompose(); throw e
        } finally { composeBusy = false; changed() }
    }
    suspend fun discard() = draftMutex.withLock {
        autosave?.cancel(); val state = compose ?: return@withLock
        state.payload.str("draftId").takeIf { it.isNotBlank() }?.let { api.json(path("/drafts/${enc(it)}", state.account), "DELETE") }
        vault.write("draft:${state.owner}", null); compose = null; savedText = ""
        if (folder == "DRAFT") loadList() // 仅草稿箱需要立即移除已删除的草稿。
        changed()
    }
    suspend fun checkOauth(): Boolean {
        val ticket = vault.read("oauth") ?: return false
        val result = api.call("/gmail-accounts/oauth/mobile/status", ticket = ticket) as JSONObject
        when (result.str("status")) {
            "waiting" -> return true
            "success" -> { vault.write("oauth", null); refreshAccounts(); loadList(); notice("邮箱已连接") }
            "cancelled" -> { vault.write("oauth", null); notice("已取消连接") }
            else -> { vault.write("oauth", null); notice("连接未完成，请重试") }
        }
        return false
    }

    companion object {
        @Volatile private var shared: MailModel? = null
        /** 进程级共享：挂后台被回收后重建 Activity 时沿用同一份会话与列表，不重新拉取。 */
        fun of(app: Application) = shared ?: synchronized(this) { shared ?: MailModel(app).also { shared = it } }
    }
}

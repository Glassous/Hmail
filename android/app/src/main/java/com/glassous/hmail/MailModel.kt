package com.glassous.hmail

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

data class MailboxState(
    val accounts: List<Account> = emptyList(),
    val active: String = "",
    val labels: List<MailLabel> = emptyList(),
    val accountsLoading: Boolean = false,
    val accountsError: String? = null,
    val labelsLoading: Boolean = false,
    val labelsError: String? = null
)

class MailModel private constructor(app: Application) : AndroidViewModel(app) {
    val vault = Vault(app)
    val api = MailApi(vault)
    private val listCache = MailListCache(app)
    private val cacheWrites = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    init {
        // One ordered writer prevents old snapshots from being written after logout cleanup.
        viewModelScope.launch(Dispatchers.IO) {
            for (write in cacheWrites) {
                try { write() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Cache failure must never discard a successful network response. */ }
            }
        }
    }
    /** 本地是否存在未过期的会话 Cookie；只用于决定首屏，不发起网络请求。 */
    val loggedIn get() = user != null || api.hasSession
    val revision = MutableStateFlow(0)
    private var localRestored = false
    private val sessionMutex = Mutex()
    private var sessionReady = false
    var localOnly by mutableStateOf(false)
        private set
    var initialized = false
    /** 会话恢复进行中：首页在此期间显示加载态，不用空数据提示误导用户。 */
    var starting = false
    var user: JSONObject? = null
    var config = JSONObject()
    var mailbox by mutableStateOf(MailboxState())
        private set
    val accounts get() = mailbox.accounts
    val labels get() = mailbox.labels
    val active get() = mailbox.active
    var syncing by mutableStateOf(false)
        private set
    var listError by mutableStateOf<String?>(null)
        private set
    var folder = "INBOX"
    var query = ""
    var items = emptyList<Mail>()
    var messages = emptyList<Mail>()
    val selected = linkedSetOf<String>()
    var next = ""
    private var loadedPages = 0
    private var paginationReady = false
    var loadingMore by mutableStateOf(false)
        private set
    var moreError by mutableStateOf<String?>(null)
        private set
    val canLoadMore get() = paginationReady && next.isNotBlank() && !loading
    var historyComplete by mutableStateOf(true)
        private set
    private var indexVersion = 0L
    private val pendingMail = mutableSetOf<String>()
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
    private var labelsJob: Job? = null
    private var syncJob: Job? = null
    private var threadJob: Job? = null
    private var verifyJob: Job? = null
    private var mailboxGeneration = 0
    private var accountGeneration = 0
    private var labelsGeneration = 0
    private var threadGeneration = 0
    private var listGeneration = 0
    private var authGeneration = 0
    fun changed() { revision.value++ }
    fun path(suffix: String, aid: String = active) = "/gmail-accounts/${enc(aid)}$suffix"
    fun form(name: String) = forms.getOrPut(name) { mutableMapOf() }
    fun fail(error: Throwable) {
        if (error is CancellationException) return
        if (error is ApiFailure && error.code in listOf("unauthorized", "csrf")) clearSession()
    }
    /** Runs before setContent, on IO: the first frame already contains the local mailbox. */
    suspend fun restoreLocalMailbox() {
        if (localRestored) return
        localRestored = true
        val saved = listCache.readBootstrap() ?: return
        val savedUser = saved.optJSONObject("user") ?: return
        val owner = savedUser.str("id")
        if (owner.isBlank() || vault.read("local-mailbox-owner") != owner) return
        val savedAccounts = saved.array("accounts").objects().map(Account::from)
        // 冷启动优先落在默认邮箱上；没有默认邮箱时才沿用上次选择的邮箱。
        val aid = savedAccounts.firstOrNull { it.isDefault }?.id
            ?: saved.str("active").takeIf { value -> savedAccounts.any { it.id == value } }
            ?: savedAccounts.firstOrNull()?.id.orEmpty()
        user = savedUser
        mailbox = MailboxState(accounts = savedAccounts, active = aid,
            labels = saved.array("labels").objects().map(MailLabel::from))
        folder = saved.str("folder").ifBlank { "INBOX" }
        query = saved.str("query")
        theme = vault.read("theme") ?: savedUser.str("theme").ifBlank { "system" }
        restoreStoredCompose(owner)
        if (aid.isNotBlank()) {
            listCache.read(MailListKey(owner, aid, folder, query))?.let {
                items = it.items; next = it.next; loadedPages = it.pages
            }
        }
        paginationReady = false
        listPosition = 0; listOffset = 0
        localOnly = true
    }
    private fun persistMailboxContext() {
        val currentUser = user ?: return
        val snapshot = obj("version" to 1, "user" to JSONObject(currentUser.toString()),
            "accounts" to JSONArray(accounts.map { obj("id" to it.id, "email" to it.email,
                "provider" to it.provider, "status" to it.status, "isDefault" to it.isDefault) }),
            "active" to active, "folder" to folder, "query" to query,
            "labels" to JSONArray(labels.map { obj("id" to it.id, "name" to it.name, "type" to it.type) })).toString()
        cacheWrites.trySend { listCache.writeBootstrap(snapshot) }
    }
    private suspend fun ensureSession() = sessionMutex.withLock {
        if (sessionReady) return@withLock
        val generation = authGeneration
        val result = api.json("/me")
        if (generation != authGeneration) throw CancellationException("Session changed")
        val remoteUser = result.getJSONObject("user")
        if (user != null && user!!.str("id") != remoteUser.str("id")) {
            clearSession()
            throw CancellationException("Different mailbox owner")
        }
        user = remoteUser; api.csrf = result.str("csrf"); sessionReady = true
        vault.write("local-mailbox-owner", remoteUser.str("id"))
        if (compose == null) restoreStoredCompose(remoteUser.str("id"))
        theme = remoteUser.str("theme").ifBlank { "system" }
        persistMailboxContext(); changed()
    }
    fun work(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                if (user != null) ensureSession()
                block()
            } catch (e: Exception) { fail(e) }
            finally { changed() }
        }
    }
    fun boot() {
        if (initialized) return
        initialized = true; starting = true
        val generation = authGeneration
        viewModelScope.launch {
            launch {
                try { config = api.json("/config"); changed() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { }
            }
            try {
                if (generation == authGeneration && loggedIn) {
                    ensureSession()
                    if (generation == authGeneration) refreshAccounts(sync = true)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == authGeneration) {
                    localOnly = user != null
                    if (user == null || e is ApiFailure && e.code in listOf("unauthorized", "csrf")) fail(e)
                }
            } finally { starting = false; changed() }
        }
    }
    suspend fun setSession(result: JSONObject, sync: Boolean = false) {
        val incoming = result.getJSONObject("user")
        if (user != null && user!!.str("id") != incoming.str("id")) clearSession(clearAuthentication = false)
        user = incoming; api.csrf = result.str("csrf"); authGeneration++; sessionReady = true; localOnly = false
        vault.write("local-mailbox-owner", incoming.str("id"))
        theme = user!!.str("theme").ifBlank { "system" }; vault.write("theme", theme)
        restoreStoredCompose(user!!.str("id"))
        if (compose?.owner != user!!.str("id")) compose = null
        persistMailboxContext(); changed()
        refreshAccounts(sync); initialized = true; changed()
    }
    fun clearSession(clearAuthentication: Boolean = true) {
        val owner = user?.str("id")
        vault.write("local-mailbox-owner", null)
        cacheWrites.trySend { listCache.writeBootstrap(null) }
        sessionReady = false; localOnly = false
        if (owner != null) cacheWrites.trySend { listCache.retainAccounts(owner, emptySet()) }
        persistCompose(); authGeneration++; listGeneration++; listJob?.cancel(); autosave?.cancel()
        mailboxGeneration++; accountGeneration++; labelsGeneration++; threadGeneration++
        labelsJob?.cancel(); syncJob?.cancel(); threadJob?.cancel(); verifyJob?.cancel()
        if (clearAuthentication) api.clear()
        user = null; compose = null; mailbox = MailboxState(); syncing = false; loading = false; listError = null
        resetPages(); messages = emptyList(); forms.clear(); initialized = true; starting = false; changed()
    }
    suspend fun refreshAccounts(sync: Boolean = false) {
        val auth = authGeneration
        val generation = ++accountGeneration
        mailbox = mailbox.copy(accountsLoading = true, accountsError = null)
        try {
            ensureSession()
            val result = api.list("/gmail-accounts").map(Account::from)
            if (auth != authGeneration || generation != accountGeneration) return
            mailbox = mailbox.copy(accounts = result, accountsLoading = false)
            val owner = user?.str("id").orEmpty()
            cacheWrites.trySend { listCache.retainAccounts(owner, result.map { it.id }.toSet()) }
            if (result.none { it.id == active }) {
                // 当前邮箱已不存在时落到默认邮箱；没有默认邮箱则用列表里最早连接的邮箱。
                switchAccount(result.firstOrNull { it.isDefault }?.id ?: result.firstOrNull()?.id ?: "", sync)
            } else {
                persistMailboxContext()
                refreshLabels()
                loadList(sync = sync, initial = true)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (auth == authGeneration && generation == accountGeneration) {
                mailbox = mailbox.copy(accountsError = "邮箱加载失败，请重试")
                localOnly = items.isNotEmpty()
                fail(e)
            }
        } finally {
            if (auth == authGeneration && generation == accountGeneration) {
                mailbox = mailbox.copy(accountsLoading = false); changed()
            }
        }
    }
    /** 设置默认邮箱，传入空字符串表示取消默认。 */
    suspend fun setDefaultAccount(id: String) {
        api.json("/me/default-account", "PUT", obj("accountId" to id))
        refreshAccounts()
    }
    fun retryAccounts() {
        if (mailbox.accountsLoading) return
        viewModelScope.launch { refreshAccounts(sync = true) }
    }
    fun refreshLabels(expectedAccount: String = active) {
        if (expectedAccount != active) return
        labelsJob?.cancel()
        val generation = ++labelsGeneration
        val auth = authGeneration
        val aid = active
        if (aid.isBlank()) return
        mailbox = mailbox.copy(labelsLoading = true, labelsError = null)
        labelsJob = viewModelScope.launch {
            try {
                val result = api.list(path("/labels", aid)).map(MailLabel::from)
                if (generation == labelsGeneration && auth == authGeneration && aid == active) {
                    mailbox = mailbox.copy(labels = result)
                    persistMailboxContext()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == labelsGeneration && auth == authGeneration && aid == active) {
                    mailbox = mailbox.copy(labelsError = "标签加载失败，点击重试")
                    fail(e)
                }
            } finally {
                if (generation == labelsGeneration && auth == authGeneration && aid == active) {
                    mailbox = mailbox.copy(labelsLoading = false); changed()
                }
            }
        }
    }
    fun switchAccount(id: String, sync: Boolean = false) {
        listJob?.cancel(); labelsJob?.cancel(); syncJob?.cancel(); threadJob?.cancel()
        mailboxGeneration++; listGeneration++; labelsGeneration++; threadGeneration++
        syncing = false
        mailbox = mailbox.copy(active = id, labels = emptyList(), labelsLoading = false, labelsError = null)
        folder = "INBOX"; query = ""; messages = emptyList(); resetPages()
        persistMailboxContext()
        loadList(sync = sync, initial = true)
        refreshLabels()
    }
    fun resetPages() {
        loadedPages = 0; paginationReady = false; next = ""; items = emptyList(); selected.clear()
        loadingMore = false; moreError = null; listPosition = 0; listOffset = 0
    }
    fun chooseFolder(id: String) { syncJob?.cancel();syncing=false; mailboxGeneration++; folder = id; query = ""; resetPages(); persistMailboxContext(); loadList(sync=true,initial=true) }
    fun search(value: String) { query = value.trim(); resetPages(); persistMailboxContext(); loadList() }
    fun loadMore() {
        if (!canLoadMore) return
        loadList(append = true)
    }
    private fun cacheCurrentList() {
        val owner = user?.str("id") ?: return
        if (active.isBlank() || loadedPages == 0) return
        val key = MailListKey(owner, active, folder, query)
        // Copy only list fields before handing the snapshot to the background writer.
        val rows = items.map { mail ->
            obj("id" to mail.id, "threadId" to mail.threadId, "subject" to mail.raw.str("subject"),
                "from" to mail.from, "date" to mail.raw.str("date"), "snippet" to mail.raw.str("snippet"),
                "labels" to JSONArray(mail.labels), "count" to mail.raw.optInt("count", 1))
        }
        val cursor = next
        val pages = loadedPages
        cacheWrites.trySend { listCache.write(key, rows, cursor, pages) }
        persistMailboxContext()
    }
    fun loadList(sync: Boolean = false, quiet: Boolean = false, initial: Boolean = false, append: Boolean = false) {
        if (sync && !initial) { syncMailbox(manual = true); return }
        listJob?.cancel()
        val generation = ++listGeneration
        val auth = authGeneration
        val aid = active
        val chosenFolder = folder
        val chosenQuery = query
        val owner = user?.str("id").orEmpty()
        if (aid.isBlank()) { loading = false; loadingMore = false; listError = null; changed(); return }
        loading = true; loadingMore = append; moreError = null; listError = null
        if (!append) paginationReady = false
        changed()
        listJob = viewModelScope.launch {
            fun current() = generation == listGeneration && auth == authGeneration && aid == active
            try {
                if (!append && loadedPages == 0) {
                    val cached = listCache.read(MailListKey(owner, aid, chosenFolder, chosenQuery))
                    if (!current()) return@launch
                    if (cached != null) {
                        items = cached.items; next = cached.next; loadedPages = cached.pages
                        changed()
                    }
                }
                ensureSession()
                if (!current()) return@launch
                val baseline = items.toList()
                var cursor = if (append) next else ""
                var restarted = false
                val result = try {
                    api.json(path("/threads?folder=${enc(chosenFolder)}&q=${enc(chosenQuery)}&cursor=${enc(cursor)}", aid))
                } catch (e: ApiFailure) {
                    if (e.code != "cursor_expired") throw e
                    restarted = true
                    api.json(path("/threads?folder=${enc(chosenFolder)}&q=${enc(chosenQuery)}", aid))
                }
                if (!current()) return@launch
                val rows = withContext(Dispatchers.Default) { result.array("items").objects().map(::Mail) }
                cursor = result.str("nextCursor")
                if (current()) {
                    val unique = rows.distinctBy { it.threadId }.map { row ->
                        if ("$aid:${row.id}" in pendingMail || "$aid:${row.threadId}" in pendingMail) baseline.find { it.id == row.id } ?: row else row
                    }
                    items = if (append && !restarted) {
                        val existing = baseline.map { it.threadId }.toSet()
                        baseline + unique.filter { it.threadId !in existing }
                    } else unique
                    next = cursor
                    loadedPages = if (append && !restarted) loadedPages + 1 else 1
                    result.optJSONObject("sync")?.let { state ->
                        indexVersion = state.optLong("indexVersion")
                        historyComplete = state.optBoolean("historyComplete")
                    }
                    paginationReady = true; localOnly = false
                    selected.retainAll(items.map { it.threadId }.toSet())
                    cacheCurrentList()
                    changed()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (current()) {
                    localOnly = items.isNotEmpty()
                    if (!append && loadedPages > 0) cacheCurrentList()
                    if (append) moreError = "加载失败，点击重试" else listError = "邮件刷新失败，点击重试"
                    if (!quiet || e is ApiFailure && e.code == "unauthorized") fail(e)
                }
            } finally {
                if (current()) {
                    loading = false; loadingMore = false; changed()
                    if (sync && sessionReady && !localOnly) syncMailbox(manual = false)
                }
            }
        }
    }
    private fun syncMailbox(manual: Boolean) {
        if (active.isBlank()) return
        syncJob?.cancel()
        val aid = active
        val auth = authGeneration
        val mailboxVersion = mailboxGeneration
        val chosenFolder = folder
        syncing = true; changed()
        syncJob = viewModelScope.launch {
            try {
                ensureSession()
                api.json(path("/sync-jobs?folder=${enc(chosenFolder)}", aid), "POST")
                while (isActive && auth == authGeneration && aid == active && mailboxVersion == mailboxGeneration && chosenFolder == folder) {
                    val result = api.json(path("/sync-status?folder=${enc(chosenFolder)}", aid))
                    if (auth != authGeneration || aid != active || mailboxVersion != mailboxGeneration || chosenFolder != folder) break
                    historyComplete = result.optBoolean("historyComplete")
                    if (result.optLong("indexVersion") != indexVersion) { loadList(quiet = true); refreshLabels() }
                    syncing = result.str("status") in listOf("queued", "running", "retry")
                    changed()
                    delay(if (syncing) 2000 else 30000)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (auth == authGeneration && aid == active && mailboxVersion == mailboxGeneration) {
                    localOnly = items.isNotEmpty()
                    fail(e)
                }
            }
            finally { if (auth == authGeneration && aid == active && mailboxVersion == mailboxGeneration) { syncing = false; changed() } }
        }
    }
    suspend fun readThread(aid: String, tid: String) = coroutineScope<Unit> {
        threadJob?.cancel()
        threadJob = currentCoroutineContext()[Job]
        val requestGeneration = ++threadGeneration
        val generation = authGeneration
        val result = api.list(path("/threads/${enc(tid)}", aid)).map(::Mail)
        if (generation != authGeneration || aid != active || requestGeneration != threadGeneration) return@coroutineScope
        messages = result; changed()
        val unread = result.filter { "UNREAD" in it.labels }.map { it.id }
        if (unread.isNotEmpty()) {
            viewModelScope.launch {
                if (generation == authGeneration && aid == active) {
                    try { modify(remove=listOf("UNREAD"), ids=unread, threads=emptyList()) }
                    catch(e: Exception) { fail(e) }
                }
            }
        }
    }
    suspend fun modify(add: List<String> = emptyList(), remove: List<String> = emptyList(), action: String = "labels", ids: List<String> = emptyList(), threads: List<String> = selected.toList()) {
        if (ids.isEmpty() && threads.isEmpty()) return
        val aid = active
        val auth = authGeneration
        val mailboxVersion = mailboxGeneration
        val keys = (if (ids.isEmpty()) threads else ids).map { "$aid:$it" }
        if (keys.any { it in pendingMail }) return
        pendingMail.addAll(keys)
        val snapshots = (items + messages).filter { it.id in ids || it.threadId in threads }.associateWith { it.labels.toList() }
        snapshots.forEach { (mail, before) -> mail.raw.put("labels", JSONArray((before - remove.toSet() + add).distinct())) }
        changed()
        val result = try {
            api.json(path("/messages/modify", aid), "POST", obj("ids" to JSONArray(ids), "threadIds" to JSONArray(if (ids.isEmpty()) threads else emptyList<String>()), "add" to JSONArray(add), "remove" to JSONArray(remove), "action" to action))
        } catch (e: Exception) {
            if (aid == active && auth == authGeneration) snapshots.forEach { (mail, before) -> mail.raw.put("labels", JSONArray(before)) }
            throw e
        } finally { pendingMail.removeAll(keys.toSet()); changed() }
        if (aid != active || auth != authGeneration || mailboxVersion != mailboxGeneration) return
        val failed = result.array("results").objects().filter { !it.optBoolean("ok") }.map { it.str("id") }.toSet()
        snapshots.filter { (mail, _) -> mail.id in failed || mail.threadId in failed }.forEach { (mail, before) -> mail.raw.put("labels", JSONArray(before)) }
        if (failed.isNotEmpty()) { cacheCurrentList(); changed(); throw java.io.IOException("部分邮件未能更新，请重试失败项") }
        messages.filter { ids.contains(it.id) || threads.contains(it.threadId) }.forEach { it.raw.put("labels", JSONArray((it.labels - remove.toSet() + add).distinct())) }
        // A confirmed write updates the local view before reconciliation; never delete the
        // entire local mailbox merely because a refresh could follow this operation.
        items = items.mapNotNull { mail ->
            if (mail.id !in ids && mail.threadId !in threads) return@mapNotNull mail
            val updatedLabels = (mail.labels - remove.toSet() + add).distinct()
            val leavesFolder = when {
                action == "trash" -> folder != "TRASH"
                action == "untrash" -> folder == "TRASH"
                folder in remove -> true
                folder == "ALL" && "SPAM" in add -> true
                else -> false
            }
            if (leavesFolder) null else Mail(JSONObject(mail.raw.toString()).put("labels", JSONArray(updatedLabels)))
        }
        selected.clear(); cacheCurrentList(); changed()
        // Reconciliation is driven by the mailbox index version, not a blocking reload.
    }
    fun persistCompose() {
        val state = compose ?: return
        if (!hasDraft) return // 没编辑过就不写本地草稿：避免留下空草稿与多余的继续编辑入口。
        vault.write("draft:${state.owner}", state.stored())
    }
    /** 从本地恢复未完成的写信；若上次发送结果未确认（如进程被杀），立即启动自动核对。 */
    private fun restoreStoredCompose(owner: String) {
        compose = vault.read("draft:$owner")?.let { runCatching { ComposeState.restore(it) }.getOrNull() }
            ?.takeIf { it.owner == owner }
        compose?.takeIf { it.uncertain }?.let { verifyPendingSend(it) }
    }
    fun startCompose(mode: String = "", mail: Mail? = null) {
        if (compose != null) return
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
    suspend fun openDraft(mail: Mail) = coroutineScope<Unit> {
        if (compose != null) return@coroutineScope
        threadJob?.cancel()
        threadJob = currentCoroutineContext()[Job]
        val generation = ++threadGeneration
        val auth = authGeneration
        val aid = active
        val direct = mail.raw.str("draftId")
        val drafts = if (direct.isNotBlank()) listOf(obj("id" to direct, "messageId" to mail.id)) else api.list(path("/drafts", aid))
        var found = drafts.find { it.str("messageId") == mail.id || it.str("id") == mail.id }
        if (found == null) for (draft in drafts) {
            if (api.json(path("/drafts/${enc(draft.str("id"))}", aid)).str("threadId") == mail.threadId) { found = draft; break }
        }
        val id = found?.str("id") ?: throw ApiFailure("not_found", 404, "草稿未找到，请刷新后重试")
        val message = Mail(api.json(path("/drafts/${enc(id)}", aid)))
        if (auth != authGeneration || aid != active || generation != threadGeneration) return@coroutineScope
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
            api.json(path("/send", state.account), "POST", state.payload)
            finishSend(state)
        } catch (e: Exception) {
            // 只有「确定未投递」的失败才解除锁定；其余保持待确认，交给自动核对收敛。
            state.uncertain = when {
                e !is ApiFailure -> true
                e.code == "send_failed" || e.code == "network" -> false
                e.code == "send_uncertain" -> true
                else -> e.status >= 500
            }
            persistCompose()
            if (state.uncertain) verifyPendingSend(state)
            throw e
        } finally { composeBusy = false; changed() }
    }
    /** 发送成功（含核对确认）后的统一收尾：清本地草稿、刷新全部邮箱与当前列表。收尾失败不得改变「已发送」。 */
    private fun finishSend(state: ComposeState) {
        state.uncertain = false
        if (compose === state) {
            compose = null; savedText = ""
            try { vault.write("draft:${state.owner}", null) } catch (_: Exception) { }
        }
        refreshAll()
        loadList(sync = true)
        changed()
    }
    /** 刷新全部邮箱：服务端会把该账户下所有已连接邮箱的所有文件夹重新同步（邮箱服务器 + 数据库索引）。 */
    fun refreshAll() {
        viewModelScope.launch {
            try { api.json("/sync-all", "POST") }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(e) }
        }
    }
    /**
     * 发送结果不明确（响应丢失/连接中断）时自动与服务端核对。
     * 服务端会在「已发送」文件夹确认该邮件是否真的发出：
     * 确认已发出则按成功收尾；确认从未发出则解除锁定，允许重新编辑发送。
     */
    private fun verifyPendingSend(state: ComposeState) {
        verifyJob?.cancel()
        val aid = state.account
        val composeId = state.payload.str("composeId")
        if (composeId.isBlank() || user == null) return
        verifyJob = viewModelScope.launch {
            repeat(6) { attempt ->
                if (compose !== state || !state.uncertain) return@launch
                delay(if (attempt == 0) 1500 else 4000)
                if (compose !== state || !state.uncertain) return@launch
                val result = try { api.json(path("/send-status?composeId=${enc(composeId)}", aid)) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { if (e is ApiFailure && e.code in listOf("unauthorized", "csrf")) fail(e); null }
                    ?: return@repeat
                when (result.str("status")) {
                    "sent" -> { finishSend(state); return@launch }
                    // 服务端没有该会话或已作废：确认从未投递，解除锁定让用户重新发送。
                    "none", "discarded" -> { state.uncertain = false; persistCompose(); changed(); return@launch }
                }
            }
        }
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
            "success" -> { vault.write("oauth", null); refreshAccounts(); loadList() }
            else -> vault.write("oauth", null)
        }
        return false
    }

    companion object {
        @Volatile private var shared: MailModel? = null
        /** 进程级共享：挂后台被回收后重建 Activity 时沿用同一份会话与列表，不重新拉取。 */
        fun of(app: Application) = shared ?: synchronized(this) { shared ?: MailModel(app).also { shared = it } }
    }
}

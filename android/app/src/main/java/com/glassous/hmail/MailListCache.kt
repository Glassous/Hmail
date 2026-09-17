package com.glassous.hmail

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal data class MailListKey(val owner: String, val account: String, val folder: String, val query: String) {
    fun storageKey(): String = MessageDigest.getInstance("SHA-256")
        .digest(JSONArray(listOf(owner, account, folder, query)).toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

internal data class CachedMailList(val items: List<Mail>, val next: String, val pages: Int)

/** Durable encrypted mailbox state. Local lists remain available until logout or account removal. */
internal class MailListCache(context: Context) {
    private val app = context.applicationContext
    private val storage by lazy { Vault(app, "hmail-mail-lists") }
    private val mutex = Mutex()

    suspend fun read(key: MailListKey): CachedMailList? = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val raw = storage.read(key.storageKey()) ?: return@withLock null
                val data = JSONObject(raw)
                if (data.optInt("version") != 1) return@withLock null
                // 统一视图里不同邮箱可能有相同 threadId，去重必须带上所属邮箱。
                CachedMailList(data.array("items").objects().map(::Mail).distinctBy { it.accountId + ":" + it.threadId },
                    data.str("next"), data.optInt("pages", 1).coerceAtLeast(1))
            }.getOrNull()
        }
    }

    suspend fun write(key: MailListKey, rows: List<JSONObject>, next: String, pages: Int) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val snapshot = obj("version" to 1, "owner" to key.owner, "account" to key.account,
                "folder" to key.folder, "query" to key.query,
                "savedAt" to System.currentTimeMillis(), "items" to JSONArray(rows), "next" to next, "pages" to pages)
            val serialized = snapshot.toString()
            storage.write(key.storageKey(), serialized)
        }
    }

    suspend fun retainAccounts(owner: String, accounts: Set<String>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            storage.names().filter { it != "bootstrap" }.forEach { name ->
                val data = runCatching { JSONObject(storage.read(name).orEmpty()) }.getOrNull()
                if (data == null || data.str("owner") == owner && data.str("account") !in accounts)
                    storage.write(name, null)
            }
        }
    }

    suspend fun readBootstrap(): JSONObject? = withContext(Dispatchers.IO) {
        mutex.withLock { runCatching { JSONObject(storage.read("bootstrap") ?: return@withLock null) }.getOrNull() }
    }

    suspend fun writeBootstrap(snapshot: String?) = withContext(Dispatchers.IO) {
        mutex.withLock { storage.write("bootstrap", snapshot) }
    }
}

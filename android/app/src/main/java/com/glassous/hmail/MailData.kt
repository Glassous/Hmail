package com.glassous.hmail

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val SERVER = "https://hmail.fiacloud.top"
fun enc(value: String): String = Uri.encode(value)
fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().apply { pairs.forEach { put(it.first, it.second ?: JSONObject.NULL) } }
fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
fun JSONObject.str(key: String) = if (isNull(key)) "" else optString(key, "")
fun JSONObject.array(key: String) = optJSONArray(key) ?: JSONArray()

/** Only ciphertext is written to preferences; the AES key never leaves Keystore. */
class Vault(context: Context, name: String = "hmail-vault") {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("hmail-storage", null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("hmail-storage", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    // Constructed on Dispatchers.IO; subsequent reads use this in-memory snapshot.
    private val values = prefs.all.keys.mapNotNull { name -> decrypt(name)?.let { name to it } }.toMap().toMutableMap()
    @Synchronized fun read(name: String): String? = values[name]
    @Synchronized fun names(): List<String> = values.keys.toList()
    private fun decrypt(name: String): String? {
        val raw = prefs.getString(name, null) ?: return null
        return try {
            val bytes = Base64.decode(raw, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        } catch (_: Exception) { prefs.edit().remove(name).commit(); null }
    }
    @Synchronized fun write(name: String, value: String?) {
        if (value == null) { values.remove(name); prefs.edit().remove(name).commit(); return }
        values[name] = value
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        prefs.edit().putString(name, Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)).commit()
    }
}

class ApiFailure(val code: String, val status: Int, message: String) : IOException(message)
class MailApi(private val vault: Vault) {
    @Volatile var csrf = ""
    @Volatile private var sessionCookie = vault.read("session")?.let { Cookie.parse(SERVER.toHttpUrl(), it) }
    val hasSession get() = sessionCookie?.expiresAt?.let { it > System.currentTimeMillis() } == true
    private val cookies = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (url.host != "hmail.fiacloud.top") return
            cookies.firstOrNull { it.name == "hmail_session" }?.let {
                sessionCookie = it.takeIf { cookie -> cookie.expiresAt > System.currentTimeMillis() }
                vault.write("session", sessionCookie?.toString())
            }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            if (url.host != "hmail.fiacloud.top" || !url.isHttps) return emptyList()
            val cookie = sessionCookie ?: return emptyList()
            return if (cookie.matches(url) && cookie.expiresAt > System.currentTimeMillis()) listOf(cookie) else emptyList()
        }
    }
    private val client = OkHttpClient.Builder().cookieJar(cookies).followRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()
    fun clear() { csrf = ""; sessionCookie = null; vault.write("session", null); vault.write("oauth", null) }
    private suspend fun execute(request: Request): ByteArray = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        val bytes = it.body?.bytes() ?: ByteArray(0)
                        if (!it.isSuccessful) {
                            val error = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrDefault(JSONObject())
                            throw ApiFailure(error.str("code"), it.code, error.str("message").ifBlank { "暂时无法完成，请稍后重试" })
                        }
                        if (continuation.isActive) continuation.resume(bytes)
                    }
                } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
            }
        })
    }
    private fun request(path: String, method: String, body: RequestBody? = null, ticket: String? = null): Request {
        if (method != "GET" && csrf.isBlank() && !path.startsWith("/auth/"))
            throw ApiFailure("session_unavailable", 0, "请联网恢复会话后重试")
        val builder = Request.Builder().url(SERVER + "/api/v1" + path).header("Accept", "application/json")
        if (method != "GET") builder.header("Origin", SERVER).header("X-CSRF-Token", csrf)
        ticket?.let { builder.header("X-OAuth-Ticket", it) }
        return builder.method(method, body ?: if (method == "GET") null else ByteArray(0).toRequestBody()).build()
    }
    suspend fun call(path: String, method: String = "GET", data: JSONObject? = null, ticket: String? = null): Any {
        val bytes = execute(request(path, method, data?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType()), ticket))
        return withContext(Dispatchers.Default) { JSONTokener(String(bytes, Charsets.UTF_8)).nextValue() }
    }
    suspend fun json(path: String, method: String = "GET", data: JSONObject? = null) = call(path, method, data) as JSONObject
    suspend fun list(path: String) = (call(path) as JSONArray).objects()
    suspend fun upload(aid: String, name: String, type: String, bytes: ByteArray): Attachment {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file", name, bytes.toRequestBody(type.toMediaType())).build()
        val responseBytes = execute(request("/gmail-accounts/${enc(aid)}/attachments", "POST", body))
        return withContext(Dispatchers.Default) { Attachment.from(JSONObject(String(responseBytes, Charsets.UTF_8))) }
    }
    suspend fun download(path: String) = execute(request(path, "GET"))
}

data class Account(val id: String, val email: String, val provider: String, val status: String, val isDefault: Boolean = false) {
    companion object { fun from(j: JSONObject) = Account(j.str("id"), j.str("email"), j.str("provider"), j.str("status"), j.optBoolean("isDefault")) }
}
data class MailLabel(val id: String, val name: String, val type: String) {
    companion object { fun from(j: JSONObject) = MailLabel(j.str("id"), j.str("name"), j.str("type")) }
}
data class Attachment(val id: String, val name: String, val size: Long, val messageId: String = "") {
    fun json() = obj("id" to id, "name" to name, "size" to size, "messageId" to messageId.ifBlank { null })
    companion object { fun from(j: JSONObject) = Attachment(j.str("id"), j.str("name"), j.optLong("size"), j.str("messageId")) }
}
data class Mail(val raw: JSONObject) {
    val id get() = raw.str("id")
    val threadId get() = raw.str("threadId")
    /** 统一视图才会下发这两个字段；单账户列表为空串，由调用方回退到当前账户。 */
    val accountId get() = raw.str("accountId")
    val account get() = raw.str("account")
    val subject get() = raw.str("subject").ifBlank { "（无主题）" }
    val from get() = raw.str("from")
    val labels get() = (0 until raw.array("labels").length()).map { raw.array("labels").getString(it) }
    val attachments get() = raw.array("attachments").objects().map(Attachment::from)
}
data class ComposeState(val owner: String, val account: String, val payload: JSONObject = obj(
    "to" to "", "cc" to "", "bcc" to "", "subject" to "", "text" to "", "inReplyTo" to "", "references" to "",
    "threadId" to null, "draftId" to null, "composeId" to UUID.randomUUID().toString(), "version" to 1, "attachments" to JSONArray()
), var dirty: Boolean = false, var uncertain: Boolean = false) {
    var attachments: List<Attachment>
        get() = payload.array("attachments").objects().map(Attachment::from)
        set(value) { payload.put("attachments", JSONArray(value.map { it.json() })) }
    fun stored() = obj("owner" to owner, "account" to account, "payload" to payload, "dirty" to dirty, "uncertain" to uncertain).toString()
    companion object { fun restore(raw: String): ComposeState { val j = JSONObject(raw); return ComposeState(j.str("owner"), j.str("account"), j.getJSONObject("payload"), j.optBoolean("dirty"), j.optBoolean("uncertain")) } }
}

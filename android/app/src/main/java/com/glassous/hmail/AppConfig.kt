package com.glassous.hmail

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** 只放行 http/https/mailto，避免从邮件内容里打开任意 scheme。 */
fun openExternal(context: Context, url: String) {
    val uri = Uri.parse(url)
    if (uri.scheme !in listOf("https", "http", "mailto")) return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
    } catch (_: Exception) {
        // 没有可打开链接的应用时静默失败，与用户取消打开等效。
    }
}

/** 系统文件夹：键为服务端标签，值为界面名称。 */
val folders = linkedMapOf(
    "INBOX" to "收件箱",
    "STARRED" to "已加星标",
    "SENT" to "已发送",
    "DRAFT" to "草稿",
    "ALL" to "所有邮件",
    "SPAM" to "垃圾邮件",
    "TRASH" to "回收站"
)

/** 列表日期：优先 ISO 8601，其次 RFC 1123，都解析失败时截断原字符串。 */
fun shortDate(value: String): String {
    val instant = runCatching { Instant.parse(value) }.getOrNull()
        ?: runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
    return instant?.atZone(java.time.ZoneId.systemDefault())?.let { "${it.monthValue}月${it.dayOfMonth}日" } ?: value.take(16)
}

fun sizeText(size: Long): String =
    if (size >= 1048576) String.format(java.util.Locale.ROOT, "%.1f MB", size / 1048576.0)
    else "${(size / 1024).coerceAtLeast(1)} KB"

package com.glassous.hmail

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.SignStyle
import java.time.format.TextStyle
import java.time.temporal.ChronoField
import java.util.Locale

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

/** 「全部账户」在账户选择器里的哨兵值：不是真实账户，用于跨邮箱统一视图。 */
const val ALL_ACCOUNTS = "__all__"

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

private val MAIL_DATE_MONTHS = mapOf(
    "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
    "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12
)

/**
 * 邮件 Date 头的宽松格式：星期可选、秒可选、时区可缺省。
 * 各家客户端写出的 RFC 5322 变体（缺秒、只有 GMT、带 `(UTC)` 注释等）都能落到这里。
 */
private val LENIENT_MAIL_DATE: DateTimeFormatter = DateTimeFormatterBuilder()
    .parseCaseInsensitive()
    .parseLenient()
    .optionalStart().appendText(ChronoField.DAY_OF_WEEK, TextStyle.SHORT).appendLiteral(',').optionalEnd()
    .appendLiteral(' ')
    .appendValue(ChronoField.DAY_OF_MONTH, 1, 2, SignStyle.NOT_NEGATIVE)
    .appendLiteral(' ')
    .appendText(ChronoField.MONTH_OF_YEAR, TextStyle.SHORT)
    .appendLiteral(' ')
    .appendValue(ChronoField.YEAR, 2, 4, SignStyle.NOT_NEGATIVE)
    .appendLiteral(' ')
    .appendValue(ChronoField.HOUR_OF_DAY, 1, 2, SignStyle.NOT_NEGATIVE)
    .appendLiteral(':')
    .appendValue(ChronoField.MINUTE_OF_HOUR, 1, 2, SignStyle.NOT_NEGATIVE)
    .optionalStart().appendLiteral(':').appendValue(ChronoField.SECOND_OF_MINUTE, 1, 2, SignStyle.NOT_NEGATIVE).optionalEnd()
    .optionalStart().appendLiteral(' ').appendOffset("+HHMM", "GMT").optionalEnd()
    .toFormatter(Locale.ENGLISH)

/** `2024-01-05 12:00[:ss][ +0800]` 这类缺 T、空格分隔偏移的 ISO 变体。 */
private val SPACED_ISO = Regex("^(\\d{4}-\\d{2}-\\d{2})[ T](\\d{2}:\\d{2}(?::\\d{2})?(?:\\.\\d+)?)\\s*([+-]\\d{2}:?\\d{2}|Z)?$")

/** ISO 系列要求 `+08:00` 形式，而邮件里常见 `+0800`，解析前统一补上冒号。 */
private val TRAILING_OFFSET = Regex("([+-]\\d{2})(\\d{2})\\s*$")

private fun isoOffset(text: String): String = TRAILING_OFFSET.replace(text) { "${it.groupValues[1]}:${it.groupValues[2]}" }

/** 兜底：直接从文本里取出「日 月 年」，兼容月份写成全称的情况。 */
private val TEXT_DATE = Regex("(\\d{1,2})[\\s.\\-]+([A-Za-z]{3,9})[\\s.\\-]+(\\d{4})")

/** 去掉 `(UTC)` 这类注释、把 `+08:00` 归一到 RFC 的 `+0800`，并规整空白。 */
private fun normalizeMailDate(text: String): String = text
    .replace(Regex("\\([^)]*\\)"), " ")
    .replace(Regex("([+-]\\d{2}):(\\d{2})\\b"), "$1$2")
    .replace(Regex("\\s+"), " ")
    .trim()

private fun parseMailInstant(text: String): Instant? {
    // 极端情况下服务端给出的可能是 epoch 秒或毫秒。
    text.toLongOrNull()?.let { epoch ->
        return if (epoch > 100_000_000_000L) Instant.ofEpochMilli(epoch) else Instant.ofEpochSecond(epoch)
    }
    val isoText = isoOffset(text)
    runCatching { return Instant.parse(isoText) }
    runCatching { return OffsetDateTime.parse(isoText, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant() }
    runCatching { return ZonedDateTime.parse(isoText, DateTimeFormatter.ISO_ZONED_DATE_TIME).toInstant() }
    SPACED_ISO.matchEntire(text.trim())?.let { match ->
        val values = match.groupValues
        val iso = isoOffset(values[1] + "T" + values[2] + values[3])
        runCatching { return Instant.parse(iso) }
        runCatching { return OffsetDateTime.parse(iso, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant() }
        runCatching { return LocalDateTime.parse(iso, DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(ZoneId.systemDefault()).toInstant() }
    }
    runCatching { return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay(ZoneId.systemDefault()).toInstant() }
    // RFC 5322 邮件头：先标准格式，再用宽松格式兜住缺秒、缺时区、单数字日期等写法。
    val cleaned = normalizeMailDate(text)
    runCatching { return ZonedDateTime.parse(cleaned, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }
    val parsed = runCatching { LENIENT_MAIL_DATE.parse(cleaned) }.getOrNull() ?: return null
    return if (runCatching { ZoneOffset.from(parsed) }.isSuccess) OffsetDateTime.from(parsed).toInstant()
    else LocalDateTime.from(parsed).atZone(ZoneId.systemDefault()).toInstant()
}

/** 仍解析不出时间点时，直接从文本取「日 月 年」，保证界面始终是中文日期。 */
private fun textMailDate(text: String): String? {
    val match = TEXT_DATE.find(text) ?: return null
    val day = match.groupValues[1].toIntOrNull()?.takeIf { it in 1..31 } ?: return null
    val month = MAIL_DATE_MONTHS[match.groupValues[2].lowercase(Locale.ROOT).take(3)] ?: return null
    return "${month}月${day}日"
}

/** 列表日期：能解析成时间点就按系统时区显示「M月D日」，否则从文本提取，最后才截断原串。 */
fun shortDate(value: String): String {
    val text = value.trim()
    if (text.isEmpty()) return ""
    parseMailInstant(text)?.let { instant ->
        val local = instant.atZone(ZoneId.systemDefault())
        return "${local.monthValue}月${local.dayOfMonth}日"
    }
    return textMailDate(text) ?: text.take(16)
}

fun sizeText(size: Long): String =
    if (size >= 1048576) String.format(java.util.Locale.ROOT, "%.1f MB", size / 1048576.0)
    else "${(size / 1024).coerceAtLeast(1)} KB"

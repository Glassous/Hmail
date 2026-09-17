package com.glassous.hmail

import androidx.annotation.DrawableRes

/**
 * 已适配的邮箱服务商图标：按邮箱域名匹配 drawable，未适配的服务商返回 null，调用方不占位。
 * 域名表与 Web 端 `frontend/src/providers.ts` 保持一致，改动时两边同步。
 */
private val providerIcons: List<Pair<List<String>, Int>> = listOf(
    listOf("gmail.com", "googlemail.com") to R.drawable.google_icon,
    listOf("outlook.com", "hotmail.com", "live.com", "msn.com") to R.drawable.microsoft_outlook_icon,
    listOf("qq.com", "vip.qq.com", "foxmail.com") to R.drawable.qqmail,
    listOf("163.com", "vip.163.com", "126.com", "vip.126.com") to R.drawable.netease,
    listOf("yahoo.com", "yahoo.co.jp", "ymail.com") to R.drawable.yahoo_icon,
    listOf("icloud.com", "me.com", "mac.com") to R.drawable.icloud_logo,
)

/** 邮箱地址对应的服务商图标资源；「全部账户」这类没有域名的字符串会返回 null。 */
@DrawableRes
fun providerIcon(email: String): Int? {
    val domain = email.substringAfter('@', "").trim().lowercase()
    if (domain.isEmpty()) return null
    return providerIcons
        .firstOrNull { (domains, _) -> domains.any { domain == it || domain.endsWith(".$it") } }
        ?.second
}

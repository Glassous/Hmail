package com.glassous.hmail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** 列表日期格式化：覆盖各家客户端写出的真实 Date 头写法。 */
class MailDateTest {
    private fun expected(instant: Instant): String {
        val local = instant.atZone(ZoneId.systemDefault())
        return "${local.monthValue}月${local.dayOfMonth}日"
    }

    private fun assertDate(raw: String, instant: Instant) = assertEquals(expected(instant), shortDate(raw))

    @Test
    fun standardRfc5322Header() {
        assertDate("Mon, 01 Jan 2024 00:00:00 +0000", Instant.parse("2024-01-01T00:00:00Z"))
    }

    @Test
    fun singleDigitDay() {
        assertDate("Tue, 5 Jul 2023 12:34:56 +0800", Instant.parse("2023-07-05T04:34:56Z"))
    }

    @Test
    fun weekdayMissing() {
        assertDate("1 Jan 2024 00:00:00 +0000", Instant.parse("2024-01-01T00:00:00Z"))
    }

    @Test
    fun secondsMissing() {
        assertDate("Mon, 1 Jan 2024 00:00 +0000", Instant.parse("2024-01-01T00:00:00Z"))
    }

    @Test
    fun gmtZone() {
        assertDate("Mon, 1 Jan 2024 00:00:00 GMT", Instant.parse("2024-01-01T00:00:00Z"))
    }

    @Test
    fun trailingComment() {
        assertDate("Mon, 01 Jan 2024 00:00:00 +0000 (UTC)", Instant.parse("2024-01-01T00:00:00Z"))
    }

    @Test
    fun colonOffset() {
        assertDate("Wed, 05 Jul 2023 12:34:56 +08:00", Instant.parse("2023-07-05T04:34:56Z"))
    }

    @Test
    fun fullMonthNameAndJunkTail() {
        val formatted = shortDate("Fri, 5 January 2024 09:30:00 +0000 (some text")
        assertTrue(formatted, formatted.endsWith("月5日"))
    }

    @Test
    fun isoInstant() {
        assertDate("2024-01-05T12:00:00Z", Instant.parse("2024-01-05T12:00:00Z"))
    }

    @Test
    fun isoWithoutSeconds() {
        assertDate("2024-01-05T12:00", Instant.parse("2024-01-05T12:00:00Z").atZone(ZoneId.systemDefault()).toInstant())
    }

    @Test
    fun spacedIsoWithOffset() {
        assertDate("2024-01-05 12:00:00 +0800", Instant.parse("2024-01-05T04:00:00Z"))
    }

    @Test
    fun spacedIsoWithoutOffset() {
        val local = java.time.LocalDateTime.parse("2024-01-05T12:00:00").atZone(ZoneId.systemDefault()).toInstant()
        assertDate("2024-01-05 12:00:00", local)
    }

    @Test
    fun epochMilliseconds() {
        assertDate("1700000000000", Instant.ofEpochMilli(1700000000000))
    }

    @Test
    fun blankValueStaysBlank() {
        assertEquals("", shortDate("   "))
    }
}

package com.nfctool.app.core.nfc.ndef

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * vCalendar 编解码测试。
 */
class VCalendarCodecTest {

    @Test
    fun `解析标准 vCalendar`() {
        val text = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            SUMMARY:周会
            DTSTART:20261026T100000
            DTEND:20261026T110000
            LOCATION:三号会议室
            DESCRIPTION:讨论 NFC 方案
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val event = VCalendarCodec.parse(text.toByteArray())
        assertNotNull(event)
        assertEquals("周会", event!!.summary)
        assertEquals("20261026T100000", event.start)
        assertEquals("20261026T110000", event.end)
        assertEquals("三号会议室", event.location)
        assertEquals("讨论 NFC 方案", event.description)
    }

    @Test
    fun `非 vCalendar 内容返回 null`() {
        assertNull(VCalendarCodec.parse("hello".toByteArray()))
    }

    @Test
    fun `生成的 vCalendar 含必要结构`() {
        val text = VCalendarCodec.build(
            summary = "周会",
            start = "20261026T100000",
            end = "20261026T110000",
            location = "三号会议室",
        )
        assertTrue(text.contains("BEGIN:VCALENDAR"))
        assertTrue(text.contains("VERSION:2.0"))
        assertTrue(text.contains("BEGIN:VEVENT"))
        assertTrue(text.contains("SUMMARY:周会"))
        assertTrue(text.contains("DTSTART:20261026T100000"))
        assertTrue(text.contains("LOCATION:三号会议室"))
        assertTrue(text.contains("END:VEVENT"))
        assertTrue(text.contains("END:VCALENDAR"))
    }

    @Test
    fun `生成的 vCalendar 可被自身解析`() {
        val text = VCalendarCodec.build(
            summary = "周会",
            start = "20261026T100000",
            end = "20261026T110000",
        )
        val event = VCalendarCodec.parse(text.toByteArray())
        assertNotNull(event)
        assertEquals("周会", event!!.summary)
        assertEquals("20261026T100000", event.start)
    }

    @Test
    fun `标题为空时构建失败`() {
        assertTrue(runCatching { VCalendarCodec.build(summary = "", start = "", end = "") }.isFailure)
    }
}

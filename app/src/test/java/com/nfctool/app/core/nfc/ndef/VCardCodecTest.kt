package com.nfctool.app.core.nfc.ndef

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * vCard 编解码测试。
 */
class VCardCodecTest {

    @Test
    fun `解析标准 vCard 3_0`() {
        val text = """
            BEGIN:VCARD
            VERSION:3.0
            N:王;小明;;;
            FN:王小明
            ORG:某科技
            TITLE:工程师
            TEL;TYPE=CELL:13800000000
            EMAIL:xiaoming@example.com
            URL:https://example.com
            ADR;TYPE=HOME:上海市浦东新区
            END:VCARD
        """.trimIndent()

        val card = VCardCodec.parse(text.toByteArray())
        assertNotNull(card)
        assertEquals("王小明", card!!.fullName)
        assertEquals("某科技", card.organization)
        assertEquals("工程师", card.title)
        assertEquals(listOf("13800000000"), card.phones)
        assertEquals(listOf("xiaoming@example.com"), card.emails)
        assertEquals(listOf("https://example.com"), card.urls)
        assertEquals("上海市浦东新区", card.address)
    }

    @Test
    fun `解析含转义分号的字段`() {
        val text = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:张\\;三\r\nEND:VCARD"
        val card = VCardCodec.parse(text.toByteArray())
        assertNotNull(card)
        assertEquals("张;三", card!!.fullName)
    }

    @Test
    fun `非 vCard 内容返回 null`() {
        assertNull(VCardCodec.parse("hello world".toByteArray()))
    }

    @Test
    fun `生成的 vCard 含必要字段`() {
        val text = VCardCodec.build(
            fullName = "王小明",
            phone = "13800000000",
            email = "xiaoming@example.com",
            organization = "某科技",
        )
        assertTrue(text.startsWith("BEGIN:VCARD"))
        assertTrue(text.contains("VERSION:3.0"))
        assertTrue(text.contains("FN:王小明"))
        assertTrue(text.contains("TEL;TYPE=CELL:13800000000"))
        assertTrue(text.contains("EMAIL:xiaoming@example.com"))
        assertTrue(text.contains("ORG:某科技"))
        assertTrue(text.trimEnd().endsWith("END:VCARD"))
        // 使用 CRLF 换行，符合 vCard 规范
        assertTrue(text.contains("\r\n"))
    }

    @Test
    fun `生成的 vCard 可被自身解析`() {
        val text = VCardCodec.build(
            fullName = "李四",
            phone = "13900000000",
            email = "lisi@example.com",
        )
        val card = VCardCodec.parse(text.toByteArray())
        assertNotNull(card)
        assertEquals("李四", card!!.fullName)
        assertEquals(listOf("13900000000"), card.phones)
    }

    @Test
    fun `姓名为空时构建失败`() {
        val failed = runCatching { VCardCodec.build(fullName = "  ") }.isFailure
        assertTrue(failed)
    }
}

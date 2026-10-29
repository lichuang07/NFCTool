package com.nfctool.app.core.nfc.ndef

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MECARD 编解码测试。
 */
class MeCardCodecTest {

    @Test
    fun `解析标准 MECARD`() {
        val text = "MECARD:N:王小明;TEL:13800000000;EMAIL:xiaoming@example.com;URL:https://example.com;;"
        val card = MeCardCodec.parse(text.toByteArray())
        assertNotNull(card)
        assertEquals("王小明", card!!.name)
        assertEquals(listOf("13800000000"), card.phones)
        assertEquals(listOf("xiaoming@example.com"), card.emails)
        assertEquals(listOf("https://example.com"), card.urls)
    }

    @Test
    fun `解析多值字段`() {
        val text = "MECARD:N:张三;TEL:13800000000,13900000000;EMAIL:a@x.com,b@x.com;;"
        val card = MeCardCodec.parse(text.toByteArray())
        assertNotNull(card)
        assertEquals(listOf("13800000000", "13900000000"), card!!.phones)
        assertEquals(listOf("a@x.com", "b@x.com"), card.emails)
    }

    @Test
    fun `非 MECARD 内容返回 null`() {
        assertNull(MeCardCodec.parse("MECARDX:N:xxx;;".toByteArray()))
        assertNull(MeCardCodec.parse("BEGIN:VCARD".toByteArray()))
    }

    @Test
    fun `生成的 MECARD 以双分号结尾`() {
        val text = MeCardCodec.build(
            name = "王小明",
            phone = "13800000000",
            email = "xiaoming@example.com",
        )
        assertTrue(text.startsWith("MECARD:N:"))
        assertTrue(text.endsWith(";;"))
    }

    @Test
    fun `生成的 MECARD 可被自身解析`() {
        val text = MeCardCodec.build(
            name = "王小明",
            phone = "13800000000",
            url = "https://example.com",
        )
        val card = MeCardCodec.parse(text.toByteArray())
        assertNotNull(card)
        assertEquals("王小明", card!!.name)
        assertEquals(listOf("13800000000"), card.phones)
        assertEquals(listOf("https://example.com"), card.urls)
    }

    @Test
    fun `姓名中的分隔符被清理`() {
        val text = MeCardCodec.build(name = "王;小明,二")
        assertTrue(text.contains("N:王 小明 二"))
    }

    @Test
    fun `姓名为空时构建失败`() {
        assertTrue(runCatching { MeCardCodec.build(name = "") }.isFailure)
    }
}

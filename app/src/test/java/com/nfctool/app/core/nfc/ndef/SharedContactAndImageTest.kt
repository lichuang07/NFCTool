package com.nfctool.app.core.nfc.ndef

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分享联系人（vCard）与分享图片的解析测试。
 */
class SharedContactAndImageTest {

    // ---------- vCard ----------

    @Test
    fun `识别出 vCard 文本`() {
        assertTrue(SharedContentParser.looksLikeVCard("BEGIN:VCARD\nVERSION:3.0\nEND:VCARD"))
        assertTrue(SharedContentParser.looksLikeVCard("begin:vcard\r\nversion:3.0\r\nend:vcard"))
    }

    @Test
    fun `普通文本不被误判为 vCard`() {
        assertTrue(!SharedContentParser.looksLikeVCard("这是一段普通文字"))
        assertTrue(!SharedContentParser.looksLikeVCard("https://example.com"))
    }

    @Test
    fun `vCard 分享解析出姓名与联系方式`() {
        val vcard = """
            BEGIN:VCARD
            VERSION:3.0
            N:王小明;王小明;;;
            FN:王小明
            ORG:某某科技
            TITLE:高级工程师
            TEL;TYPE=CELL:13800000000
            EMAIL:wang@example.com
            URL:https://example.com
            ADR;TYPE=WORK:;;浦东新区;上海;;
            END:VCARD
        """.trimIndent()

        val parsed = SharedContentParser.parse(vcard)
        assertNotNull(parsed)
        assertEquals(SharedContentParser.Kind.VCARD, parsed!!.kind)
        assertTrue(parsed.isValid)

        val card = parsed.vcard!!
        assertEquals("王小明", card.fullName)
        assertEquals("某某科技", card.organization)
        assertEquals("高级工程师", card.title)
        assertTrue(card.phones.contains("13800000000"))
        assertTrue(card.emails.contains("wang@example.com"))
        assertTrue(card.urls.contains("https://example.com"))
    }

    @Test
    fun `vCard 的标题字段取自姓名`() {
        val vcard = "BEGIN:VCARD\nVERSION:3.0\nFN:李四\nEND:VCARD"
        val parsed = SharedContentParser.parse(vcard)
        assertEquals("李四", parsed?.title)
        assertEquals("李四", parsed?.vcard?.fullName)
    }

    @Test
    fun `内容非法的 vCard 返回错误而非降级`() {
        // BEGIN:VCARD 存在但没有 END —— VCardCodec 解析不出结构
        val parsed = SharedContentParser.parse("BEGIN:VCARD\n随便一些内容")
        assertNotNull(parsed)
        assertEquals(SharedContentParser.Kind.VCARD, parsed!!.kind)
        assertNotNull(parsed.error)
        assertTrue(!parsed.isValid)
    }

    @Test
    fun `微信分享的 vCard 能识别`() {
        // 微信分享联系人时的典型形态
        val vcard = "BEGIN:VCARD\nVERSION:3.0\nN:张;三;;;\nFN:张三\nTEL;TYPE=CELL:13911112222\nEND:VCARD"
        val parsed = SharedContentParser.parse(vcard)
        assertEquals(SharedContentParser.Kind.VCARD, parsed?.kind)
        assertEquals("张三", parsed?.vcard?.fullName)
    }

    // ---------- 图片 ----------

    @Test
    fun `图片解析保留字节与 MIME`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val parsed = SharedContentParser.parseImage(bytes, "image/jpeg")
        assertNotNull(parsed)
        assertEquals(SharedContentParser.Kind.IMAGE, parsed!!.kind)
        assertTrue(parsed.isValid)
        assertEquals("image/jpeg", parsed.imageMime)
        assertEquals(4, parsed.imageBytes!!.size)
    }

    @Test
    fun `图片 MIME 为空时默认用 jpeg`() {
        val parsed = SharedContentParser.parseImage(byteArrayOf(1, 2, 3), null)
        assertEquals("image/jpeg", parsed.imageMime)
    }

    @Test
    fun `空图片返回错误`() {
        val parsed = SharedContentParser.parseImage(ByteArray(0), "image/png")
        assertNotNull(parsed)
        assertEquals(SharedContentParser.Kind.IMAGE, parsed!!.kind)
        assertNotNull(parsed.error)
        assertTrue(!parsed.isValid)
    }

    // ---------- MIME 猜测 ----------

    @Test
    fun `根据文件头猜测图片 MIME`() {
        assertEquals(
            "image/png",
            ImageCompressor.guessMime(
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            ),
        )
        assertEquals(
            "image/jpeg",
            ImageCompressor.guessMime(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())),
        )
        assertEquals(
            "image/gif",
            ImageCompressor.guessMime(byteArrayOf('G'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), '8'.code.toByte())),
        )
        assertEquals(
            "image/bmp",
            ImageCompressor.guessMime(byteArrayOf('B'.code.toByte(), 'M'.code.toByte())),
        )
    }

    @Test
    fun `未知格式回落到二进制类型`() {
        assertEquals(
            "application/octet-stream",
            ImageCompressor.guessMime(byteArrayOf(0x01, 0x02, 0x03, 0x04)),
        )
    }
}

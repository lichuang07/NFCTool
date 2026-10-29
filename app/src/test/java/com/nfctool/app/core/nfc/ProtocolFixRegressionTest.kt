package com.nfctool.app.core.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * 2026-10-25 协议层 P0 修复的回归测试。
 *
 * 覆盖：
 *  1. Text 记录状态字节必须编码语言码长度（早期硬编码 0x00，记录不合规）
 *  2. APDU 6Cxx 重发只能改 Le（case 3 无 Le 时改末字节会写坏数据）
 *  3. APDU 状态字提取
 */
class ProtocolFixRegressionTest {

    // ---------- Text 记录状态字节 ----------

    @Test
    fun `Text 状态字节编码语言码长度`() {
        val payload = NdefCodec.buildTextPayload("你好", Charsets.UTF_8, "zh")
        // status = 0x02：UTF-8 + 语言码 2 字节
        assertEquals(0x02, payload[0].toInt() and 0xFF)
        // 语言码紧跟其后
        assertEquals("zh", String(payload, 1, 2, Charsets.US_ASCII))
        // 正文是 UTF-8 的中文
        assertEquals("你好", String(payload, 3, payload.size - 3, Charsets.UTF_8))
    }

    @Test
    fun `Text 状态字节对英文单位置语言同样正确`() {
        val payload = NdefCodec.buildTextPayload("hello", Charsets.UTF_8, "en")
        assertEquals(0x02, payload[0].toInt() and 0xFF)
        assertEquals("en", String(payload, 1, 2, Charsets.US_ASCII))
        assertEquals("hello", String(payload, 3, payload.size - 3, Charsets.UTF_8))
    }

    @Test
    fun `Text 状态字节 UTF-16 时置 bit7`() {
        val payload = NdefCodec.buildTextPayload("hi", Charsets.UTF_16, "en")
        // 0x80(UTF-16) | 0x02(语言长度)
        assertEquals(0x82, payload[0].toInt() and 0xFF)
    }

    @Test
    fun `Text 状态字节支持三字节语言码`() {
        val payload = NdefCodec.buildTextPayload("x", Charsets.UTF_8, "zh-CN")
        assertEquals(0x05, payload[0].toInt() and 0xFF)
        assertEquals("zh-CN", String(payload, 1, 5, Charsets.US_ASCII))
    }

    @Test
    fun `Text 状态字节不再恒为 0`() {
        // 这条用例守着 2026-10-25 之前的 bug：状态字节硬编码 0x00，
        // 读方按 langLen=0 解析，把 "zh" 当正文开头
        val payload = NdefCodec.buildTextPayload("内容", Charsets.UTF_8, "zh")
        assertTrue("状态字节必须非 0", payload[0].toInt() != 0)
    }

    // ---------- APDU Le 判定（6Cxx 重发安全） ----------

    @Test
    fun `case 2 APDU 判定为带 Le`() {
        // CLA INS P1 P2 Le
        assertTrue(ApduEngine.hasLe(byteArrayOf(0x00, 0xB0.toByte(), 0x00, 0x00, 0x10)))
    }

    @Test
    fun `case 4 APDU 判定为带 Le`() {
        // CLA INS P1 P2 Lc=2 data Le
        assertTrue(ApduEngine.hasLe(byteArrayOf(0x00, 0xD0.toByte(), 0x00, 0x00, 0x02, 0x01, 0x02, 0x00)))
    }

    @Test
    fun `case 3 APDU 判定为不带 Le`() {
        // CLA INS P1 P2 Lc=2 data —— 末字节是数据，不能改
        assertFalse(ApduEngine.hasLe(byteArrayOf(0x00, 0xD6.toByte(), 0x00, 0x00, 0x02, 0x01, 0x02)))
    }

    @Test
    fun `超短 APDU 判定为不带 Le`() {
        assertFalse(ApduEngine.hasLe(byteArrayOf(0x00, 0x60)))
        assertFalse(ApduEngine.hasLe(ByteArray(0)))
    }

    // ---------- 状态字提取 ----------

    @Test
    fun `状态字取最后两字节`() {
        val sw = ApduEngine.statusWord(byteArrayOf(0x01, 0x02, 0x90.toByte(), 0x00))
        assertEquals(2, sw.size)
        assertEquals(0x90.toByte(), sw[0])
        assertEquals(0x00.toByte(), sw[1])
    }

    @Test
    fun `响应不足两字节时状态字为空`() {
        assertEquals(0, ApduEngine.statusWord(byteArrayOf(0x90.toByte())).size)
        assertEquals(0, ApduEngine.statusWord(ByteArray(0)).size)
    }
}

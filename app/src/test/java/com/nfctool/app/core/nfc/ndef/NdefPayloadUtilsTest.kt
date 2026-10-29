package com.nfctool.app.core.nfc.ndef

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NdefPayloadUtils] 单元测试。
 *
 * 重点覆盖 AID 与 URI 的区分判据 —— 这是 `NdefKind.AID` 能否被正确识别的关键。
 */
class NdefPayloadUtilsTest {

    // ---------- isAidPayload ----------

    @Test
    fun `NFC Forum Type 4 的 AID 字节被识别为 AID`() {
        // D2760000850101 是 NFC Forum Type 4 Tag Application 的标准 AID
        val aid = byteArrayOf(
            0xD2.toByte(), 0x76.toByte(), 0x00, 0x00, 0x85.toByte(), 0x01, 0x01,
        )
        assertTrue(NdefPayloadUtils.isAidPayload(aid))
    }

    @Test
    fun `5 字节 AID 被识别为 AID`() {
        val aid = byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x00, 0x03)
        assertTrue(NdefPayloadUtils.isAidPayload(aid))
    }

    @Test
    fun `合法 URI 文本不被误判为 AID`() {
        val uri = "https://example.com".toByteArray(Charsets.UTF_8)
        assertFalse(NdefPayloadUtils.isAidPayload(uri))
    }

    @Test
    fun `短 URI 不被判为 AID 因为长度不足 5`() {
        val uri = "a:b".toByteArray(Charsets.UTF_8)
        assertFalse(NdefPayloadUtils.isAidPayload(uri))
    }

    @Test
    fun `超长字节不被判为 AID 因为超过 16 字节`() {
        val tooLong = ByteArray(17) { 0x00 }
        assertFalse(NdefPayloadUtils.isAidPayload(tooLong))
    }

    @Test
    fun `空 payload 不被判为 AID`() {
        assertFalse(NdefPayloadUtils.isAidPayload(ByteArray(0)))
    }

    // ---------- isUriText ----------

    @Test
    fun `带 scheme 的文本被识别为 URI`() {
        assertTrue(NdefPayloadUtils.isUriText("tel:+8613800138000".toByteArray()))
    }

    @Test
    fun `无冒号的文本不是 URI`() {
        assertFalse(NdefPayloadUtils.isUriText("example".toByteArray()))
    }

    @Test
    fun `scheme 为空不是 URI`() {
        assertFalse(NdefPayloadUtils.isUriText(":value".toByteArray()))
    }

    @Test
    fun `scheme 首字符非字母不是 URI`() {
        assertFalse(NdefPayloadUtils.isUriText("1abc:def".toByteArray()))
    }

    @Test
    fun `含非 ASCII 字节不是 URI`() {
        val bytes = "https://例え.jp".toByteArray(Charsets.UTF_8)
        assertFalse(NdefPayloadUtils.isUriText(bytes))
    }

    @Test
    fun `空数组不是 URI`() {
        assertFalse(NdefPayloadUtils.isUriText(ByteArray(0)))
    }

    // ---------- isProbablyText ----------

    @Test
    fun `普通 ASCII 文本被判定为文本`() {
        assertTrue(NdefPayloadUtils.isProbablyText("hello world".toByteArray()))
    }

    @Test
    fun `含 NUL 字节的二进制被判定为非文本`() {
        val bytes = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        assertFalse(NdefPayloadUtils.isProbablyText(bytes))
    }

    @Test
    fun `空数组不是文本`() {
        assertFalse(NdefPayloadUtils.isProbablyText(ByteArray(0)))
    }

    @Test
    fun `UTF-8 中文被判定为文本`() {
        assertTrue(NdefPayloadUtils.isProbablyText("你好，世界".toByteArray(Charsets.UTF_8)))
    }

    // ---------- toHexSpaced ----------

    @Test
    fun `十六进制格式化带空格分隔`() {
        assertEquals("41 42 43", NdefPayloadUtils.toHexSpaced(byteArrayOf(0x41, 0x42, 0x43)))
    }

    @Test
    fun `十六进制格式化保留前导零`() {
        assertEquals("0F FF", NdefPayloadUtils.toHexSpaced(byteArrayOf(0x0F, 0xFF.toByte())))
    }
}

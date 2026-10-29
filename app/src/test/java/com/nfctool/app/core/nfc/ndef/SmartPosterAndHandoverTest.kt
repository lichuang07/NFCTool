package com.nfctool.app.core.nfc.ndef

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smart Poster 与 Handover 的 payload 编解码测试。
 *
 * 这两个 codec 的 payload 都是纯字节结构，因此测试不依赖 Android 的 NdefRecord。
 *
 * Smart Poster 于 2026-10-25 按 NFC Forum 规范重写：
 *  - 记录类型是 "Sp"（早期误写 "S"）
 *  - payload 是**嵌套 NDEF 消息**（早期用自造的 action count 结构，任何手机都不认）
 */
class SmartPosterAndHandoverTest {

    // ---------------- Smart Poster ----------------

    @Test
    fun `Smart Poster payload 首条记录带 MB 标志`() {
        val payload = SmartPosterCodec.encodePayload(
            title = "官网",
            uris = listOf("https://example.com"),
            language = "zh",
        )
        // 嵌套消息首条记录的 flags：MB(0x80) + SR(0x10) + TNF_WELL_KNOWN(0x01)
        assertEquals(0x91, payload[0].toInt() and 0xFF)
        // type 长度 1，type 为 'T'
        assertEquals(1, payload[1].toInt() and 0xFF)
        assertEquals('T', payload[3].toInt().toChar())
    }

    @Test
    fun `Smart Poster payload 末条记录带 ME 标志`() {
        val payload = SmartPosterCodec.encodePayload(
            title = "官网",
            uris = listOf("https://example.com"),
            language = "zh",
        )
        // 第二条记录的偏移 = flags + typeLen + payloadLen + type + payloadLen
        val firstPayloadLen = payload[2].toInt() and 0xFF
        val second = 4 + firstPayloadLen
        // ME(0x40) + SR(0x10) + TNF_WELL_KNOWN(0x01)
        assertEquals(0x51, payload[second].toInt() and 0xFF)
        assertEquals('U', payload[second + 3].toInt().toChar())
    }

    @Test
    fun `Smart Poster 无标题时首条记录就是 U 记录`() {
        val payload = SmartPosterCodec.encodePayload(
            title = null,
            uris = listOf("https://example.com"),
        )
        // 只有一条记录：MB|ME|SR|TNF = 0x80|0x40|0x10|0x01
        assertEquals(0xD1, payload[0].toInt() and 0xFF)
        assertEquals('U', payload[3].toInt().toChar())
    }

    @Test
    fun `Smart Poster 编码后可解码还原标题与链接`() {
        val payload = SmartPosterCodec.encodePayload(
            title = "产品官网",
            uris = listOf("https://example.com", "https://example.com/help"),
        )
        val poster = SmartPosterCodec.decodePayload(payload)
        assertNotNull(poster)
        assertEquals("产品官网", poster!!.title)
        assertEquals("zh", poster.language)
        assertEquals(listOf("https://example.com", "https://example.com/help"), poster.uris)
    }

    @Test
    fun `Smart Poster 动作值可编解码`() {
        val payload = SmartPosterCodec.encodePayload(
            title = null,
            uris = listOf("https://example.com"),
            action = SmartPosterCodec.ACTION_SAVE_FOR_LATER,
        )
        val poster = SmartPosterCodec.decodePayload(payload)
        assertEquals(SmartPosterCodec.ACTION_SAVE_FOR_LATER, poster?.action)
    }

    @Test
    fun `Smart Poster 至少需要一个链接`() {
        assertTrue(runCatching { SmartPosterCodec.encodePayload(null, emptyList()) }.isFailure)
    }

    @Test
    fun `Smart Poster 非法 payload 返回 null`() {
        assertNull(SmartPosterCodec.decodePayload(ByteArray(0)))
        // 只有 flags 字节，没有 type 长度
        assertNull(SmartPosterCodec.decodePayload(byteArrayOf(0x91.toByte())))
    }

    @Test
    fun `Smart Poster 记录类型是 Sp 而不是 S`() {
        // 这是 2026-10-25 修复的核心：NFC Forum 规范的 RTD 是 "Sp"
        val type = SmartPosterCodec.TYPE_SMART_POSTER_NAME
        assertEquals("Sp", type)
    }

    // ---------------- Handover ----------------

    @Test
    fun `Handover payload 结构正确`() {
        val payload = HandoverCodec.encodePayload(
            listOf(
                HandoverCodec.CARRIER_URI to "https://example.com".toByteArray(),
                HandoverCodec.CARRIER_BLUETOOTH to byteArrayOf(1, 2, 3, 4, 5, 6),
            )
        )
        assertEquals(1, payload[0].toInt())   // version
        assertEquals(2, payload[1].toInt())   // count
        assertEquals(HandoverCodec.CARRIER_URI, payload[2].toInt())
        assertEquals(19, payload[3].toInt())  // URI 长度
        assertEquals(HandoverCodec.CARRIER_BLUETOOTH, payload[4 + 19].toInt())
    }

    @Test
    fun `Handover 编码后可解码还原载体`() {
        val payload = HandoverCodec.encodePayload(
            listOf(HandoverCodec.CARRIER_URI to "https://example.com".toByteArray())
        )
        val handover = HandoverCodec.decodePayload(payload)
        assertNotNull(handover)
        assertEquals(1, handover!!.version)
        assertEquals(1, handover.alternatives.size)
        assertEquals("URI", handover.alternatives[0].carrierName)
        assertEquals("https://example.com", String(handover.alternatives[0].data, Charsets.UTF_8))
    }

    @Test
    fun `Handover 至少需要一个载体`() {
        assertTrue(runCatching { HandoverCodec.encodePayload(emptyList()) }.isFailure)
    }

    @Test
    fun `Handover 非法 payload 返回 null`() {
        assertNull(HandoverCodec.decodePayload(ByteArray(0)))
        // 声明 1 个载体但没有后续数据
        assertNull(HandoverCodec.decodePayload(byteArrayOf(1, 1, 0x03)))
    }

    @Test
    fun `载体类型常量与 NFC Forum 规范一致`() {
        assertEquals(0x00, HandoverCodec.CARRIER_NFC_DEVICE)
        assertEquals(0x01, HandoverCodec.CARRIER_BLUETOOTH)
        assertEquals(0x02, HandoverCodec.CARRIER_WIFI)
        assertEquals(0x03, HandoverCodec.CARRIER_URI)
    }
}

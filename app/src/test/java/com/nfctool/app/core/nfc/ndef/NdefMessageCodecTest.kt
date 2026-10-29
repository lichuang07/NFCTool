package com.nfctool.app.core.nfc.ndef

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NDEF 消息层编解码测试。
 *
 * 复制标签依赖「消息 → 字节 → 消息」这条链路，一旦编码写错，
 * 写出来的标签在别的手机上就读不出内容 —— 所以这里用**手工构造的期望字节**
 * 做逐字节断言，而不只是往返一致。
 */
class NdefMessageCodecTest {

    private fun rec(
        tnf: Int,
        type: String,
        payload: ByteArray,
        id: ByteArray = ByteArray(0),
    ) = NdefMessageCodec.RawRecord(
        tnf = tnf,
        type = type.toByteArray(Charsets.US_ASCII),
        id = id,
        payload = payload,
    )

    // ---------- 逐字节布局 ----------

    @Test
    fun textRecord_bytesMatchSpec() {
        // 经典 NDEF Text 记录：TNF=1(Well-known), type="T", payload=[02 7A 68]
        val bytes = NdefMessageCodec.encodeMessage(
            listOf(rec(1, "T", byteArrayOf(0x02, 0x7A, 0x68)))
        )

        assertEquals(
            "D1 01 03 54 02 7A 68",
            bytes.joinToString(" ") { "%02X".format(it) },
        )
        // 分解含义：
        //  D1 = MB(0x80) | ME(0x40) | SR(0x10) | TNF(0x01)
        assertEquals(0xD1, bytes[0].toInt() and 0xFF)
        assertEquals(0x01, bytes[1].toInt()) // type length
        assertEquals(0x03, bytes[2].toInt()) // payload length（SR 时为 1 字节）
    }

    @Test
    fun longPayload_usesFourByteLength() {
        val payload = ByteArray(300) { (it % 251).toByte() }
        val bytes = NdefMessageCodec.encodeMessage(listOf(rec(2, "a/b", payload)))

        // SR 位必须清零（payload > 255）
        val header = bytes[0].toInt() and 0xFF
        assertEquals(0, header and 0x10)

        // header(1) + typeLen(1) + len(4) + type(3) + payload(300)
        assertEquals(1 + 1 + 4 + 3 + 300, bytes.size)

        val len = ((bytes[2].toInt() and 0xFF) shl 24) or
            ((bytes[3].toInt() and 0xFF) shl 16) or
            ((bytes[4].toInt() and 0xFF) shl 8) or
            (bytes[5].toInt() and 0xFF)
        assertEquals(300, len)
    }

    @Test
    fun idField_setsIlFlag() {
        val bytes = NdefMessageCodec.encodeMessage(
            listOf(rec(4, "x:y", byteArrayOf(1, 2), id = byteArrayOf(0x0A)))
        )
        val header = bytes[0].toInt() and 0xFF
        assertTrue("IL 位应置位", (header and 0x08) != 0)

        // header(1)+typeLen(1)+payloadLen(1)+idLen(1)+type(3)+id(1)+payload(2)
        assertEquals(10, bytes.size)
    }

    // ---------- 往返一致 ----------

    @Test
    fun multipleRecords_roundTrip() {
        val records = listOf(
            rec(1, "T", byteArrayOf(0x02, 0x7A, 0x68, 0x41)),
            rec(1, "U", byteArrayOf(0x04, 0x74, 0x65, 0x73, 0x74)),
            rec(2, "a/b", ByteArray(400) { 7 }),
        )
        val decoded = NdefMessageCodec.decodeMessage(
            NdefMessageCodec.encodeMessage(records)
        ).getOrThrow()

        assertEquals(records.size, decoded.size)
        records.forEachIndexed { i, expected ->
            assertEquals("第 $i 条记录 TNF", expected.tnf, decoded[i].tnf)
            assertTrue("第 $i 条记录 type", expected.type.contentEquals(decoded[i].type))
            assertTrue("第 $i 条记录 id", expected.id.contentEquals(decoded[i].id))
            assertTrue("第 $i 条记录 payload", expected.payload.contentEquals(decoded[i].payload))
        }
    }

    @Test
    fun emptyPayload_roundTrip() {
        val r = rec(5, "", ByteArray(0))
        val decoded = NdefMessageCodec.decodeMessage(
            NdefMessageCodec.encodeMessage(listOf(r))
        ).getOrThrow()
        assertEquals(1, decoded.size)
        assertEquals(0, decoded[0].payload.size)
    }

    @Test
    fun payloadAtShortRecordBoundary_usesShortForm() {
        // 255 字节仍属短记录（SR=1），256 起用长格式
        val short = NdefMessageCodec.encodeMessage(listOf(rec(1, "T", ByteArray(255))))
        assertEquals(0x10, short[0].toInt() and 0x10)

        val long = NdefMessageCodec.encodeMessage(listOf(rec(1, "T", ByteArray(256))))
        assertEquals(0, long[0].toInt() and 0x10)
    }

    // ---------- 中间记录不能带 MB / ME ----------

    @Test
    fun middleRecords_haveNoMbOrMe() {
        val bytes = NdefMessageCodec.encodeMessage(
            listOf(
                rec(1, "T", byteArrayOf(1)),
                rec(1, "T", byteArrayOf(2)),
                rec(1, "T", byteArrayOf(3)),
            )
        )
        // 记录格式固定：header(1)+typeLen(1)+payloadLen(1)+type(1)+payload(1) = 5 字节
        val first = bytes[0].toInt() and 0xFF
        val middle = bytes[5].toInt() and 0xFF
        val last = bytes[10].toInt() and 0xFF

        assertTrue("首条应有 MB", (first and 0x80) != 0)
        assertTrue("首条不应有 ME", (first and 0x40) == 0)
        assertTrue("中间不应有 MB", (middle and 0x80) == 0)
        assertTrue("中间不应有 ME", (middle and 0x40) == 0)
        assertTrue("末条不应有 MB", (last and 0x80) == 0)
        assertTrue("末条应有 ME", (last and 0x40) != 0)
    }

    // ---------- 非法输入 ----------

    @Test
    fun truncatedData_returnsFailure() {
        // 声明 10 字节 payload，实际没有
        val broken = byteArrayOf(0xD1.toByte(), 0x01, 0x0A, 0x54)
        val result = NdefMessageCodec.decodeMessage(broken)
        assertTrue("截断数据应返回 failure", result.isFailure)
    }

    // ---------- 与已知真实记录的字节对照 ----------

    @Test
    fun knownUriRecord_matchesExpectedBytes() {
        // TNF=1, type="U", payload=[0x00,'t','e','s','t']  —— URI 前缀 0x00 表示无缩写
        val bytes = NdefMessageCodec.encodeMessage(
            listOf(rec(1, "U", byteArrayOf(0x00, 0x74, 0x65, 0x73, 0x74)))
        )
        assertEquals(
            "D1 01 05 55 00 74 65 73 74",
            bytes.joinToString(" ") { "%02X".format(it) },
        )
    }

    @Test
    fun largeMessage_roundTrips() {
        val records = (0 until 12).map { i ->
            rec(2, "x/y", ByteArray(100 + i * 50) { (it % 97).toByte() })
        }
        val bytes = NdefMessageCodec.encodeMessage(records)
        val decoded = NdefMessageCodec.decodeMessage(bytes).getOrThrow()
        assertEquals(records.size, decoded.size)
        records.forEachIndexed { i, expected ->
            assertTrue("第 $i 条 payload", expected.payload.contentEquals(decoded[i].payload))
        }
    }
}

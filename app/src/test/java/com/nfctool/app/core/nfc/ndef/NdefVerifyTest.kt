package com.nfctool.app.core.nfc.ndef

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NdefVerify] 单元测试 —— 写入后回读校验的核心逻辑。
 */
class NdefVerifyTest {

    private fun rec(
        tnf: Int = 1,
        type: ByteArray = "T".toByteArray(Charsets.US_ASCII),
        id: ByteArray = ByteArray(0),
        payload: ByteArray = byteArrayOf(0x00),
    ) = NdefVerify.RecordBytes(tnf, type, id, payload)

    @Test
    fun `完全一致的两组记录判定为相同`() {
        val a = listOf(rec(), rec(tnf = 2, type = "text/vcard".toByteArray()))
        val b = listOf(rec(), rec(tnf = 2, type = "text/vcard".toByteArray()))
        assertTrue(NdefVerify.sameRecords(a, b))
        assertNull(NdefVerify.describeMismatch(a, b))
    }

    @Test
    fun `空记录列表视为一致`() {
        assertTrue(NdefVerify.sameRecords(emptyList(), emptyList()))
        assertNull(NdefVerify.describeMismatch(emptyList(), emptyList()))
    }

    @Test
    fun `记录条数不同判定为不一致`() {
        val a = listOf(rec())
        val b = listOf(rec(), rec())
        assertFalse(NdefVerify.sameRecords(a, b))
        val reason = NdefVerify.describeMismatch(a, b)
        assertNotNull(reason)
        assertTrue(reason!!.contains("记录条数不一致"))
    }

    @Test
    fun `TNF 不同判定为不一致并说明位置`() {
        val a = listOf(rec(tnf = 1))
        val b = listOf(rec(tnf = 2))
        assertFalse(NdefVerify.sameRecords(a, b))
        val reason = NdefVerify.describeMismatch(a, b)!!
        assertTrue(reason.contains("TNF 不一致"))
        assertTrue(reason.contains("第 1 条"))
    }

    @Test
    fun `type 字段不同判定为不一致`() {
        val a = listOf(rec(type = "T".toByteArray()))
        val b = listOf(rec(type = "U".toByteArray()))
        assertFalse(NdefVerify.sameRecords(a, b))
        assertTrue(NdefVerify.describeMismatch(a, b)!!.contains("type 字段不一致"))
    }

    @Test
    fun `ID 字段不同判定为不一致`() {
        val a = listOf(rec(id = byteArrayOf(0x01)))
        val b = listOf(rec(id = byteArrayOf(0x02)))
        assertFalse(NdefVerify.sameRecords(a, b))
        assertTrue(NdefVerify.describeMismatch(a, b)!!.contains("ID 字段不一致"))
    }

    @Test
    fun `载荷不同判定为不一致并给出字节数`() {
        val a = listOf(rec(payload = byteArrayOf(0x01, 0x02)))
        val b = listOf(rec(payload = byteArrayOf(0x01, 0x02, 0x03)))
        assertFalse(NdefVerify.sameRecords(a, b))
        val reason = NdefVerify.describeMismatch(a, b)!!
        assertTrue(reason.contains("载荷不一致"))
        assertTrue(reason.contains("3 字节"))
    }

    @Test
    fun `第二条记录不一致时能定位到第 2 条`() {
        val a = listOf(rec(), rec(payload = byteArrayOf(0x01)))
        val b = listOf(rec(), rec(payload = byteArrayOf(0x02)))
        val reason = NdefVerify.describeMismatch(a, b)!!
        assertTrue(reason.contains("第 2 条"))
    }

    @Test
    fun `RecordBytes 的 equals 与 hashCode 语义正确`() {
        val a = rec(payload = byteArrayOf(0x01, 0x02))
        val b = rec(payload = byteArrayOf(0x01, 0x02))
        val c = rec(payload = byteArrayOf(0x03))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == c)
    }
}

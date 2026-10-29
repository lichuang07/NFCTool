package com.nfctool.app.core.nfc.ndef

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 蓝牙 OOB 记录编解码测试。
 *
 * 字节布局依据 Bluetooth Core Specification 5.2.2.7：
 * BR/EDR = 2 字节小端长度 + 6 字节小端 BD_ADDR + EIR 结构；
 * LE = 纯 EIR 结构序列。
 */
class BluetoothOobCodecTest {

    @Test
    fun `BR_EDR payload 头部布局正确`() {
        val payload = BluetoothOobCodec.buildBrEdr("AA:BB:CC:DD:EE:FF")
        // 长度 = 8（自身 2 + 地址 6），小端
        assertEquals(8, payload[0].toInt())
        assertEquals(0, payload[1].toInt())
        // BD_ADDR 小端存放：FF EE DD CC BB AA
        val expected = byteArrayOf(
            0xFF.toByte(), 0xEE.toByte(), 0xDD.toByte(),
            0xCC.toByte(), 0xBB.toByte(), 0xAA.toByte(),
        )
        for (i in 0 until 6) {
            assertEquals(expected[i], payload[2 + i])
        }
    }

    @Test
    fun `BR_EDR 编码后可解码还原地址`() {
        val payload = BluetoothOobCodec.buildBrEdr("AA:BB:CC:DD:EE:FF")
        val oob = BluetoothOobCodec.parse(payload, isLe = false)
        assertNotNull(oob)
        assertEquals("AA:BB:CC:DD:EE:FF", oob!!.address)
        assertTrue(oob.adStructures.isEmpty())
    }

    @Test
    fun `BR_EDR 携带设备名`() {
        val payload = BluetoothOobCodec.buildBrEdr(
            "AA:BB:CC:DD:EE:FF",
            listOf(
                BluetoothOobCodec.AdStructure(
                    BluetoothOobCodec.AD_COMPLETE_LOCAL_NAME,
                    "WH-1000XM4".toByteArray(Charsets.UTF_8),
                )
            ),
        )
        val oob = BluetoothOobCodec.parse(payload, isLe = false)
        assertNotNull(oob)
        assertEquals("WH-1000XM4", oob!!.deviceName)
        assertEquals(1, oob.adStructures.size)
        // 长度应包含 AD 结构
        assertEquals(8 + 1 + 1 + 10, payload[0].toInt())
    }

    @Test
    fun `LE payload 无长度头`() {
        val payload = BluetoothOobCodec.buildLe("AA:BB:CC:DD:EE:FF")
        // AD 结构：Length(1) + Type(0x1B)(1) + 地址(6)
        // Length 字段表示「类型 + 数据」的字节数 = 7，不含 Length 自身
        assertEquals(7, payload[0].toInt())
        assertEquals(BluetoothOobCodec.AD_LE_DEVICE_ADDRESS, payload[1].toInt())
        // 整体占 1 + 7 = 8 字节
        assertEquals(8, payload.size)
    }

    @Test
    fun `LE 编码后可解码还原地址`() {
        val payload = BluetoothOobCodec.buildLe("AA:BB:CC:DD:EE:FF")
        val oob = BluetoothOobCodec.parse(payload, isLe = true)
        assertNotNull(oob)
        assertEquals("AA:BB:CC:DD:EE:FF", oob!!.address)
        assertTrue(oob.isLe)
    }

    @Test
    fun `LE 地址显示为还原后的顺序`() {
        val payload = BluetoothOobCodec.buildLe("01:02:03:04:05:06")
        val ad = BluetoothOobCodec.parseAdStructures(payload, 0)!!.first()
        assertEquals("01:02:03:04:05:06", BluetoothOobCodec.formatLeAddress(ad.data))
    }

    @Test
    fun `地址格式错误时构建失败`() {
        assertTrue(runCatching { BluetoothOobCodec.buildBrEdr("AA:BB:CC") }.isFailure)
        assertTrue(runCatching { BluetoothOobCodec.buildBrEdr("ZZ:BB:CC:DD:EE:FF") }.isFailure)
        assertTrue(runCatching { BluetoothOobCodec.buildLe("nonsense") }.isFailure)
    }

    @Test
    fun `非法 payload 返回 null`() {
        assertNull(BluetoothOobCodec.parse(ByteArray(0), isLe = false))
        assertNull(BluetoothOobCodec.parse(byteArrayOf(1, 2, 3), isLe = false))
        assertNull(BluetoothOobCodec.parse(ByteArray(0), isLe = true))
    }

    @Test
    fun `识别 OOB MIME 类型`() {
        assertTrue(BluetoothOobCodec.isOobRecord(BluetoothOobCodec.MIME_BR_EDR))
        assertTrue(BluetoothOobCodec.isOobRecord(BluetoothOobCodec.MIME_LE))
        assertTrue(BluetoothOobCodec.isOobRecord("APPLICATION/VND.BLUETOOTH.EP.OOB"))
        assertTrue(!BluetoothOobCodec.isOobRecord("text/plain"))
    }

    @Test
    fun `判断 LE 类型`() {
        assertTrue(BluetoothOobCodec.isLe(BluetoothOobCodec.MIME_LE))
        assertTrue(!BluetoothOobCodec.isLe(BluetoothOobCodec.MIME_BR_EDR))
    }

    @Test
    fun `携带 SSP 材料时能被识别`() {
        val payload = BluetoothOobCodec.buildBrEdr(
            "AA:BB:CC:DD:EE:FF",
            listOf(
                BluetoothOobCodec.AdStructure(
                    BluetoothOobCodec.AD_HASH_C_256,
                    ByteArray(16),
                )
            ),
        )
        val oob = BluetoothOobCodec.parse(payload, isLe = false)
        assertNotNull(oob)
        assertTrue(oob!!.hasSspMaterial)
    }

    @Test
    fun `地址解析支持紧凑格式`() {
        val a = BluetoothOobCodec.parseAddress("AABBCCDDEEFF")
        val b = BluetoothOobCodec.parseAddress("AA:BB:CC:DD:EE:FF")
        assertNotNull(a)
        assertNotNull(b)
        for (i in 0 until 6) {
            assertEquals(a!![i], b!![i])
        }
    }
}

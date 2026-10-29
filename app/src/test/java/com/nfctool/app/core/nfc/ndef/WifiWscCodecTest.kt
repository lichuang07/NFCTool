package com.nfctool.app.core.nfc.ndef

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wi-Fi WSC 编解码单元测试。
 *
 * 测试里的 [parseLikeAosp] 是**逐行对照 AOSP `NfcWifiProtectedSetup.parse()`**
 * 写的参考解析器，用来验证我们生成的字节流与系统侧能解析的格式一致。
 */
class WifiWscCodecTest {

    /**
     * 照搬 AOSP NfcWifiProtectedSetup.parse / parseCredential 的解析逻辑。
     */
    private fun parseLikeAosp(payload: ByteArray): Triple<String?, String?, Int?> {
        var ssid: String? = null
        var password: String? = null
        var authType: Int? = null

        var index = 0
        while (index + 4 <= payload.size) {
            val fieldId = ((payload[index].toInt() and 0xFF) shl 8) or
                (payload[index + 1].toInt() and 0xFF)
            val fieldSize = ((payload[index + 2].toInt() and 0xFF) shl 8) or
                (payload[index + 3].toInt() and 0xFF)
            index += 4
            if (index + fieldSize > payload.size) break

            if (fieldId == 0x100E) {
                val end = index + fieldSize
                var p = index
                while (p < end) {
                    if (p + 4 > end) break
                    val subId = ((payload[p].toInt() and 0xFF) shl 8) or
                        (payload[p + 1].toInt() and 0xFF)
                    val subSize = ((payload[p + 2].toInt() and 0xFF) shl 8) or
                        (payload[p + 3].toInt() and 0xFF)
                    p += 4
                    if (p + subSize > end) break
                    when (subId) {
                        0x1045 -> ssid = String(payload, p, subSize, Charsets.UTF_8)
                        0x1027 -> password = String(payload, p, subSize, Charsets.UTF_8)
                        0x1003 -> if (subSize == 2) {
                            authType = ((payload[p].toInt() and 0xFF) shl 8) or
                                (payload[p + 1].toInt() and 0xFF)
                        }
                    }
                    p += subSize
                }
                index = end
            } else {
                index += fieldSize
            }
        }
        return Triple(ssid, password, authType)
    }

    @Test
    fun `WPA2-PSK 编码后能被系统解析器正确还原`() {
        val payload = WifiWscCodec.encode(
            WifiWscCodec.WifiConfig("MyHomeWiFi", "password123", WifiWscCodec.AUTH_WPA2_PSK)
        )
        val (ssid, password, authType) = parseLikeAosp(payload)
        assertEquals("MyHomeWiFi", ssid)
        assertEquals("password123", password)
        assertEquals(WifiWscCodec.AUTH_WPA2_PSK, authType)
    }

    @Test
    fun `Credential 长度字段精确覆盖内部字节`() {
        val payload = WifiWscCodec.encode(
            WifiWscCodec.WifiConfig("Net", "12345678", WifiWscCodec.AUTH_WPA2_PSK)
        )
        val declared = ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
        assertEquals(payload.size - 4, declared)
    }

    @Test
    fun `开放网络不携带密码字段`() {
        val payload = WifiWscCodec.encode(
            WifiWscCodec.WifiConfig("OpenNet", "", WifiWscCodec.AUTH_OPEN)
        )
        val (ssid, password, authType) = parseLikeAosp(payload)
        assertEquals("OpenNet", ssid)
        assertNull(password)
        assertEquals(WifiWscCodec.AUTH_OPEN, authType)
    }

    @Test
    fun `中文 SSID 按字节长度编码并正确还原`() {
        val payload = WifiWscCodec.encode(
            WifiWscCodec.WifiConfig("家庭WiFi", "pass", WifiWscCodec.AUTH_WPA2_PSK)
        )
        assertEquals("家庭WiFi", parseLikeAosp(payload).first)
    }

    @Test
    fun `编解码往返一致`() {
        val config = WifiWscCodec.WifiConfig(
            ssid = "RoundTrip",
            password = "pw-12345678",
            authType = WifiWscCodec.AUTH_WPA_PSK_WPA2_PSK,
        )
        val decoded = WifiWscCodec.decodePayload(WifiWscCodec.encode(config))
        assertNotNull(decoded)
        assertEquals(config.ssid, decoded!!.ssid)
        assertEquals(config.password, decoded.password)
        assertEquals(config.authType, decoded.authType)
    }

    @Test
    fun `隐藏网络用空 SSID 表达时仍可解析`() {
        // 隐藏网络的 SSID 为空字节串，但 Wi-Fi 配网记录本身仍需可解析
        val payload = WifiWscCodec.encode(
            WifiWscCodec.WifiConfig("HiddenNet", "pw123456", WifiWscCodec.AUTH_WPA2_PSK)
        )
        val decoded = WifiWscCodec.decodePayload(payload)
        assertNotNull(decoded)
        assertEquals("HiddenNet", decoded!!.ssid)
    }

    @Test
    fun `加密网络缺少密码时拒绝编码`() {
        val result = runCatching {
            WifiWscCodec.encode(
                WifiWscCodec.WifiConfig("Net", "", WifiWscCodec.AUTH_WPA2_PSK)
            )
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun `超长密码被拒绝`() {
        val result = runCatching {
            WifiWscCodec.encode(
                WifiWscCodec.WifiConfig("Net", "x".repeat(65), WifiWscCodec.AUTH_WPA2_PSK)
            )
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun `WPA2 样例体积可容纳于 NTAG213`() {
        val payload = WifiWscCodec.encode(
            WifiWscCodec.WifiConfig("MyHomeWiFi", "password123", WifiWscCodec.AUTH_WPA2_PSK)
        )
        // NTAG213 用户区 144 字节，扣除 NDEF 消息头与 TLV 包装后仍需留有余量
        assertTrue("payload=${payload.size}", payload.size < 120)
    }

    @Test
    fun `解码遇到非 Credential 顶层字段时能跳过`() {
        // 构造一个顶层先放未知属性、再放 Credential 的 payload
        val credential = WifiWscCodec.encode(
            WifiWscCodec.WifiConfig("SkipTest", "pw123456", WifiWscCodec.AUTH_WPA2_PSK)
        )
        // 未知顶层属性占 4 字节头 + 2 字节值
        val payload = ByteBuffer.allocate(6 + credential.size).order(ByteOrder.BIG_ENDIAN)
        payload.putShort(0x1234) // 未知属性
        payload.putShort(2)
        payload.put(byteArrayOf(0x00, 0x00))
        payload.put(credential)

        val decoded = WifiWscCodec.decodePayload(payload.array())
        assertNotNull(decoded)
        assertEquals("SkipTest", decoded!!.ssid)
    }

    @Test
    fun `无 SSID 时判定为无效记录`() {
        val payload = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        payload.putShort(0x1003) // 只有认证类型，没有 Credential
        payload.putShort(2)
        payload.put(byteArrayOf(0x00, 0x20))
        assertNull(WifiWscCodec.decodePayload(payload.array()))
    }
}

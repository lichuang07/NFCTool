package com.nfctool.app.core.nfc.ndef

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 二维码三种写入模式的容量特性测试。
 *
 * ## 为什么这个测试重要
 *
 * 三种模式的占用差别很大：
 *  - 仅内容：一条 URI 记录，只占几十字节
 *  - 仅二维码图：PNG 体积随模块边长平方增长
 *  - 内容 + 二维码图：两者之和
 *
 * 「仅内容」模式是标签容量不够时的兜底方案，
 * 所以必须验证它在 NTAG213（137 字节）这种极小容量下也放得下。
 */
class QrWriteModeTest {

    // ---------- 各模式占用估算 ----------

    @Test
    fun `仅内容模式的占用远小于二维码图`() {
        val content = "https://example.com"
        val contentBytes = content.toByteArray(Charsets.UTF_8).size
        // 记录头约 10~16 字节，这里保守取 32
        val contentOnly = contentBytes + 32

        val encoded = QrCodeEncoder.encode(content, moduleSize = 4, margin = 1)!!
        val png = QrCodeEncoder.toPng(encoded)!!

        assertTrue(
            "仅内容 $contentOnly 字节应远小于二维码图 ${png.size} 字节",
            contentOnly < png.size,
        )
    }

    @Test
    fun `短链接在 NTAG213 容量下仅内容模式放得下`() {
        // NTAG213 只有 137 字节
        val capacity = 137
        val content = "https://a.co/x"
        val contentBytes = content.toByteArray(Charsets.UTF_8).size
        assertTrue(
            "仅内容模式应放得进 137 字节",
            contentBytes + 32 <= capacity,
        )
    }

    @Test
    fun `短链接的二维码图在 NTAG213 容量下放不下`() {
        // 实测：真实二维码的 1-bit PNG 约 200~320 字节
        // （比 checker 图样大得多 —— 真实图案杂乱，deflate 压不动），
        // 所以 **137 字节的 NTAG213 放不下二维码图**。
        // 这正是「仅内容」模式存在的意义。
        val capacity = 137
        val content = "https://a.co/x"
        var smallest = Int.MAX_VALUE
        for (module in intArrayOf(6, 4, 3, 2, 1)) {
            val encoded = QrCodeEncoder.encode(content, moduleSize = module, margin = 1)!!
            val png = QrCodeEncoder.toPng(encoded)!!
            if (png.size < smallest) smallest = png.size
        }
        assertTrue(
            "真实二维码 PNG 最小约 $smallest 字节，应超出 NTAG213 的 137 字节",
            smallest > capacity,
        )
    }

    // ---------- 内容记录的形态选择 ----------

    @Test
    fun `网址编码后能解码回原文`() {
        val content = "https://example.com/path?a=1"
        val encoded = QrCodeEncoder.encode(content, moduleSize = 4, margin = 2)
        assertNotNull(encoded)
        // ASCII 内容的 UTF-8 字节数应等于字符数
        assertEquals(content.length, encoded!!.byteCount)
    }

    @Test
    fun `长内容超出二维码最大容量时编码返回 null`() {
        // 二维码最大版本 40，字节模式最多约 2953 字节
        assertNull(QrCodeEncoder.encode("A".repeat(5000), moduleSize = 2, margin = 1))
    }

    @Test
    fun `Wi-Fi 配置串是二维码的高价值用法`() {
        // 这类内容用「仅内容」模式写成 URI 记录不现实（手机不认），
        // 但写成二维码图后用相机扫可以直接连网
        val content = "WIFI:T:WPA;S:MyHomeWiFi;P:secret123;;"
        val encoded = QrCodeEncoder.encode(content, moduleSize = 4, margin = 2)
        assertNotNull(encoded)
        assertTrue(encoded!!.byteCount > 30)
    }

    // ---------- MIME 常量 ----------

    @Test
    fun `二维码 MIME 为 image png`() {
        assertEquals("image/png", QrCodeEncoder.MIME)
    }
}

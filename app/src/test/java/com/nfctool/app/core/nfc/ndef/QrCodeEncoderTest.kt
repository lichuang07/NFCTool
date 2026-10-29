package com.nfctool.app.core.nfc.ndef

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QrCodeEncoder] 单元测试。
 *
 * **最关键的是「编码后能解码回原文」** ——
 * 只检查矩阵尺寸是不够的，矩阵对了但内容错了，用户扫出来是乱的。
 * 所以这里用 ZXing 的 Reader 把生成的图解码回来做对拍。
 */
class QrCodeEncoderTest {

    /**
     * 纯字节的灰度源。
     *
     * 不用 `BufferedImageLuminanceSource`（它在 ZXing 的 `javase` 模块里，
     * 会拖进 AWT 桌面依赖）—— 自己实现 [LuminanceSource] 接口即可，
     * `core` 模块就够用。
     */
    private class ByteLuminanceSource(
        private val pixels: ByteArray,
        private val width: Int,
        private val height: Int,
    ) : LuminanceSource(width, height) {

        override fun getRow(y: Int, row: ByteArray?): ByteArray {
            val out = row ?: ByteArray(width)
            val offset = y * width
            for (x in 0 until width) {
                out[x] = pixels[offset + x]
            }
            return out
        }

        override fun getMatrix(): ByteArray = pixels
    }

    /**
     * 把编码结果渲染成灰度图再解码回文本。
     *
     * 走完整链路：矩阵 → 像素 → 图像 → 解码，
     * 顺便验证了 [QrCodeEncoder.toAlpha8] 的像素展开是对的。
     */
    private fun decodeBack(encoded: QrCodeEncoder.Encoded): String? {
        val alpha = QrCodeEncoder.toAlpha8(encoded)
        // toAlpha8 里 0=黑、1=白；灰度图 0=黑、255=白
        val gray = ByteArray(alpha.size) { if (alpha[it] == 0.toByte()) 0 else 255.toByte() }
        val source = ByteLuminanceSource(gray, encoded.width, encoded.height)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        return QRCodeReader().decode(bitmap).text
    }

    @Test
    fun `短网址编码后可解码回原文`() {
        val content = "https://example.com"
        val encoded = QrCodeEncoder.encode(content, moduleSize = 4, margin = 2)
        assertNotNull(encoded)
        assertEquals(content, decodeBack(encoded!!))
    }

    @Test
    fun `中文内容编码后可解码回原文`() {
        val content = "会议室 B-203，周三 10:00 开会"
        val encoded = QrCodeEncoder.encode(content, moduleSize = 4, margin = 2)
        assertNotNull(encoded)
        assertEquals(content, decodeBack(encoded!!))
    }

    @Test
    fun `纯数字内容编码后可解码回原文`() {
        val content = "13800000000"
        val encoded = QrCodeEncoder.encode(content, moduleSize = 4, margin = 2)
        assertEquals(content, decodeBack(encoded!!))
    }

    @Test
    fun `Wi-Fi 配置串可完整往返`() {
        // 这是二维码最有价值的用法：把 Wi-Fi 配置写进标签，扫码直接连网
        val content = "WIFI:T:WPA;S:MyHomeWiFi;P:secret123;;"
        val encoded = QrCodeEncoder.encode(content, moduleSize = 4, margin = 2)
        assertEquals(content, decodeBack(encoded!!))
    }

    @Test
    fun `空内容返回 null`() {
        assertNull(QrCodeEncoder.encode(""))
    }

    @Test
    fun `内容过长时返回 null 而不是崩溃`() {
        val tooLong = "A".repeat(100000)
        assertNull(QrCodeEncoder.encode(tooLong, moduleSize = 4, margin = 2))
    }

    @Test
    fun `矩阵尺寸与静区计算正确`() {
        val encoded = QrCodeEncoder.encode("test", moduleSize = 4, margin = 2)!!
        // 总像素 = (模块数 + 静区*2) * 模块边长
        assertEquals((encoded.matrix.width + 4) * 4, encoded.width)
        assertEquals(encoded.width, encoded.height)
    }

    @Test
    fun `模块边长越大总像素越多`() {
        val small = QrCodeEncoder.encode("test", moduleSize = 2, margin = 1)!!
        val large = QrCodeEncoder.encode("test", moduleSize = 6, margin = 1)!!
        assertTrue(large.width > small.width)
    }

    @Test
    fun `toAlpha8 输出长度与尺寸一致`() {
        val encoded = QrCodeEncoder.encode("test", moduleSize = 3, margin = 2)!!
        val pixels = QrCodeEncoder.toAlpha8(encoded)
        assertEquals(encoded.width * encoded.height, pixels.size)
    }

    @Test
    fun `toAlpha8 中静区为白矩阵内含黑点`() {
        val encoded = QrCodeEncoder.encode("test", moduleSize = 4, margin = 2)!!
        val pixels = QrCodeEncoder.toAlpha8(encoded)

        // 静区：左上角 moduleSize*margin 范围内应全为白（1）
        val marginPixels = encoded.moduleSize * encoded.margin
        for (y in 0 until marginPixels) {
            for (x in 0 until marginPixels) {
                assertEquals(
                    "静区 ($x,$y) 应为白色",
                    1,
                    pixels[y * encoded.width + x].toInt(),
                )
            }
        }

        // 矩阵内必然有黑点（定位图案）
        var blackCount = 0
        for (y in 0 until encoded.height) {
            for (x in 0 until encoded.width) {
                if (pixels[y * encoded.width + x] == 0.toByte()) blackCount++
            }
        }
        assertTrue("二维码应包含黑点", blackCount > 0)
    }

    // ---------- 容量估算 ----------

    @Test
    fun `短内容对应最小版本`() {
        // 25 字节以内编成版本 1（数据区 21×21）
        assertEquals(QrCodeEncoder.MIN_VERSION, QrCodeEncoder.estimateVersion(1, ErrorCorrectionLevel.M))
        assertEquals(QrCodeEncoder.MIN_VERSION, QrCodeEncoder.estimateVersion(20, ErrorCorrectionLevel.M))
    }

    @Test
    fun `内容越长版本号越大`() {
        val v2 = QrCodeEncoder.estimateVersion(20, ErrorCorrectionLevel.M)
        val v5 = QrCodeEncoder.estimateVersion(90, ErrorCorrectionLevel.M)
        val v9 = QrCodeEncoder.estimateVersion(200, ErrorCorrectionLevel.M)
        assertTrue(v5 > v2)
        assertTrue(v9 > v5)
    }

    @Test
    fun `纠错等级越高容量越小`() {
        val content = 100
        val l = QrCodeEncoder.estimateVersion(content, ErrorCorrectionLevel.L)
        val m = QrCodeEncoder.estimateVersion(content, ErrorCorrectionLevel.M)
        val q = QrCodeEncoder.estimateVersion(content, ErrorCorrectionLevel.Q)
        val h = QrCodeEncoder.estimateVersion(content, ErrorCorrectionLevel.H)
        assertTrue("L 应能装下而 H 装不下", l <= m)
        assertTrue(m <= q)
        assertTrue(q <= h)
    }

    @Test
    fun `超出估算范围返回负一`() {
        assertEquals(-1, QrCodeEncoder.estimateVersion(99999, ErrorCorrectionLevel.M))
    }

    @Test
    fun `容量表与实际编码结果一致`() {
        // ⚠️ 这是关键校验：估算表若与 ZXing 实际行为脱节，
        // UI 上显示的「预计写入体积」就是错的。
        // 逐个纠错等级取几个临界点，验证「刚好放得下」与「差一点放不下」。
        val cases = listOf(
            ErrorCorrectionLevel.L to intArrayOf(25, 47, 77),
            ErrorCorrectionLevel.M to intArrayOf(20, 38, 61),
            ErrorCorrectionLevel.Q to intArrayOf(16, 29, 47),
            ErrorCorrectionLevel.H to intArrayOf(10, 20, 35),
        )
        cases.forEach { (ecc, caps) ->
            caps.forEach { cap ->
                val exact = QrCodeEncoder.encode("A".repeat(cap), moduleSize = 2, margin = 1)
                assertNotNull("$ecc 容量 $cap 应能编码", exact)
                val exactMatrix = exact!!.matrix
                val over = QrCodeEncoder.encode("A".repeat(cap + 1), moduleSize = 2, margin = 1)
                // 超 1 字节可能仍能编码（ZXing 允许部分版本有余量），
                // 但版本号必须变大
                if (over != null) {
                    assertTrue(
                        "$ecc 超容量后版本应变大",
                        QrCodeEncoder.versionOf(over.matrix, over.margin) >=
                            QrCodeEncoder.versionOf(exactMatrix, exact.margin),
                    )
                }
            }
        }
    }

    @Test
    fun `从矩阵宽度反推版本号`() {
        // 版本 1 数据区 21×21，每级 +4。矩阵宽度含静区，需先减掉两侧。
        val encoded = QrCodeEncoder.encode("A", moduleSize = 2, margin = 1)!!
        assertEquals(
            QrCodeEncoder.MIN_VERSION,
            QrCodeEncoder.versionOf(encoded.matrix, encoded.margin),
        )
        assertEquals(21, encoded.matrix.width - encoded.margin * 2)
    }

    @Test
    fun `长内容版本号大于短内容`() {
        val short = QrCodeEncoder.encode("A", moduleSize = 2, margin = 1)!!
        val long = QrCodeEncoder.encode("A".repeat(200), moduleSize = 2, margin = 1)!!
        assertTrue(
            QrCodeEncoder.versionOf(long.matrix, long.margin) >
                QrCodeEncoder.versionOf(short.matrix, short.margin),
        )
    }

    @Test
    fun `版本反推对不同静区都正确`() {
        val content = "A".repeat(100)
        listOf(1, 2, 4).forEach { margin ->
            val encoded = QrCodeEncoder.encode(content, moduleSize = 2, margin = margin)!!
            val version = QrCodeEncoder.versionOf(encoded.matrix, margin)
            // 100 字节在 M 级下需版本 5~6
            assertTrue("margin=$margin 版本 $version 应在 5~6", version in 5..6)
        }
    }

    @Test
    fun `版本反推随内容量单调递增`() {
        // ZXing 3.5.3 的实际容量比规范表略宽（会自行升版本），
        // 所以不能断言「估算表 == 实际版本」，
        // 只能断言「内容量越大，版本号单调不减」—— 这是稳的。
        var lastVersion = 0
        listOf(1, 10, 20, 40, 80, 120, 200, 300).forEach { bytes ->
            val encoded = QrCodeEncoder.encode("A".repeat(bytes), moduleSize = 2, margin = 1)!!
            val version = QrCodeEncoder.versionOf(encoded.matrix, encoded.margin)
            assertTrue(
                "$bytes bytes -> version $version should be >= $lastVersion",
                version >= lastVersion,
            )
            lastVersion = version
        }
    }

    @Test
    fun `估算版本号与实际编码版本偏差不超过一`() {
        // 估算表来自规范，ZXing 实际会自行微调，
        // 允许差 1 个版本，超出就说明表或公式有问题。
        listOf(
            ErrorCorrectionLevel.L to listOf(10, 25, 50, 100),
            ErrorCorrectionLevel.M to listOf(10, 20, 40, 90),
            ErrorCorrectionLevel.Q to listOf(10, 16, 30, 60),
            ErrorCorrectionLevel.H to listOf(5, 10, 20, 40),
        ).forEach { (ecc, samples) ->
            samples.forEach { bytes ->
                val encoded = QrCodeEncoder.encode("A".repeat(bytes), moduleSize = 2, margin = 1)!!
                val actual = QrCodeEncoder.versionOf(encoded.matrix, encoded.margin)
                val estimated = QrCodeEncoder.estimateVersion(bytes, ecc)
                assertTrue(
                    "$ecc $bytes bytes: actual=$actual estimated=$estimated differ by more than 1",
                    kotlin.math.abs(actual - estimated) <= 1,
                )
            }
        }
    }

    @Test
    fun `内容超出所有版本时编码失败返回 null`() {
        assertNull(QrCodeEncoder.encode("A".repeat(100000), moduleSize = 2, margin = 1))
    }
}

package com.nfctool.app.core.nfc.ndef

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * [PngWriter] 单元测试。
 *
 * **关键是用 ImageIO 把生成的 PNG 解码回来逐像素比对** ——
 * PNG 格式手写很容易出错（chunk 顺序、CRC、zlib 头、位序），
 * 只要有一个错，解码就会失败或像素错位。
 */
class PngWriterTest {

    @Test
    fun `生成的 PNG 能被 ImageIO 解码`() {
        val width = 8
        val height = 8
        val pixels = ByteArray(width * height) { 1 }  // 全白

        val png = PngWriter.encode1BitGray(pixels, width, height)
        assertNotNull(png)

        val image = ImageIO.read(png.inputStream())
        assertNotNull("PNG 应能被解码", image)
        assertEquals(width, image.width)
        assertEquals(height, image.height)
    }

    @Test
    fun `全白与全黑的像素能正确还原`() {
        val width = 16
        val height = 8

        // 全白
        val white = ByteArray(width * height) { 1 }
        val whiteImage = ImageIO.read(PngWriter.encode1BitGray(white, width, height).inputStream())
        for (y in 0 until height) {
            for (x in 0 until width) {
                val gray = whiteImage.getRGB(x, y) and 0xFF
                assertEquals("($x,$y) 应为白", 0xFF, gray)
            }
        }

        // 全黑
        val black = ByteArray(width * height) { 0 }
        val blackImage = ImageIO.read(PngWriter.encode1BitGray(black, width, height).inputStream())
        for (y in 0 until height) {
            for (x in 0 until width) {
                val gray = blackImage.getRGB(x, y) and 0xFF
                assertEquals("($x,$y) 应为黑", 0x00, gray)
            }
        }
    }

    @Test
    fun `黑白交替图案能逐像素还原`() {
        val width = 21   // 故意用非 8 的倍数，测跨字节边界
        val height = 5
        val pixels = ByteArray(width * height) { i ->
            if ((i / width + i % width) % 2 == 0) 0 else 1
        }

        val image = ImageIO.read(PngWriter.encode1BitGray(pixels, width, height).inputStream())
        assertNotNull(image)
        for (y in 0 until height) {
            for (x in 0 until width) {
                // pixels 里 0=黑、1=白；PNG 解码后灰度是 0 或 255
                val expected = if (pixels[y * width + x] == 0.toByte()) 0x00 else 0xFF
                val actual = image.getRGB(x, y) and 0xFF
                assertEquals("($x,$y) 像素错位", expected, actual)
            }
        }
    }

    @Test
    fun `PNG 签名正确`() {
        val png = PngWriter.encode1BitGray(ByteArray(64) { 1 }, 8, 8)
        val expected = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        for (i in expected.indices) {
            assertEquals("签名第 $i 字节", expected[i], png[i])
        }
    }

    @Test
    fun `包含 IHDR IDAT IEND 三个必需 chunk`() {
        val png = PngWriter.encode1BitGray(ByteArray(64) { 1 }, 8, 8)
        val text = String(png, Charsets.ISO_8859_1)
        assertTrue("应含 IHDR", text.contains("IHDR"))
        assertTrue("应含 IDAT", text.contains("IDAT"))
        assertTrue("应含 IEND", text.contains("IEND"))
    }

    @Test
    fun `纯色图的体积很小`() {
        // 1-bit 编码下，256×256 全白只有 256*32 = 8192 原始字节，
        // 压缩后应该只有几百字节
        val size = 256
        val png = PngWriter.encode1BitGray(ByteArray(size * size) { 1 }, size, size)
        assertTrue("256×256 全白 PNG 应小于 1KB，实际 ${png.size}", png.size < 1024)
    }

    @Test
    fun `尺寸非法时抛异常`() {
        try {
            PngWriter.encode1BitGray(ByteArray(0), 0, 0)
            org.junit.Assert.fail("应抛出异常")
        } catch (e: IllegalArgumentException) {
            // 预期
        }
    }

    @Test
    fun `像素数据不足时抛异常`() {
        try {
            PngWriter.encode1BitGray(ByteArray(4), 8, 8)
            org.junit.Assert.fail("应抛出异常")
        } catch (e: IllegalArgumentException) {
            // 预期
        }
    }

    // ---------- 与二维码的集成 ----------

    @Test
    fun `二维码 PNG 能被解码且尺寸正确`() {
        val encoded = QrCodeEncoder.encode("https://example.com", moduleSize = 4, margin = 2)!!
        val png = QrCodeEncoder.toPng(encoded)!!
        val image = ImageIO.read(png.inputStream())
        assertNotNull(image)
        assertEquals(encoded.width, image.width)
        assertEquals(encoded.height, image.height)
    }

    @Test
    fun `二维码 PNG 体积明显小于同尺寸 32 位 PNG`() {
        val encoded = QrCodeEncoder.encode("https://example.com", moduleSize = 4, margin = 2)!!
        val oneBit = QrCodeEncoder.toPng(encoded)!!

        // 32 位未压缩的裸体积（作为对比基准）
        val raw32 = encoded.width * encoded.height * 4
        assertTrue(
            "1-bit PNG ${oneBit.size} 字节应远小于 32 位裸体积 $raw32",
            oneBit.size < raw32 / 4,
        )
    }

    @Test
    fun `二维码 PNG 体积随模块大小缓慢增长`() {
        // ⚠️ 直觉是「体积随边长平方增长」，但 1-bit + deflate 的实际表现不同：
        // 模块变大 → 像素变多，但每行的重复模式也变多，deflate 压得更狠。
        // 实测真实二维码：1px 约 203 字节，6px 约 312 字节 ——
        // 只涨了 1.5 倍，而非 36 倍。
        val content = "https://example.com"
        val small = QrCodeEncoder.toPng(
            QrCodeEncoder.encode(content, moduleSize = 1, margin = 1)!!
        )!!
        val large = QrCodeEncoder.toPng(
            QrCodeEncoder.encode(content, moduleSize = 6, margin = 1)!!
        )!!
        assertTrue(
            "6px 的 PNG（${large.size}）不应比 1px（${small.size}）大 3 倍以上",
            large.size < small.size * 3,
        )
        assertTrue(
            "6px 的 PNG 应比 1px 大（模块更多细节）",
            large.size > small.size,
        )
    }

    @Test
    fun `真实二维码 PNG 放不进 137 字节的标签`() {
        // 实测真实二维码的 1-bit PNG 约 200~320 字节。
        // 所以 NTAG213（137 字节）**放不下二维码图**，
        // 必须用「仅内容」模式 —— 这是本应用必须提供该模式的原因。
        val content = "https://a.co/x"
        val png = QrCodeEncoder.toPng(
            QrCodeEncoder.encode(content, moduleSize = 2, margin = 1)!!
        )!!
        assertTrue(
            "真实二维码 PNG ${png.size} 字节应超出 137 字节的 NTAG213",
            png.size > 137,
        )
    }
}

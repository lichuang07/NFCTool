package com.nfctool.app.core.nfc.ndef

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * 纯 Java 的 **1-bit 灰度 PNG** 编码器。
 *
 * ## 为什么不用 Android 的 Bitmap.compress
 *
 * 1. **体积**：二维码只有黑白两色。
 *    - `Bitmap.Config.ALPHA_8` 压缩出来是 8 位/像素
 *    - 本编码器输出 1 位/像素 —— **理论上小 8 倍**
 *    标签容量就那么点，这个差距是决定性的。
 *
 * 2. **可测试**：Android 的 `Bitmap` 在 JUnit 里不可用，
 *    而 PNG 体积恰恰是这个功能最需要验证的东西。
 *    纯 Java 实现可以直接写单测（用 ImageIO 解码回来比对）。
 *
 * 3. **不分配位图**：直接按行输出字节，省掉整张图的内存开销。
 *
 * ## PNG 格式要点（1-bit 灰度，color type 0）
 *
 * ```
 * 89 50 4E 47 0D 0A 1A 0A     签名
 * IHDR  width height bitDepth=1 colorType=0 ...
 * IDAT  zlib 压缩的扫描线数据
 * IEND
 * ```
 *
 * 扫描线每行前置一个 filter 字节（0 = None），
 * 每行字节数 = ceil(width / 8)，**高位在前**。
 * 灰度模式下采样值 0 = 黑、1 = 白。
 */
object PngWriter {

    /** PNG 文件签名 */
    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    /**
     * 把 1-bit 像素编码成 PNG。
     *
     * @param pixels  每像素 1 字节，0 = 黑、1 = 白
     * @param width   宽（像素）
     * @param height  高（像素）
     * @return PNG 字节
     */
    fun encode1BitGray(pixels: ByteArray, width: Int, height: Int): ByteArray {
        require(width > 0 && height > 0) { "尺寸非法" }
        require(pixels.size >= width * height) { "像素数据长度不足" }

        val raw = ByteArrayOutputStream()
        val bytesPerRow = (width + 7) / 8
        val row = ByteArray(bytesPerRow)
        for (y in 0 until height) {
            // 每行前置 filter 字节 0（None）
            raw.write(0)
            java.util.Arrays.fill(row, 0)
            for (x in 0 until width) {
                // ⚠️ PNG 1-bit 灰度的位序：位值 0 = 黑、1 = 白。
                // 本类的 pixels 约定同样是 0 = 黑、1 = 白，
                // 所以**白色像素要把该位设为 1**（不是 0）。
                val isWhite = pixels[y * width + x] != 0.toByte()
                if (isWhite) {
                    // 位序：高位在前，第 x 像素落在第 x%8 位
                    row[x / 8] = (row[x / 8].toInt() or (0x80 ushr (x % 8))).toByte()
                }
            }
            raw.write(row)
        }

        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)

        // IHDR
        val ihdr = ByteArrayOutputStream()
        writeInt(ihdr, width)
        writeInt(ihdr, height)
        ihdr.write(1)   // bit depth = 1
        ihdr.write(0)   // color type = 0（grayscale）
        ihdr.write(0)   // compression = deflate
        ihdr.write(0)   // filter method = 0
        ihdr.write(0)   // interlace = none
        writeChunk(out, "IHDR", ihdr.toByteArray())

        // IDAT
        val compressed = zlibCompress(raw.toByteArray())
        writeChunk(out, "IDAT", compressed)

        // IEND
        writeChunk(out, "IEND", ByteArray(0))

        return out.toByteArray()
    }

    /**
     * 写一个 PNG chunk：长度 + 类型 + 数据 + CRC32。
     */
    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        writeInt(out, data.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(data)

        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        writeInt(out, crc.value.toInt())
    }

    /**
     * 写 4 字节大端整数。
     */
    private fun writeInt(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    /**
     * zlib 封装（RFC 1950）：2 字节头 + deflate 数据 + 4 字节 Adler-32。
     *
     * ⚠️ `Deflater(level, nowrap = false)` **本身就输出完整的 zlib 流**
     * （含 0x78 头与 Adler-32 校验）。
     * 千万别再手动加头 —— 实测那样会让数据流损坏，
     * 解压时报 `invalid stored block lengths`。
     *
     * @param data 待压缩的原始数据
     * @return zlib 格式的压缩数据
     */
    private fun zlibCompress(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, false)
        deflater.setInput(data)
        deflater.finish()

        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            if (count <= 0) break
            out.write(buffer, 0, count)
        }
        deflater.end()
        return out.toByteArray()
    }
}

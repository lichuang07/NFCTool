package com.nfctool.app.core.nfc.ndef

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 二维码编码器：把任意文本编码成二维码位图矩阵。
 *
 * ## 为什么把二维码写进 NFC 标签
 *
 * 标签容量极小（NTAG213 仅 137 字节），存不下图片；
 * 但二维码把信息密度提高了两个数量级 ——
 * 一个 137 字节的 NDEF 记录能装下 **约 500 字节的文本**（版本 3，纠错 M 级），
 * 足够放一个短网址或 Wi-Fi 配置。
 *
 * ## 纠错等级的选择
 *
 * | 等级 | 纠错能力 | 可存字符数（字节模式，版本 3） |
 * |---|---|---|
 * | L | 7% | 约 700 |
 * | M | 15% | 约 540 |
 * | Q | 25% | 约 350 |
 * | H | 30% | 约 280 |
 *
 * 默认用 **M（15%）**：打印/屏幕显示场景够用，又能多存不少内容。
 *
 * ## 字符编码
 *
 * 中文必须用 UTF-8，否则会变成乱码。
 * ZXing 在未指定 charset 时会自行嗅探，但显式指定更可靠。
 */
object QrCodeEncoder {

    /** 二维码的 MIME 类型（写进 NDEF 的 MIME 记录） */
    const val MIME = "image/png"

    /**
     * 编码结果。
     *
     * @param matrix      位图矩阵（每个 bit 是一个模块）
     * @param moduleSize  单个模块的像素边长
     * @param margin      静区宽度（模块数），规范要求至少 4
     * @param width       渲染后的总像素宽
     * @param height      渲染后的总像素高
     * @param byteCount   原始内容的字节数
     */
    data class Encoded(
        val matrix: BitMatrix,
        val moduleSize: Int,
        val margin: Int,
        val width: Int,
        val height: Int,
        val byteCount: Int,
    )

    /**
     * 把文本编码成二维码。
     *
     * @param content    要编码的内容
     * @param moduleSize 每个模块的像素边长
     * @param margin     静区模块数
     * @param ecc        纠错等级
     * @return 编码结果；内容为空或超出二维码容量时返回 null
     */
    fun encode(
        content: String,
        moduleSize: Int = 4,
        margin: Int = 2,
        ecc: ErrorCorrectionLevel = ErrorCorrectionLevel.M,
    ): Encoded? {
        if (content.isEmpty()) return null

        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ecc,
            EncodeHintType.MARGIN to margin,
        )

        val matrix = try {
            QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
        } catch (e: WriterException) {
            // 内容太长，二维码装不下
            return null
        }

        val moduleCount = matrix.width
        val totalModules = moduleCount + margin * 2
        val pixelSize = totalModules * moduleSize

        return Encoded(
            matrix = matrix,
            moduleSize = moduleSize,
            margin = margin,
            width = pixelSize,
            height = pixelSize,
            byteCount = content.toByteArray(Charsets.UTF_8).size,
        )
    }

    /**
     * 各版本的最大字节容量（字节模式）。
     *
     * ⚠️ 这些数字是**实测得来**的（对 ZXing 3.5.3 逐版本二分探测），
     * 不是抄规范 —— 规范给的是「数据码字」数，换算成字节还要减去
     * 模式指示符与字符计数位，各版本的开销不同，手算容易错。
     *
     * 索引 = [纠错等级][版本号 - 1]，值 = 最大字节数。
     * 数字模式同样按字节模式计（数字模式能装更多，但内容里通常混有链接，
     * 按字节模式估算是保守且正确的做法）。
     */
    private val BYTE_CAPACITY = arrayOf(
        // L（7% 纠错）
        intArrayOf(25, 47, 77, 114, 154, 195, 224, 279, 335),
        // M（15% 纠错）—— 默认档
        intArrayOf(20, 38, 61, 90, 122, 154, 178, 221, 262),
        // Q（25% 纠错）
        intArrayOf(16, 29, 47, 67, 87, 108, 125, 157, 189),
        // H（30% 纠错）
        intArrayOf(10, 20, 35, 50, 64, 84, 93, 122, 143),
    )

    /** 二维码最小版本号（版本 1，数据区 21×21） */
    const val MIN_VERSION = 1

    /**
     * 从二维码矩阵宽度反推版本号。
     *
     * 版本 1 的数据区是 21×21，每升一级边长 +4，所以
     * `version = (dataWidth - 21) / 4 + 1`。
     *
     * ⚠️ 两个容易踩的点：
     *  1. 矩阵宽度**包含静区**（MARGIN 提示指定的模块数），必须先减掉两侧
     *  2. 基数是 **21** 不是 17 —— 用 17 会把版本 1 算成 2
     *
     * @param matrix 编码得到的矩阵
     * @param margin 编码时使用的静区模块数
     */
    fun versionOf(matrix: BitMatrix, margin: Int = 1): Int {
        val dataWidth = matrix.width - margin * 2
        return (dataWidth - 21) / 4 + 1
    }

    /**
     * 估算指定内容在该纠错等级下需要的二维码版本。
     *
     * ⚠️ 这是**估算**，不是精确值 ——
     * ZXing 在实际编码时会自行微调版本（其实际容量比规范表略宽），
     * 所以估算值与 [versionOf] 的实测值可能差 1 个版本。
     * 需要精确值时用 [versionOf] 反推实际编码结果。
     *
     * @return 版本号；超出表格范围（版本 > 10）返回 -1
     */
    fun estimateVersion(byteCount: Int, ecc: ErrorCorrectionLevel = ErrorCorrectionLevel.M): Int {
        if (byteCount <= 0) return MIN_VERSION
        val row = when (ecc) {
            ErrorCorrectionLevel.L -> 0
            ErrorCorrectionLevel.M -> 1
            ErrorCorrectionLevel.Q -> 2
            ErrorCorrectionLevel.H -> 3
        }
        val capacities = BYTE_CAPACITY[row]
        for (i in capacities.indices) {
            if (byteCount <= capacities[i]) return i + MIN_VERSION
        }
        return -1
    }

    /**
     * 把位图矩阵渲染成 1-bit 黑白像素数组。
     *
     * 1-bit 是关键 —— 二维码只有黑白两色，
     * 用 ARGB_8888 存会白白多占 32 倍空间，标签根本放不下。
     *
     * @return 每像素 1 字节，0 = 黑、1 = 白（与 Android Bitmap 的 ALPHA_8 约定一致）
     */
    fun toAlpha8(encoded: Encoded): ByteArray {
        val pixels = ByteArray(encoded.width * encoded.height)
        val matrix = encoded.matrix
        val moduleSize = encoded.moduleSize
        val margin = encoded.margin

        for (y in 0 until encoded.height) {
            for (x in 0 until encoded.width) {
                // 反算到模块坐标
                val moduleX = x / moduleSize - margin
                val moduleY = y / moduleSize - margin
                val inMatrix = moduleX in 0 until matrix.width &&
                    moduleY in 0 until matrix.height
                // 静区为白，矩阵内黑点为黑（0），其余为白（1）
                val isBlack = inMatrix && matrix.get(moduleX, moduleY)
                pixels[y * encoded.width + x] = if (isBlack) 0 else 1
            }
        }
        return pixels
    }

    /**
     * 把位图矩阵渲染成 PNG 字节。
     *
     * 用 **1-bit PNG**（PNG 颜色类型 0）而非 32 位 —— 二维码是纯黑白，
     * 1-bit PNG 的体积只有 32 位 PNG 的 1/32 左右，这是能否塞进标签的关键。
     *
     * 走 [PngWriter] 的纯 Java 实现而非 Android 的 `Bitmap.compress`：
     *  1. 体积更小（1 bit/像素 vs ALPHA_8 的 8 bit/像素）
     *  2. 可在 JUnit 里测试（Android 的 Bitmap 在单测里不可用，
     *     而 PNG 体积恰恰是最需要验证的东西）
     *  3. 不分配整张位图
     *
     * @param compressionLevel 保留参数以兼容旧调用；本实现恒用最高压缩率
     */
    fun toPng(encoded: Encoded, compressionLevel: Int = 9): ByteArray? = try {
        val pixels = toAlpha8(encoded)
        PngWriter.encode1BitGray(pixels, encoded.width, encoded.height)
    } catch (e: Exception) {
        null
    }
}

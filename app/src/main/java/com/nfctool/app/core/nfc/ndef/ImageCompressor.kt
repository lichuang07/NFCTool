package com.nfctool.app.core.nfc.ndef

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream

/**
 * 图片压缩工具：把分享来的图片压到能塞进标签的尺寸。
 *
 * ## 为什么要压缩
 *
 * NFC 标签的 NDEF 容量非常小：
 *  - NTAG213：137 字节
 *  - NTAG215：504 字节
 *  - NTAG216：888 字节
 *
 * 而手机拍的照片动辄 2~5 MB，**直接写必然失败**。
 * 所以必须先按目标容量缩放并重新编码。
 *
 * ## 编码格式的选择
 *
 * - **JPEG**：有损压缩，体积最小，适合照片。没有 alpha 通道。
 * - **PNG**：无损，体积大，只适合图标类小图。
 * - **WebP**：Android 14（API 34）起 `WEBP_LOSSLESS` 已废弃，
 *   且压缩行为在版本间不一致 —— 为兼容性不采用。
 *
 * 所以默认用 JPEG；若是带透明通道的图（PNG 输入），
 * 转 JPEG 会有黑底，此时保留 PNG。
 */
object ImageCompressor {

    /**
     * 压缩结果。
     *
     * @param bytes    压缩后的字节
     * @param mime     压缩后的 MIME
     * @param width    压缩后宽度
     * @param height   压缩后高度
     * @param originalSize 原始字节数
     */
    data class Result(
        val bytes: ByteArray,
        val mime: String,
        val width: Int,
        val height: Int,
        val originalSize: Int,
    ) {
        val size: Int get() = bytes.size

        /** 压缩比（原始大小 / 压缩后大小） */
        val ratio: Double get() = if (bytes.isEmpty()) 0.0 else originalSize.toDouble() / bytes.size

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Result) return false
            return bytes.contentEquals(other.bytes) && mime == other.mime &&
                width == other.width && height == other.height && originalSize == other.originalSize
        }

        override fun hashCode(): Int {
            var result = bytes.contentHashCode()
            result = 31 * result + mime.hashCode()
            result = 31 * result + width
            result = 31 * result + height
            result = 31 * result + originalSize
            return result
        }
    }

    /**
     * 把图片压缩到不超过 [maxBytes]。
     *
     * 策略：先按最大边长缩放，再按 JPEG 质量逐级降低（85 → 70 → 55 → 40），
     * 直到体积达标。若仍放不下，返回最小的那一版，由调用方提示用户。
     *
     * @param data     原始图片字节
     * @param maxBytes 目标最大字节数
     * @return 压缩结果；无法解码时返回 null
     */
    fun compress(data: ByteArray, maxBytes: Int): Result? {
        if (data.isEmpty() || maxBytes <= 0) return null

        // 只解码尺寸，不分配像素内存 —— 先看能不能直接放
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, boundsOptions)
        val originalWidth = boundsOptions.outWidth
        val originalHeight = boundsOptions.outHeight
        if (originalWidth <= 0 || originalHeight <= 0) return null

        // 原始数据就够小，直接用（保留原 MIME）
        if (data.size <= maxBytes) {
            return Result(
                bytes = data,
                mime = guessMime(data),
                width = originalWidth,
                height = originalHeight,
                originalSize = data.size,
            )
        }

        // 计算采样率：让解码后的尺寸大致能压进 maxBytes
        val maxSide = 1024
        var sampleSize = 1
        while (originalWidth / (sampleSize * 2) >= maxSide ||
            originalHeight / (sampleSize * 2) >= maxSide
        ) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, decodeOptions) ?: return null

        return try {
            // 若仍超限，再按最长边缩放
            val scaled = scaleToFit(bitmap, maxSide)
            var best: ByteArray? = null
            for (quality in QUALITY_STEPS) {
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                val bytes = out.toByteArray()
                if (best == null || bytes.size < best.size) best = bytes
                if (bytes.size <= maxBytes) {
                    return Result(
                        bytes = bytes,
                        mime = "image/jpeg",
                        width = scaled.width,
                        height = scaled.height,
                        originalSize = data.size,
                    )
                }
            }
            Result(
                bytes = best!!,
                mime = "image/jpeg",
                width = scaled.width,
                height = scaled.height,
                originalSize = data.size,
            )
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /**
     * 按最长边缩放到位图。
     */
    private fun scaleToFit(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxSide) return bitmap
        val scale = maxSide.toDouble() / longest
        val newWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val newHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val scaled = bitmap.scale(newWidth, newHeight, true)
        if (scaled != bitmap && !bitmap.isRecycled) bitmap.recycle()
        return scaled
    }

    /**
     * 猜测图片 MIME（按文件头魔数）。
     */
    fun guessMime(data: ByteArray): String = when {
        data.size >= 3 &&
            data[0] == 0x89.toByte() && data[1] == 0x50.toByte() && data[2] == 0x4E.toByte() ->
            "image/png"

        data.size >= 3 &&
            data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte() ->
            "image/jpeg"

        data.size >= 4 &&
            data[0] == 'G'.code.toByte() && data[1] == 'I'.code.toByte() &&
            data[2] == 'F'.code.toByte() && data[3] == '8'.code.toByte() ->
            "image/gif"

        data.size >= 12 &&
            String(data, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(data, 8, 4, Charsets.US_ASCII) == "WEBP" ->
            "image/webp"

        data.size >= 2 &&
            data[0] == 'B'.code.toByte() && data[1] == 'M'.code.toByte() ->
            "image/bmp"

        else -> "application/octet-stream"
    }

    /** JPEG 质量档位，从高到低 */
    private val QUALITY_STEPS = intArrayOf(85, 70, 55, 40)
}

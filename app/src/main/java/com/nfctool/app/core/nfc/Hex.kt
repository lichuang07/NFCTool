package com.nfctool.app.core.nfc
import com.nfctool.app.R
import com.nfctool.app.core.i18n.AppStrings

/**
 * 十六进制与字节数组互转工具。
 */
object Hex {
    private const val DIGITS = "0123456789ABCDEF"

    fun toHex(bytes: ByteArray, separator: String = " "): String =
        bytes.joinToString(separator) { b ->
            val v = b.toInt() and 0xFF
            "${DIGITS[v shr 4]}${DIGITS[v and 0x0F]}"
        }

    fun toHexNoSep(bytes: ByteArray): String = toHex(bytes, "")

    /** 解析十六进制字符串，支持空格/冒号/换行分隔与 0x 前缀 */
    fun parse(input: String): ByteArray {
        val cleaned = input.replace("0x", "", ignoreCase = true)
            .replace(":", " ")
            .replace(",", " ")
            .replace("-", " ")
            .filter { !it.isWhitespace() }
        require(cleaned.length % 2 == 0) {
            AppStrings.get(R.string.err_hex_even, cleaned.length)
        }
        val out = ByteArray(cleaned.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(cleaned[i * 2], 16)
            val lo = Character.digit(cleaned[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) {
                AppStrings.get(R.string.err_hex_invalid, cleaned.substring(i * 2, i * 2 + 2))
            }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /** 宽松解析：忽略非法字符，奇数位补 0 */
    fun parseLenient(input: String): ByteArray {
        val cleaned = input.replace("0x", "", ignoreCase = true)
            .filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        if (cleaned.isEmpty()) return ByteArray(0)
        val padded = if (cleaned.length % 2 == 1) cleaned + "0" else cleaned
        return parse(padded)
    }

    fun isValid(input: String): Boolean = runCatching { parse(input) }.isSuccess

    /** 带偏移与 ASCII 预览的经典 hexdump 分行 */
    fun dump(bytes: ByteArray, bytesPerLine: Int = 16): List<String> =
        bytes.toList().chunked(bytesPerLine).map { chunk ->
            val hex = chunk.joinToString(" ") { b ->
                val v = b.toInt() and 0xFF
                "${DIGITS[v shr 4]}${DIGITS[v and 0x0F]}"
            }
            val ascii = chunk.joinToString("") { b ->
                val c = b.toInt() and 0xFF
                if (c in 0x20..0x7E) c.toChar().toString() else "."
            }
            hex.padEnd(bytesPerLine * 3 - 1, ' ') + "  |" + ascii + "|"
        }

    fun formatUid(uid: ByteArray): String =
        uid.joinToString(":") { b ->
            val v = b.toInt() and 0xFF
            "${DIGITS[v shr 4]}${DIGITS[v and 0x0F]}"
        }

    /** 逆序 UID（部分场景用于比对） */
    fun reversed(uid: ByteArray): ByteArray = uid.reversedArray()
}

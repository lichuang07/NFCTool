package com.nfctool.app.core.nfc.ndef

/**
 * NDEF 写入回读校验。
 *
 * ## 为什么需要回读
 *
 * Android 的 `Ndef.writeNdefMessage()` 在**标签已离开感应范围**时，
 * 部分机型不会抛异常，却实际什么都没写进去。
 * 若只依据「方法没抛异常」就报成功，用户会看到「写入成功」，
 * 但标签里根本没有数据 —— 这是最容易被忽略也最伤人的一种假成功。
 *
 * 所以正确做法是：**写完立刻读回来逐字节比对**。
 *
 * ## 纯字节化
 *
 * Android 的 `NdefRecord` 在 JUnit 里不可用，
 * 因此比对逻辑基于「TNF + type + id + payload」四元组的纯字节表示，
 * 可以直接写单元测试。
 */
object NdefVerify {

    /**
     * 一条记录的纯字节表示，用于比对。
     */
    data class RecordBytes(
        val tnf: Int,
        val type: ByteArray,
        val id: ByteArray,
        val payload: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RecordBytes) return false
            return tnf == other.tnf &&
                type.contentEquals(other.type) &&
                id.contentEquals(other.id) &&
                payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = tnf
            result = 31 * result + type.contentHashCode()
            result = 31 * result + id.contentHashCode()
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    /**
     * 判断两条记录列表是否完全一致。
     *
     * @return true 表示标签上的内容与期望写入的完全一致
     */
    fun sameRecords(actual: List<RecordBytes>, expected: List<RecordBytes>): Boolean {
        if (actual.size != expected.size) return false
        return actual.indices.all { actual[it] == expected[it] }
    }

    /**
     * 生成不一致的可读原因，用于给用户明确反馈。
     *
     * @return null 表示内容一致；否则返回原因描述
     */
    fun describeMismatch(
        actual: List<RecordBytes>,
        expected: List<RecordBytes>,
    ): String? {
        if (actual.size != expected.size) {
            return "记录条数不一致：期望 ${expected.size} 条，实际 ${actual.size} 条"
        }
        for (i in actual.indices) {
            val a = actual[i]
            val e = expected[i]
            if (a.tnf != e.tnf) {
                return "第 ${i + 1} 条记录的 TNF 不一致：期望 ${e.tnf}，实际 ${a.tnf}"
            }
            if (!a.type.contentEquals(e.type)) {
                return "第 ${i + 1} 条记录的 type 字段不一致"
            }
            if (!a.id.contentEquals(e.id)) {
                return "第 ${i + 1} 条记录的 ID 字段不一致"
            }
            if (!a.payload.contentEquals(e.payload)) {
                return "第 ${i + 1} 条记录的载荷不一致" +
                    "（期望 ${e.payload.size} 字节，实际 ${a.payload.size} 字节）"
            }
        }
        return null
    }
}

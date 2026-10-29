package com.nfctool.app.core.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import com.nfctool.app.R
import com.nfctool.app.core.i18n.AppStrings
import com.nfctool.app.core.nfc.ndef.NdefMessageCodec

/**
 * 标签复制（克隆）：把一张标签上的 NDEF 消息原样搬到另一张标签上。
 *
 * ## 为什么是「整条消息」而不是「逐记录」
 *
 * 源卡上的记录可能带有 ID、不同的 MB/ME 位置、短记录标志等细节。
 * 按「记录 → 字段」搬运会把这些元信息丢掉或重写。
 * 所以这里直接把整条消息序列化成字节（见 [NdefMessageCodec]）再写入，
 * 目标卡拿到的字节流与源卡完全一致。
 *
 * ## 能复制什么 / 不能复制什么
 *
 * - ✅ **NDEF 消息内容**：跨卡型通用，NTAG / Ultralight / Classic / Type4 都走这条路。
 * - ❌ **UID**：绝大多数标签的 UID 出厂锁定、不可写；个别"UID 可改写"的魔改卡
 *   需要厂商私有指令（且不同品牌不通用），本应用不做。
 * - ❌ **密码 / 密钥区**：故意跳过。NTAG 的 PWD/PACK 与 Classic 的密钥 A/B
 *   不在 NDEF 消息里，复制它们既不安全也常常无意义。
 */
object TagCloner {

    /**
     * 源卡快照。
     *
     * 存的是**枚举名**而不是展示文案（历史记录同款约定），
     * 这样切换语言后旧数据依然能正确显示。
     */
    data class CloneSource(
        val uidHex: String,
        val cardTypeKey: String,
        val techKeys: List<String>,
        val recordCount: Int,
        val byteCount: Int,
        val capacity: Int,
        val writable: Boolean,
        /** 整条 NDEF 消息的原始字节 */
        val messageBytes: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CloneSource) return false
            return uidHex == other.uidHex &&
                cardTypeKey == other.cardTypeKey &&
                techKeys == other.techKeys &&
                recordCount == other.recordCount &&
                byteCount == other.byteCount &&
                capacity == other.capacity &&
                writable == other.writable &&
                messageBytes.contentEquals(other.messageBytes)
        }

        override fun hashCode(): Int {
            var result = uidHex.hashCode()
            result = 31 * result + cardTypeKey.hashCode()
            result = 31 * result + techKeys.hashCode()
            result = 31 * result + recordCount
            result = 31 * result + byteCount
            result = 31 * result + capacity
            result = 31 * result + writable.hashCode()
            result = 31 * result + messageBytes.contentHashCode()
            return result
        }
    }

    /**
     * 抓取源卡快照。
     *
     * 复用 [TagReader.readOverview] 拿卡型 / 技术 / NDEF 状态（区分「不支持」「为空」「出错」），
     * 再单独读一次原始消息用于序列化 —— 多读一次只花几十毫秒，换来的
     * 是错误提示能说清到底卡在哪一步。
     */
    fun capture(session: TagSession): Result<CloneSource> = runCatching {
        val manager = NdefManager(session)
        val overview = TagReader.readOverview(session)
        when (overview.ndefState) {
            NdefManager.NdefState.UNSUPPORTED -> error(AppStrings.tagNoNdef())
            NdefManager.NdefState.EMPTY -> error(AppStrings.get(R.string.err_clone_source_empty))
            NdefManager.NdefState.ERROR ->
                error(overview.ndefError ?: AppStrings.get(R.string.err_ndef_read_failed))
            NdefManager.NdefState.OK -> Unit
        }

        val message = manager.read().getOrThrow()
        val raws = message.records.map { r ->
            NdefMessageCodec.RawRecord(
                tnf = r.tnf.toInt(),
                type = r.type ?: ByteArray(0),
                id = r.id ?: ByteArray(0),
                payload = r.payload ?: ByteArray(0),
            )
        }
        require(raws.isNotEmpty()) { AppStrings.get(R.string.err_clone_source_empty) }
        val bytes = NdefMessageCodec.encodeMessage(raws)

        CloneSource(
            uidHex = Hex.formatUid(session.uid),
            cardTypeKey = overview.cardType.name,
            techKeys = session.techs.map { it.name },
            recordCount = raws.size,
            byteCount = bytes.size,
            capacity = overview.ndefCapacity,
            writable = overview.ndefWritable || overview.ndefFormatable,
            messageBytes = bytes,
        )
    }

    /**
     * 把快照写进目标标签。
     *
     * 容量预检与回读校验都在 [NdefManager.write] 内部完成 ——
     * 部分机型在标签已离开感应范围时 `writeNdefMessage` 不抛异常却没写进去，
     * 只有回读才能确认数据真的落到了标签上。
     *
     * @param lock 写完后是否设为只读（**不可逆**，UI 必须二次确认）
     */
    fun write(session: TagSession, source: CloneSource, lock: Boolean = false): Result<Unit> =
        runCatching {
            val m = NdefManager(session)
            m.write(NdefMessage(source.messageBytes)).getOrThrow()

            if (lock) {
                // ⚠️ 不可逆：标签将永久无法再写入
                val n = session.ndef ?: error(AppStrings.get(R.string.err_lock_need_ndef))
                if (!n.isConnected) n.connect()
                if (!n.makeReadOnly()) error(AppStrings.get(R.string.err_lock_failed))
            }
        }
}

package com.nfctool.app.core.hce

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import com.nfctool.app.core.nfc.Hex

/**
 * 主机卡模拟（HCE）服务。
 *
 * 让本机手机扮演一张 NFC 卡：读卡器（公交机、门禁头、自助终端）靠近手机时，
 * 会把 APDU 指令发给手机，本服务负责响应。
 *
 * 生命周期由系统管理：读卡器靠近时绑定，NFC 链路断开时 onDeactivated 被调用。
 *
 * 注意：本服务注册的是厂商私有 AID，不与支付类 AID 冲突，
 * 也不会介入任何支付流程。
 */
class NfcHceService : HostApduService() {

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "HCE 服务已创建")
    }

    /**
     * 处理读卡器发来的 APDU。
     *
     * @param commandApdu 收到的 APDU
     * @param extras      系统附加信息
     * @return 响应 APDU（数据 + 状态字）
     */
    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null || commandApdu.isEmpty()) {
            return HceApduProcessor.STATUS_WRONG_LENGTH
        }

        val profile = HceProfile.default()
        val processor = HceApduProcessor(uid = simulateUid(), profile = profile)

        val response = processor.process(commandApdu)

        Log.i(
            TAG,
            "APDU RX: ${Hex.toHex(commandApdu)}  ->  TX: ${Hex.toHex(response)}"
        )
        return response
    }

    /**
     * NFC 链路断开或切换到其他 AID。
     *
     * @param reason DEACTIVATION_LINK_LOSS 或 DEACTIVATION_DESELECTED
     */
    override fun onDeactivated(reason: Int) {
        val reasonText = when (reason) {
            DEACTIVATION_LINK_LOSS -> "链路断开"
            DEACTIVATION_DESELECTED -> "被取消选择"
            else -> "未知($reason)"
        }
        Log.i(TAG, "HCE 服务停用：$reasonText")
    }

    /**
     * 模拟卡片的 UID。
     *
     * 真实卡片的 UID 由芯片出厂固化，HCE 无法伪造真实 UID，
     * 这里返回一个稳定的本地标识，便于读卡器侧做链路自检。
     */
    private fun simulateUid(): ByteArray = byteArrayOf(
        0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01,
    )

    companion object {
        private const val TAG = "NfcHceService"

        /** 本服务的 ComponentName，供设置页查询与前台优先注册使用 */
        fun componentName(context: android.content.Context) =
            android.content.ComponentName(context, NfcHceService::class.java)
    }
}

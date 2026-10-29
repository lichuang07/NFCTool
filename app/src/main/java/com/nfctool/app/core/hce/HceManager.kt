package com.nfctool.app.core.hce

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.cardemulation.CardEmulation
import android.os.Build

/**
 * HCE（主机卡模拟）状态与操作。
 *
 * @property deviceSupported 设备是否支持 HCE 硬件
 * @property aidsRegistered  本应用注册的 AID 列表
 * @property isDefaultForAid 该 AID 是否默认由本应用处理
 */
data class HceStatus(
    val deviceSupported: Boolean = false,
    val aidsRegistered: List<String> = emptyList(),
    val isDefaultForAid: Boolean = false,
    val foregroundPreferred: Boolean = false,
)

/**
 * HCE 管理器：负责查询设备能力、注册前台优先服务、跳转到系统卡模拟设置。
 */
class HceManager(private val context: Context) {

    private val cardEmulation: CardEmulation? =
        context.getSystemService(CardEmulation::class.java)

    private val serviceComponent: ComponentName by lazy {
        ComponentName(context, NfcHceService::class.java)
    }

    /** 设备是否支持 HCE */
    val deviceSupported: Boolean
        get() = context.packageManager.hasSystemFeature(
            PackageManager.FEATURE_NFC_HOST_CARD_EMULATION
        )

    /** 读取当前 HCE 状态 */
    fun readStatus(): HceStatus {
        val ce = cardEmulation ?: return HceStatus(deviceSupported = false)
        val profile = HceProfile.default()
        val aids = runCatching {
            ce.getAidsForService(serviceComponent, CardEmulation.CATEGORY_OTHER)
        }.getOrNull().orEmpty()

        val isDefault = profile.aidHexList.any { aidHex ->
            runCatching {
                ce.isDefaultServiceForAid(serviceComponent, aidHex)
            }.getOrDefault(false)
        }

        return HceStatus(
            deviceSupported = deviceSupported,
            aidsRegistered = aids,
            isDefaultForAid = isDefault,
        )
    }

    /**
     * 注册为前台优先服务。
     *
     * 应用在前台时可指定优先使用自己的 HCE 服务，从而绕开 AID 冲突解析。
     * 这需要 API 19+，但在部分定制系统上会被忽略。
     */
    fun setForegroundPreferred(activity: Activity, enable: Boolean): Boolean {
        val ce = cardEmulation ?: return false
        return runCatching {
            if (enable) {
                ce.setPreferredService(activity, serviceComponent)
            } else {
                ce.unsetPreferredService(activity)
            }
        }.getOrDefault(false)
    }

    /**
     * 打开系统的卡模拟设置页，让用户管理默认服务。
     */
    fun openCardEmulationSettings() {
        val intent = Intent(CardEmulation.ACTION_CHANGE_DEFAULT).apply {
            putExtra(CardEmulation.EXTRA_CATEGORY, CardEmulation.CATEGORY_OTHER)
            putExtra(CardEmulation.EXTRA_SERVICE_COMPONENT, serviceComponent)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            // 部分系统没有该 Activity，退回到 NFC 设置页
            runCatching {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_NFC_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    /** 打开系统的默认应用 / 钱包设置（Android 15+ 的钱包角色） */
    fun openWalletSettings() {
        if (Build.VERSION.SDK_INT >= 35) {
            runCatching {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        } else {
            openCardEmulationSettings()
        }
    }
}

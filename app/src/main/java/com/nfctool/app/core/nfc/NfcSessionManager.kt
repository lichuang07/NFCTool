package com.nfctool.app.core.nfc

import com.nfctool.app.R
import com.nfctool.app.core.i18n.AppStrings
import android.app.Activity
import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * NFC 适配器状态。
 */
data class NfcStatus(
    val adapterPresent: Boolean = false,
    val enabled: Boolean = false,
    val secureNfcSupported: Boolean = false,
    val secureNfcEnabled: Boolean = false,
    val observeModeSupported: Boolean = false,
    val observeModeEnabled: Boolean = false,
    val readerOptionSupported: Boolean = false,
    val readerOptionEnabled: Boolean = false,
    val tagIntentAllowed: Boolean = true,
    val antennaPosition: String = "",
) {
    val canScan: Boolean get() = adapterPresent && enabled
}

/**
 * NFC 会话管理器。
 *
 * 使用 Reader Mode（[NfcAdapter.enableReaderMode]）而非前台广播（enableForegroundDispatch），
 * 原因：
 *  1. Reader Mode 只在当前应用内派发标签，不会被系统其它应用抢走
 *  2. 可精确控制轮询的技术类型与 NDEF 检查行为
 *  3. 不会因为提前关闭 reader mode 而被系统默认应用弹走（这是最常见的坑）
 */
class NfcSessionManager(private val context: Context) {

    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(context)

    private val _status = MutableStateFlow(readStatus())
    val status: StateFlow<NfcStatus> = _status.asStateFlow()

    private val _tags = MutableStateFlow<List<TagOverview>>(emptyList())
    val tags: StateFlow<List<TagOverview>> = _tags.asStateFlow()

    private var readerModeEnabled = false
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 读到的标签回调 */
    var onTagDiscovered: ((Tag) -> Unit)? = null

    /**
     * 启用 Reader Mode。
     *
     * 注意：Reader Mode 的开关由 Activity 生命周期管理（onResume / onPause），
     * 这里只提供可选的精细控制入口，实际读卡回调统一走 [onTagDiscovered]。
     *
     * @param activity 当前 Activity
     * @param includeNfcB 是否轮询 NFC-B
     * @param skipNdefCheck 跳过平台的 NDEF 自动探测（提速约 500ms，但拿不到 Ndef 技术）
     */
    fun enableReaderMode(
        activity: Activity,
        includeNfcB: Boolean = true,
        skipNdefCheck: Boolean = false,
    ) {
        val a = adapter ?: return
        if (readerModeEnabled) return
        var flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V or
            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
        if (includeNfcB) flags = flags or NfcAdapter.FLAG_READER_NFC_B
        if (skipNdefCheck) flags = flags or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK

        try {
            a.enableReaderMode(activity, { tag ->
                onTagDiscovered?.invoke(tag)
            }, flags, Bundle())
            readerModeEnabled = true
        } catch (e: Exception) {
            readerModeEnabled = false
        }
    }

    fun disableReaderMode(activity: Activity) {
        val a = adapter ?: return
        if (!readerModeEnabled) return
        runCatching { a.disableReaderMode(activity) }
        readerModeEnabled = false
    }

    /** 刷新适配器状态 */
    fun refreshStatus() {
        _status.value = readStatus()
    }

    private fun readStatus(): NfcStatus {
        val a = adapter ?: return NfcStatus()
        fun <T> safe(block: () -> T): T? = runCatching(block).getOrNull()

        // 以下能力都是后期系统版本引入，必须按 API 级别守卫，
        // 否则在 Android 7/8 设备上会直接抛 NoSuchMethodError。
        val secureSupported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            safe { a.isSecureNfcSupported } ?: false
        } else false
        val observeSupported = if (Build.VERSION.SDK_INT >= 35) {
            safe { a.isObserveModeSupported } ?: false
        } else false
        val readerOptSupported = if (Build.VERSION.SDK_INT >= 35) {
            safe { a.isReaderOptionSupported } ?: false
        } else false
        val tagIntentAllowed = if (Build.VERSION.SDK_INT >= 36) {
            safe { a.isTagIntentAllowed } ?: true
        } else true

        val antenna = if (Build.VERSION.SDK_INT >= 34) {
            safe { readAntennaPosition(a) } ?: ""
        } else ""

        return NfcStatus(
            adapterPresent = true,
            enabled = safe { a.isEnabled } ?: false,
            secureNfcSupported = secureSupported,
            secureNfcEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                safe { a.isSecureNfcEnabled } ?: false
            } else false,
            observeModeSupported = observeSupported,
            observeModeEnabled = if (Build.VERSION.SDK_INT >= 35) {
                safe { a.isObserveModeEnabled } ?: false
            } else false,
            readerOptionSupported = readerOptSupported,
            readerOptionEnabled = if (Build.VERSION.SDK_INT >= 35) {
                safe { a.isReaderOptionEnabled } ?: false
            } else false,
            tagIntentAllowed = tagIntentAllowed,
            antennaPosition = antenna,
        )
    }

    /** 读取天线位置描述（需要 API 34+） */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun readAntennaPosition(a: NfcAdapter): String {
        val info = a.nfcAntennaInfo ?: return ""
        val antennas = info.availableNfcAntennas
        if (antennas.isEmpty()) return AppStrings.get(R.string.antenna_builtin)
        val names = antennas.map { ant ->
            val vertical = when {
                ant.locationY < info.deviceHeight / 3 -> AppStrings.get(R.string.antenna_pos_top)
                ant.locationY > info.deviceHeight * 2 / 3 -> AppStrings.get(R.string.antenna_pos_bottom)
                else -> AppStrings.get(R.string.antenna_pos_middle)
            }
            val horizontal = when {
                ant.locationX < info.deviceWidth / 3 -> AppStrings.get(R.string.antenna_pos_left)
                ant.locationX > info.deviceWidth * 2 / 3 -> AppStrings.get(R.string.antenna_pos_right)
                else -> AppStrings.get(R.string.antenna_pos_middle)
            }
            "$vertical$horizontal"
        }
        return "${names.joinToString("/")} (${antennas.size})"
    }

    /** 打开系统 NFC 设置页 */
    fun openNfcSettings() {
        runCatching {
            context.startActivity(
                android.provider.Settings.ACTION_NFC_SETTINGS
                    .let { android.content.Intent(it) }
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** 打开系统默认应用设置（用于开启标签intent） */
    fun openDefaultAppsSettings() {
        runCatching {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * 设置 polling 技术（需要 API 35+）。
     * 只保留需要的射频协议可以显著降低功耗与干扰。
     */
    fun setPollTechnology(activity: Activity, techs: List<NfcTech>) {
        if (Build.VERSION.SDK_INT < 35) return
        setPollTechnologyInternal(activity, techs)
    }

    @RequiresApi(35)
    private fun setPollTechnologyInternal(activity: Activity, techs: List<NfcTech>) {
        val a = adapter ?: return
        var poll = NfcAdapter.FLAG_READER_DISABLE
        if (techs.contains(NfcTech.NFC_A) || techs.contains(NfcTech.MIFARE_CLASSIC) ||
            techs.contains(NfcTech.MIFARE_ULTRALIGHT) || techs.contains(NfcTech.NDEF)
        ) poll = poll or NfcAdapter.FLAG_READER_NFC_A
        if (techs.contains(NfcTech.NFC_B)) poll = poll or NfcAdapter.FLAG_READER_NFC_B
        if (techs.contains(NfcTech.NFC_F)) poll = poll or NfcAdapter.FLAG_READER_NFC_F
        if (techs.contains(NfcTech.NFC_V)) poll = poll or NfcAdapter.FLAG_READER_NFC_V
        runCatching { a.setDiscoveryTechnology(activity, poll, NfcAdapter.FLAG_LISTEN_KEEP) }
    }
}

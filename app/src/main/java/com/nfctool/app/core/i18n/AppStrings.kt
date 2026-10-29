package com.nfctool.app.core.i18n

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.StringRes
import com.nfctool.app.R

/**
 * 跨层取词器。
 *
 * ## 为什么需要它
 * UI 层用 Compose 的 `stringResource`（走 Activity 的 locale 配置）；
 * 但 core 层的 ops / codec / exporter 没有 Context，又要产出用户可见的
 * 错误信息与导出报告。逐个给它们加 Context 参数会污染全部签名，
 * 所以用这个单例持有一个**按当前语言包装过的 Context**。
 *
 * ## 生命周期
 * - [com.nfctool.app.NfctoolApp.onCreate] 里 [init] 一次；
 * - 用户在设置里切换语言时，由 MainActivity 调 [update] 换 Context，
 *   同时 Activity recreate 让 UI 层生效。
 *
 * 未初始化时（例如纯 JUnit 环境）返回空串，不影响测试。
 *
 * 持有的一定是 **Application Context**（见 [init] 的调用方），生命周期与进程等长，
 * 不存在泄漏，故抑制 lint 的 StaticFieldLeak。
 */
@SuppressLint("StaticFieldLeak")
object AppStrings {

    @Volatile
    private var localized: Context? = null

    /** 应用启动时调用一次 */
    fun init(appContext: Context) {
        if (localized == null) {
            localized = LocalePref.wrap(appContext, LocalePref.currentLocale(appContext))
        }
    }

    /** 语言切换后调用：用新语言重新包装 Context */
    fun update(appContext: Context) {
        localized = LocalePref.wrap(appContext, LocalePref.currentLocale(appContext))
    }

    /** 取词；[args] 为格式参数 */
    fun get(@StringRes id: Int, vararg args: Any?): String =
        localized?.getString(id, *args) ?: ""

    // ---- 常用文案的便捷入口（core 层高频，避免每个文件都 import R） ----

    fun tagNoMifareClassic(): String = get(R.string.err_tag_no_mifare_classic)
    fun tagNoIsoDep(): String = get(R.string.err_tag_no_isodep)
    fun tagNoNdef(): String = get(R.string.err_tag_no_ndef)
    fun noNdefMessage(): String = get(R.string.err_no_ndef_message)
    fun tagLocked(): String = get(R.string.err_tag_locked)
    fun tagNotFormattable(): String = get(R.string.err_tag_not_formattable)
    fun readFailed(): String = get(R.string.err_read_failed)
    fun needReadFirst(): String = get(R.string.err_need_read_first)
    fun contentEmpty(): String = get(R.string.err_content_empty)
    fun imageEmpty(): String = get(R.string.err_image_empty)
    fun qrImageEmpty(): String = get(R.string.err_qr_empty)
    fun tagNoNfcV(): String = get(R.string.err_tag_no_nfcv)
}

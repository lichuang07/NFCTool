package com.nfctool.app.core.i18n

import android.content.Context
import android.content.res.Configuration
import androidx.core.content.edit
import java.util.Locale

/**
 * 语言偏好：跟随系统 / 中文 / English。
 *
 * 为什么用 SharedPreferences 而不是 DataStore：
 * [android.app.Activity.attachBaseContext] 里要**同步**读到语言选择来包装
 * 基 Context，DataStore 的读是挂起函数，赶不上那个时机。
 * 因此这里是唯一事实源；ViewModel 只把它包成 StateFlow 给 UI 订阅。
 */
object LocalePref {

    /** 跟随系统语言 */
    const val SYSTEM = "system"

    /** 简体中文 */
    const val CHINESE = "zh"

    /** 英文 */
    const val ENGLISH = "en"

    private const val FILE = "nfctool_locale"
    private const val KEY = "language"

    /** 当前选择；默认跟随系统 */
    fun language(context: Context): String =
        prefs(context).getString(KEY, SYSTEM) ?: SYSTEM

    fun setLanguage(context: Context, tag: String) {
        // 必须同步落盘：调用方紧接着就会 recreate Activity，
        // attachBaseContext 会在新进程/新实例里同步读取这个值
        prefs(context).edit(commit = true) { putString(KEY, tag) }
    }

    /**
     * 当前选择对应的语言。
     *
     * @return null 表示跟随系统（不包装 Context，走系统默认资源解析）
     */
    fun currentLocale(context: Context): Locale? = when (language(context)) {
        CHINESE -> Locale.SIMPLIFIED_CHINESE
        ENGLISH -> Locale.ENGLISH
        else -> null
    }

    /**
     * 用指定语言包装 Context。
     *
     * @param locale 为 null 时原样返回（跟随系统）
     */
    fun wrap(base: Context, locale: Locale?): Context {
        if (locale == null) return base
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        // 中文/英文都是 LTR，这里仍显式设置，避免 RTL 语言下方向错乱
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}

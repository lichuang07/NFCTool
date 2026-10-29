package com.nfctool.app.core.nfc.ndef

import android.nfc.NdefRecord

/**
 * Android Application Record（AAR）解析器。
 *
 * 已核对 AOSP `NdefRecord.createApplicationRecord()` 实现：
 *
 * ```java
 * return new NdefRecord(TNF_EXTERNAL_TYPE, RTD_ANDROID_APP, null,
 *         packageName.getBytes(StandardCharsets.UTF_8));
 * ```
 *
 * 即：
 *  - TNF = 0x04（TNF_EXTERNAL_TYPE）
 *  - type = `android.com:pkg`
 *  - payload = 应用包名，UTF-8
 *
 * 作用：Android 读到含 AAR 的标签时，会**强制**把事件派发给指定包名的应用，
 * 绕过常规 Intent 匹配；若应用未安装，则跳转到应用商店。
 * 因此 AAR 常与 URI 记录组合使用：URI 给非安卓设备兜底，AAR 保证安卓设备走指定应用。
 */
object AndroidAppRecordCodec {

    private const val TYPE_ANDROID_APP = "android.com:pkg"

    fun isAndroidAppRecord(record: NdefRecord): Boolean {
        if (record.tnf != NdefRecord.TNF_EXTERNAL_TYPE) return false
        return String(record.type, Charsets.UTF_8).equals(TYPE_ANDROID_APP, ignoreCase = true)
    }

    /**
     * 提取包名；不是 AAR 或内容不合法时返回 null。
     */
    fun decode(record: NdefRecord): String? {
        if (!isAndroidAppRecord(record)) return null
        val name = String(record.payload, Charsets.UTF_8).trim()
        if (name.isEmpty()) return null
        // 包名只允许字母数字与下划线，且必须含至少一个点
        if (!name.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) return null
        return name
    }

    /**
     * 构造一条 AAR 记录。
     */
    fun createRecord(packageName: String): NdefRecord {
        require(packageName.isNotBlank()) { "包名不能为空" }
        return NdefRecord.createApplicationRecord(packageName.trim())
    }
}

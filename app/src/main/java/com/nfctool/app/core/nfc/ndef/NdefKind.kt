package com.nfctool.app.core.nfc.ndef

import androidx.annotation.StringRes
import com.nfctool.app.R

/**
 * NDEF 记录的细分类型。
 *
 * NDEF 规范只定义了 8 种 TNF（Type Name Format），所有业务语义都压缩在
 * TNF 之上的 type 字段里。例如：
 *
 *  - Wi-Fi 配网  → TNF_MIME_MEDIA + type="application/vnd.wfa.wsc"
 *  - 电子名片    → TNF_MIME_MEDIA + type="text/vcard"
 *  - 应用唤起    → TNF_EXTERNAL_TYPE + type="android.com:pkg"
 *  - 连接移交    → TNF_WELL_KNOWN   + type="Hr" / "Hs"
 *
 * 所以本枚举不代表 NDEF 的原始分类，而是应用层对 type 字段的**二次归类**，
 * 目的是让 UI 能把"Wi-Fi 配网"显示成一个独立类型，而不是笼统的 "MIME"。
 *
 * [labelRes] 指向字符串资源，界面随语言切换（2026-10-25 国际化改造）。
 */
enum class NdefKind(
    @StringRes val labelRes: Int,
    val letter: Char,
) {
    TEXT(R.string.ndef_kind_text, 'T'),
    URI(R.string.ndef_kind_uri, 'U'),
    GEO(R.string.ndef_kind_geo, 'G'),
    WIFI(R.string.ndef_kind_wifi, 'W'),
    VCARD(R.string.ndef_kind_vcard, 'V'),
    MECARD(R.string.ndef_kind_mecard, 'M'),
    VCALENDAR(R.string.ndef_kind_vcalendar, 'C'),
    SMART_POSTER(R.string.ndef_kind_smart_poster, 'S'),
    ANDROID_APP(R.string.ndef_kind_android_app, 'A'),
    HANDOVER(R.string.ndef_kind_handover, 'H'),
    BLUETOOTH_OOB(R.string.ndef_kind_bluetooth_oob, 'B'),
    AID(R.string.ndef_kind_aid, 'D'),
    MIME(R.string.ndef_kind_mime, 'X'),
    EXTERNAL(R.string.ndef_kind_external, 'E'),
    UNKNOWN(R.string.ndef_kind_unknown, '?'),
}

/**
 * 细分类型解析出的一条结构化字段。
 */
data class NdefDetail(
    val label: String,
    val value: String,
)

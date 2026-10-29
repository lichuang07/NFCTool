package com.nfctool.app.ui.theme

import androidx.compose.ui.graphics.Color
import com.nfctool.app.R

/**
 * NFC 工具的配色体系：深空底色 + 靛紫主色。
 *
 * 设计取向：
 *  - 背景用极深的蓝黑（#080B14），让霓虹色的数据有"仪器面板"的质感
 *  - 主色靛紫(#6366F1)与应用图标同源，形成品牌一致性
 *  - 副色青(#00E5FF)保留，代表 NFC 射频与数据流
 *  - 卡片使用半透明玻璃层，配合背景光晕形成层次
 *
 * 配色与图标的关系：图标 F 方案（NMark）的渐变是
 * #3730A3 → #6366F1 → #818CF8，主强调色取中段 #6366F1。
 */

// ---- 基础色 ----
val Ink900 = Color(0xFF05070D)
val Ink850 = Color(0xFF080B14)
val Ink800 = Color(0xFF0D1220)
val Ink700 = Color(0xFF151C2E)
val Ink600 = Color(0xFF1E2739)
val Ink500 = Color(0xFF2A3548)

// ---- 主色 ----
/** 靛紫：与应用图标主色一致 */
val NeonIndigo = Color(0xFF6366F1)
val NeonIndigoDim = Color(0xFF4F46E5)
val NeonIndigoBright = Color(0xFF818CF8)
val NeonCyan = Color(0xFF00E5FF)
val NeonCyanDim = Color(0xFF00A8BF)
val NeonMagenta = Color(0xFFFF2E93)
val NeonMagentaDim = Color(0xFFB81F66)
val NeonViolet = Color(0xFF8B5CF6)
val NeonAmber = Color(0xFFFFB020)
val NeonLime = Color(0xFF7CFF4F)

// ---- 文本 ----
val TextPrimary = Color(0xFFF2F6FF)
val TextSecondary = Color(0xFFA8B4CC)
val TextTertiary = Color(0xFF6B7A96)

// ---- 状态色 ----
val StatusOk = Color(0xFF34D399)
val StatusWarn = Color(0xFFFBBF24)
val StatusError = Color(0xFFF87171)
val StatusInfo = Color(0xFF60A5FA)

// ---- 玻璃层 ----
val GlassSurface = Color(0x1AFFFFFF)
val GlassSurfaceHigh = Color(0x26FFFFFF)
val GlassBorder = Color(0x1FFFFFFF)
/** 强调色描边：与默认强调色（靛紫）一致 */
val GlassBorderAccent = Color(0x4D6366F1)

// ---- 强调色预设（供设置页切换） ----
data class AccentPalette(
    val name: String,
    val primary: Color,
    val primaryDim: Color,
    val secondary: Color,
    val glow: Color,
)

/**
 * 默认强调色：靛紫。
 *
 * 取自应用图标 F 方案（NMark）的渐变中段，
 * 保证桌面图标与应用内界面是同一套色。
 */
val AccentIndigo = AccentPalette(
    name = "星云紫",
    primary = NeonIndigo,
    primaryDim = NeonIndigoDim,
    secondary = NeonCyan,
    glow = Color(0x336366F1),
)

val AccentCyan = AccentPalette(
    name = "极光青",
    primary = NeonCyan,
    primaryDim = NeonCyanDim,
    secondary = NeonMagenta,
    glow = Color(0x3300E5FF),
)

val AccentMagenta = AccentPalette(
    name = "霓虹粉",
    primary = NeonMagenta,
    primaryDim = NeonMagentaDim,
    secondary = NeonCyan,
    glow = Color(0x33FF2E93),
)

val AccentViolet = AccentPalette(
    name = "鸢尾紫",
    primary = NeonViolet,
    primaryDim = Color(0xFF6D28D9),
    secondary = NeonCyan,
    glow = Color(0x338B5CF6),
)

val AccentAmber = AccentPalette(
    name = "熔岩金",
    primary = NeonAmber,
    primaryDim = Color(0xFFD08C00),
    secondary = NeonMagenta,
    glow = Color(0x33FFB020),
)

val AccentLime = AccentPalette(
    name = "数据绿",
    primary = NeonLime,
    primaryDim = Color(0xFF4DBE2E),
    secondary = NeonCyan,
    glow = Color(0x337CFF4F),
)

/** 预设列表：默认项排在首位 */
val AccentPalettes = listOf(
    AccentIndigo,
    AccentCyan,
    AccentMagenta,
    AccentViolet,
    AccentAmber,
    AccentLime,
)

fun accentPalette(key: String): AccentPalette =
    AccentPalettes.firstOrNull { it.name == key } ?: AccentIndigo

/**
 * 强调色预设名 → 展示文案资源。
 *
 * 设置里持久化的是预设 key（历史上存的是中文名，如「星云紫」），
 * 这里两种都认，保证旧设置不丢；展示时走资源，随语言切换。
 */
fun accentLabelRes(key: String): Int = when (key) {
    "星云紫", "indigo" -> R.string.accent_nebula
    "极光青", "cyan" -> R.string.accent_aurora
    "霓虹粉", "magenta" -> R.string.accent_neon_pink
    "鸢尾紫", "violet" -> R.string.accent_iris
    "熔岩金", "amber" -> R.string.accent_lava
    "数据绿", "lime" -> R.string.accent_data_green
    else -> R.string.accent_nebula
}

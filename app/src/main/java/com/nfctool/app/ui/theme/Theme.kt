package com.nfctool.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 主题上下文：把强调色与玻璃参数注入到 Composable 树里。
 */
data class NfcThemeExtras(
    val accent: AccentPalette,
    val glassSurface: androidx.compose.ui.graphics.Color,
    val glassSurfaceHigh: androidx.compose.ui.graphics.Color,
    val glassBorder: androidx.compose.ui.graphics.Color,
    val glassBorderAccent: androidx.compose.ui.graphics.Color,
    val textTertiary: androidx.compose.ui.graphics.Color,
)

val LocalNfcExtras = staticCompositionLocalOf {
    NfcThemeExtras(
        accent = AccentIndigo,
        glassSurface = GlassSurface,
        glassSurfaceHigh = GlassSurfaceHigh,
        glassBorder = GlassBorder,
        glassBorderAccent = GlassBorderAccent,
        textTertiary = TextTertiary,
    )
}

/** 便捷访问器 */
val extras: NfcThemeExtras
    @Composable get() = LocalNfcExtras.current

/**
 * 等宽字体样式：用于展示十六进制、UID、APDU 等必须对齐的数据。
 */
object MonoText {
    val caption = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
    )
    val body = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    )
    val title = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
    )
    val huge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
    )
}

/**
 * 应用字体体系。
 */
private val NfcTypography = Typography(
    displaySmall = TextStyle(
        fontSize = 32.sp, lineHeight = 40.sp,
        fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontSize = 24.sp, lineHeight = 32.sp,
        fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp,
    ),
    headlineSmall = TextStyle(
        fontSize = 20.sp, lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleLarge = TextStyle(
        fontSize = 18.sp, lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp, lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = TextStyle(
        fontSize = 15.sp, lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp, lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp, lineHeight = 17.sp,
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp, lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp, lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelSmall = TextStyle(
        fontSize = 10.sp, lineHeight = 14.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp,
    ),
)

/**
 * 主题入口。
 *
 * @param accentKey 强调色预设名
 * @param darkMode 是否使用深色（默认跟随系统）
 */
@Composable
fun NfctoolTheme(
    accentKey: String = "星云紫", // 历史默认值，accentPalette() 两种写法都认
    darkMode: Boolean = true,
    content: @Composable () -> Unit,
) {
    val accent = accentPalette(accentKey)
    val useDark = darkMode || isSystemInDarkTheme()

    val colorScheme = if (useDark) {
        darkColorScheme(
            primary = accent.primary,
            onPrimary = Ink900,
            primaryContainer = accent.primaryDim,
            onPrimaryContainer = TextPrimary,
            secondary = accent.secondary,
            onSecondary = Ink900,
            tertiary = NeonViolet,
            background = Ink850,
            onBackground = TextPrimary,
            surface = Ink800,
            onSurface = TextPrimary,
            surfaceVariant = Ink700,
            onSurfaceVariant = TextSecondary,
            outline = Ink500,
            outlineVariant = Ink600,
            error = StatusError,
            onError = Ink900,
        )
    } else {
        lightColorScheme(
            primary = accent.primaryDim,
            onPrimary = Color.White,
            primaryContainer = accent.primary.copy(alpha = 0.18f),
            onPrimaryContainer = Ink900,
            secondary = accent.secondary,
            onSecondary = Color.White,
            tertiary = NeonViolet,
            background = Color(0xFFF5F7FB),
            onBackground = Ink900,
            surface = Color.White,
            onSurface = Ink900,
            surfaceVariant = Color(0xFFE8ECF4),
            onSurfaceVariant = Color(0xFF4A5568),
            outline = Color(0xFFCBD3E1),
            outlineVariant = Color(0xFFDFE5EF),
            error = StatusError,
            onError = Color.White,
        )
    }

    val localExtras = if (useDark) {
        NfcThemeExtras(
            accent = accent,
            glassSurface = GlassSurface,
            glassSurfaceHigh = GlassSurfaceHigh,
            glassBorder = GlassBorder,
            glassBorderAccent = GlassBorderAccent,
            textTertiary = TextTertiary,
        )
    } else {
        NfcThemeExtras(
            accent = accent,
            glassSurface = Color(0xB3FFFFFF),
            glassSurfaceHigh = Color(0xE6FFFFFF),
            glassBorder = Color(0x1A000000),
            glassBorderAccent = accent.primary.copy(alpha = 0.35f),
            textTertiary = Color(0xFF7A8699),
        )
    }

    CompositionLocalProvider(LocalNfcExtras provides localExtras) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = NfcTypography,
            content = content,
        )
    }
}

/** 常用间距 */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

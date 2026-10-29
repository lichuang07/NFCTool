package com.nfctool.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nfctool.app.ui.theme.MonoText
import com.nfctool.app.ui.theme.extras

/**
 * 玻璃拟态卡片：半透明背景 + 细边框 + 顶部高光。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    onClick: (() -> Unit)? = null,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(16.dp),
    content: @Composable ColumnScopeAlias.() -> Unit,
) {
    val e = extras
    val shape = RoundedCornerShape(20.dp)
    val base = Modifier
        .clip(shape)
        .background(
            Brush.verticalGradient(
                listOf(
                    e.glassSurfaceHigh,
                    e.glassSurface,
                )
            )
        )
        .border(
            BorderStroke(
                1.dp,
                if (accent) e.glassBorderAccent else e.glassBorder
            ),
            shape
        )

    Box(base) {
        // 顶部一道高光，模拟玻璃边缘反光
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            (if (accent) e.accent.primary else Color.White).copy(alpha = 0.35f),
                            Color.Transparent,
                        )
                    )
                )
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
                .padding(contentPadding),
            content = content,
        )
    }
}

/** ColumnScope 的别名，让 GlassCard 的 content lambda 保留作用域 */
typealias ColumnScopeAlias = androidx.compose.foundation.layout.ColumnScope

/**
 * 霓虹强调按钮。
 */
@Composable
fun NeonButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
    filled: Boolean = true,
    onClick: () -> Unit,
) {
    val e = extras
    val base = if (danger) e.accent.secondary else e.accent.primary
    val alpha = if (enabled) 1f else 0.35f
    val shape = RoundedCornerShape(16.dp)

    val backgroundBrush = if (filled) {
        Brush.horizontalGradient(listOf(base.copy(alpha = 0.95f * alpha), base.copy(alpha = 0.65f * alpha)))
    } else {
        Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
    }

    Row(
        modifier = modifier
            .clip(shape)
            .background(backgroundBrush)
            .border(
                BorderStroke(
                    if (filled) 0.dp else 1.dp,
                    base.copy(alpha = 0.6f * alpha)
                ),
                shape
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            icon()
            androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (filled) Color(0xFF06080F) else base.copy(alpha = alpha),
        )
    }
}

/**
 * 次级按钮（描边风格）。
 */
@Composable
fun GhostButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val e = extras
    val shape = RoundedCornerShape(14.dp)
    val alpha = if (enabled) 1f else 0.35f

    Row(
        modifier = modifier
            .clip(shape)
            .background(e.glassSurface)
            .border(BorderStroke(1.dp, e.glassBorder), shape)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            icon()
            androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = e.textTertiary.copy(alpha = alpha),
        )
    }
}

/**
 * 章节标题。
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
) {
    val e = extras
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(width = 3.dp, height = 14.dp)
                    .background(e.accent.primary, RoundedCornerShape(2.dp))
            )
            androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        if (trailing != null) {
            Text(
                text = trailing,
                style = MonoText.caption,
                color = e.textTertiary,
            )
        }
    }
}

/**
 * 数据行：左侧标签 + 右侧值。
 */
@Composable
fun DataRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    valueColor: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val e = extras
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = e.textTertiary,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            text = value,
            style = if (mono) MonoText.body else MaterialTheme.typography.bodyMedium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

/**
 * 状态徽章。
 */
@Composable
fun StatusBadge(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = com.nfctool.app.ui.theme.StatusOk,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(color.copy(alpha = 0.15f))
            .border(BorderStroke(1.dp, color.copy(alpha = 0.4f)), shape)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MonoText.caption,
            color = color,
        )
    }
}

/**
 * 技术标签芯片。
 */
@Composable
fun TechChip(
    text: String,
    modifier: Modifier = Modifier,
    color: Color? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val e = extras
    val c = color ?: e.accent.primary
    val shape = RoundedCornerShape(10.dp)
    val bg by animateColorAsState(
        if (selected) c.copy(alpha = 0.18f) else e.glassSurface,
        label = "chipBg",
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .border(
                BorderStroke(1.dp, if (selected) c.copy(alpha = 0.55f) else e.glassBorder),
                shape
            )
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            style = MonoText.caption,
            color = if (selected) c else e.textTertiary,
        )
    }
}

/**
 * 扫描雷达动画：同心波纹扩散，表示正在搜索标签。
 */
@Composable
fun RadarPulse(
    modifier: Modifier = Modifier,
    active: Boolean = true,
    color: Color = com.nfctool.app.ui.theme.NeonCyan,
) {
    val transition = rememberInfiniteTransition(label = "radar")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )
    val alpha by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 0.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200),
            repeatMode = RepeatMode.Restart,
        ),
        label = "alpha",
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // 三层波纹
        for (i in 0 until 3) {
            val offset = (phase + i * 0.33f) % 1f
            val scale = 0.25f + offset * 0.85f
            val localAlpha = if (active) (1f - offset) * 0.5f else 0f
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawCircle(
                            color = color.copy(alpha = localAlpha),
                            radius = size.minDimension / 2f * scale,
                            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
            )
        }
        // 中心点
        Box(
            Modifier
                .size(14.dp)
                .background(
                    Brush.radialGradient(
                        listOf(
                            color.copy(alpha = if (active) 0.95f else 0.3f),
                            color.copy(alpha = 0f),
                        )
                    ),
                    RoundedCornerShape(7.dp),
                )
        )
    }
}

/**
 * 带渐变描边的强调卡片（用于主操作区）。
 */
@Composable
fun GradientBorderCard(
    modifier: Modifier = Modifier,
    glowColor: Color = com.nfctool.app.ui.theme.NeonCyan,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier
            .clip(shape)
            .drawBehind {
                drawRect(
                    brush = Brush.linearGradient(
                        listOf(
                            glowColor.copy(alpha = 0.55f),
                            com.nfctool.app.ui.theme.NeonMagenta.copy(alpha = 0.35f),
                            glowColor.copy(alpha = 0.15f),
                        )
                    ),
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
    ) {
        content()
    }
}

/**
 * 箭头按钮，用于列表项跳转。
 */
@Composable
fun ChevronIcon(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Rounded.ArrowForward,
        contentDescription = null,
        modifier = modifier.size(16.dp),
        tint = extras.textTertiary,
    )
}

/**
 * 分隔线。
 */
@Composable
fun ThinDivider(modifier: Modifier = Modifier) {
    val e = extras
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, e.glassBorder, Color.Transparent)
                )
            )
    )
}

/**
 * 顶部光晕装饰，强化霓虹氛围。
 */
fun Modifier.ambientGlow(
    color: Color = com.nfctool.app.ui.theme.NeonCyan,
    radius: Float = 320f,
    center: Offset = Offset(0.5f, 0f),
): Modifier = this.drawBehind {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.16f), Color.Transparent),
            center = Offset(size.width * center.x, size.height * center.y),
            radius = radius,
        ),
        radius = radius,
        center = Offset(size.width * center.x, size.height * center.y),
    )
}

package com.nfctool.app.ui.pages

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nfctool.app.R
import com.nfctool.app.core.nfc.CardType
import com.nfctool.app.core.nfc.NfcTech
import com.nfctool.app.core.nfc.TagCloner
import com.nfctool.app.ui.ClonePhase
import com.nfctool.app.ui.CloneState
import com.nfctool.app.ui.MainViewModel
import com.nfctool.app.ui.components.ChevronIcon
import com.nfctool.app.ui.components.GhostButton
import com.nfctool.app.ui.components.GlassCard
import com.nfctool.app.ui.components.NeonButton
import com.nfctool.app.ui.components.RadarPulse
import com.nfctool.app.ui.components.StatusBadge
import com.nfctool.app.ui.theme.StatusOk
import com.nfctool.app.ui.theme.StatusWarn
import com.nfctool.app.ui.theme.extras

/**
 * 复制标签页。
 *
 * 两段式流程，与写入共用「挂起任务等标签」的机制：
 *  1. 贴源标签 → 抓 NDEF 消息快照
 *  2. 贴空白标签 → 把快照原样写进去（写完回读校验）
 *
 * 快照保留在内存里不清空，所以可以拿着同一张源卡**连续复制多张**。
 */
@Composable
fun ClonePage(
    vm: MainViewModel,
    onBack: () -> Unit,
) {
    val clone by vm.clone.collectAsStateWithLifecycle()
    val ui by vm.uiState.collectAsStateWithLifecycle()
    var lockAfterWrite by remember { mutableStateOf(false) }
    var showLockConfirm by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        CloneTopBar(onBack = onBack)

        LazyColumn(
            // 用 weight 而不是 fillMaxSize：后者会让列表请求整个 Column 高度，
            // 把上面的标题栏挤出可视区域
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.clone_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = stringResource(R.string.clone_intro),
                        style = MaterialTheme.typography.bodySmall,
                        color = extras.textTertiary,
                    )
                }
            }

            item {
                SourceStep(
                    clone = clone,
                    busy = ui.busy,
                    onRead = { vm.startCloneRead() },
                    onCancel = { vm.cancelClone() },
                )
            }

            if (clone.source != null) {
                item {
                    TargetStep(
                        clone = clone,
                        lock = lockAfterWrite,
                        onLockChange = { showLockConfirm = true },
                        onUnlockChange = { lockAfterWrite = false },
                        onWrite = { vm.beginCloneWrite(lock = lockAfterWrite) },
                        onFinish = {
                            vm.cancelClone()
                            onBack()
                        },
                    )
                }
            }

            item { LimitsCard() }
        }
    }

    if (showLockConfirm) {
        AlertDialog(
            onDismissRequest = { showLockConfirm = false },
            title = { Text(stringResource(R.string.clone_lock_title)) },
            text = { Text(stringResource(R.string.clone_lock_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    showLockConfirm = false
                    lockAfterWrite = true
                }) { Text(stringResource(R.string.clone_lock_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showLockConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/**
 * 顶部栏：返回箭头 + 标题。
 */
@Composable
private fun CloneTopBar(onBack: () -> Unit) {
    val e = extras
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(e.glassSurface)
                .border(BorderStroke(1.dp, e.glassBorder), RoundedCornerShape(12.dp))
                .clickable { onBack() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
                tint = e.accent.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.clone_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/**
 * 第 1 步：读取源标签。
 *
 * 三种状态：未读取 / 正在等待贴卡 / 已读取（展示快照摘要）。
 */
@Composable
private fun SourceStep(
    clone: CloneState,
    busy: Boolean,
    onRead: () -> Unit,
    onCancel: () -> Unit,
) {
    val e = extras
    val source = clone.source
    val waiting = clone.phase == ClonePhase.READING_SOURCE

    GlassCard(accent = waiting) {
        Text(
            text = stringResource(R.string.clone_step_source),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))

        if (waiting) {
            // 等待贴卡：雷达波纹给一个「在等你」的持续反馈
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    RadarPulse(
                        active = true,
                        color = e.accent.primary,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.clone_source_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(12.dp))
            GhostButton(text = stringResource(R.string.action_cancel), onClick = onCancel)
        } else if (source == null) {
            Text(
                text = stringResource(R.string.clone_none),
                style = MaterialTheme.typography.bodyMedium,
                color = e.textTertiary,
            )
            Spacer(Modifier.height(12.dp))
            NeonButton(
                text = stringResource(R.string.clone_btn_read),
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.Nfc,
                        contentDescription = null,
                        tint = Color(0xFF06080F),
                        modifier = Modifier.size(18.dp),
                    )
                },
                onClick = onRead,
            )
        } else {
            Text(
                text = stringResource(R.string.clone_source_card),
                style = MaterialTheme.typography.labelLarge,
                color = extras.textTertiary,
            )
            Spacer(Modifier.height(8.dp))
            SourceSummary(source = source)
            Spacer(Modifier.height(12.dp))
            GhostButton(text = stringResource(R.string.clone_btn_reread), onClick = onRead)
        }
    }
}

/**
 * 源标签快照摘要。
 */
@Composable
private fun SourceSummary(source: com.nfctool.app.core.nfc.TagCloner.CloneSource) {
    val e = extras
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SummaryRow(stringResource(R.string.clone_label_uid), source.uidHex)
        SummaryRow(stringResource(R.string.clone_label_card), cardTypeLabel(source.cardTypeKey))
        if (source.techKeys.isNotEmpty()) {
            SummaryRow(stringResource(R.string.clone_label_tech), techLabels(source.techKeys))
        }
        SummaryRow(
            stringResource(R.string.clone_label_records),
            stringResource(R.string.clone_records_value, source.recordCount),
        )
        SummaryRow(
            stringResource(R.string.clone_label_size),
            stringResource(R.string.clone_size_value, source.byteCount),
        )
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    val e = extras
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = e.textTertiary,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = com.nfctool.app.ui.theme.MonoText.body,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
        )
    }
}

/**
 * 第 2 步：写入目标标签。
 */
@Composable
private fun TargetStep(
    clone: CloneState,
    lock: Boolean,
    onLockChange: () -> Unit,
    onUnlockChange: () -> Unit,
    onWrite: () -> Unit,
    onFinish: () -> Unit,
) {
    val e = extras
    GlassCard {
        Text(
            text = stringResource(R.string.clone_step_target),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        // 等待贴卡时给「正在等你」的提示，其余时候说明下一步该做什么
        Text(
            text = stringResource(
                if (clone.phase == ClonePhase.WRITING_TARGET) {
                    R.string.clone_write_hint
                } else {
                    R.string.clone_ready_hint
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = e.textTertiary,
        )
        Spacer(Modifier.height(12.dp))

        // 「写入后设为只读」—— 不可逆，勾选时弹二次确认
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(e.glassSurface)
                .border(BorderStroke(1.dp, e.glassBorder), RoundedCornerShape(14.dp))
                .clickable { if (lock) onUnlockChange() else onLockChange() }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Lock,
                contentDescription = null,
                tint = if (lock) StatusWarn else e.textTertiary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.clone_lock_switch),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.clone_lock_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusWarn,
                )
            }
            Switch(
                checked = lock,
                onCheckedChange = { if (it) onLockChange() else onUnlockChange() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = e.accent.primary,
                    checkedTrackColor = e.accent.primary.copy(alpha = 0.35f),
                ),
            )
        }

        Spacer(Modifier.height(14.dp))
        NeonButton(
            // 复制过一张之后，主按钮文案改成「再复制一张」，引导连续复制
            text = stringResource(
                if (clone.copiesDone > 0) {
                    R.string.clone_btn_again
                } else {
                    R.string.clone_btn_write
                },
            ),
            icon = {
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = null,
                    tint = Color(0xFF06080F),
                    modifier = Modifier.size(18.dp),
                )
            },
            onClick = onWrite,
            modifier = Modifier.fillMaxWidth(),
        )

        if (clone.copiesDone > 0) {
            Spacer(Modifier.height(10.dp))
            StatusBadge(
                text = stringResource(R.string.clone_copies, clone.copiesDone),
                color = StatusOk,
            )
        }

        clone.message?.let { msg ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = if (clone.lastSucceeded == true) StatusOk else StatusWarn,
            )
        }

        Spacer(Modifier.height(12.dp))
        GhostButton(text = stringResource(R.string.clone_btn_finish), onClick = onFinish)
    }
}

/**
 * 复制能力的边界说明。
 *
 * 必须如实告诉用户：UID 与密钥区复制不了。
 * 这类工具最容易出现的信任问题，就是用户以为"能复制门禁卡"结果失败了。
 */
@Composable
private fun LimitsCard() {
    val e = extras
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(e.glassSurface)
            .border(BorderStroke(1.dp, e.glassBorder), shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Lock,
                contentDescription = null,
                tint = StatusWarn,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.clone_limits_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Text(
            text = stringResource(R.string.clone_limits_uid),
            style = MaterialTheme.typography.bodySmall,
            color = e.textTertiary,
        )
        Text(
            text = stringResource(R.string.clone_limits_key),
            style = MaterialTheme.typography.bodySmall,
            color = e.textTertiary,
        )
        Text(
            text = stringResource(R.string.clone_limits_type),
            style = MaterialTheme.typography.bodySmall,
            color = e.textTertiary,
        )
    }
}

/**
 * 卡片类型枚举名 → 本地化展示名。
 *
 * 存的是枚举名（`CardType.valueOf`），取不到（老数据 / 未知值）就原样显示，
 * 不会退化成空白。
 */
@Composable
private fun cardTypeLabel(key: String): String {
    val type = runCatching { CardType.valueOf(key) }.getOrNull()
    return if (type != null) stringResource(type.displayNameRes) else key
}

/**
 * 技术枚举名列表 → 本地化展示名（用「 · 」连接）。
 */
@Composable
private fun techLabels(keys: List<String>): String {
    // 不能用 joinToString 的 lambda —— 它不是 @Composable 上下文，调不了 stringResource
    val parts = ArrayList<String>(keys.size)
    for (key in keys) {
        val tech = runCatching { NfcTech.valueOf(key) }.getOrNull()
        parts += if (tech != null) stringResource(tech.displayNameRes) else key
    }
    return parts.joinToString(" · ")
}

/**
 * 「复制 / 克隆标签」入口卡片。
 *
 * 写入页与读取页共用同一个入口，避免同一个功能在两个地方长得不一样：
 *  - 写入页：当作一种特殊的写入方式，放在目录最上面
 *  - 读取页：读完一张卡后，直接把刚扫到的这张当作源标签
 */
@Composable
fun CloneEntryCard(onOpen: () -> Unit) {
    val e = extras
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                // ⚠️ 渐变至少要 2 个颜色：Compose 的 validateColorStops 在
                // **绘制**阶段才会校验，只给 1 个颜色组合期不报错、一渲染就
                // 抛 IllegalArgumentException（表现为进页面直接闪退）。
                Brush.verticalGradient(
                    listOf(
                        e.accent.primary.copy(alpha = 0.18f),
                        e.accent.primary.copy(alpha = 0.05f),
                    )
                )
            )
            .border(BorderStroke(1.dp, e.glassBorderAccent), shape)
            .clickable { onOpen() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(e.accent.primary.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.ContentCopy,
                contentDescription = null,
                tint = e.accent.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.clone_entry_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.clone_entry_summary),
                style = MaterialTheme.typography.bodySmall,
                color = extras.textTertiary,
            )
        }
        ChevronIcon()
    }
}

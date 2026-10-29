package com.nfctool.app.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nfctool.app.R
import com.nfctool.app.core.nfc.Hex
import com.nfctool.app.core.nfc.NdefManager
import com.nfctool.app.core.nfc.NfcTech
import com.nfctool.app.core.nfc.TagOverview
import com.nfctool.app.ui.MainViewModel
import com.nfctool.app.ui.components.DataRow
import com.nfctool.app.ui.components.GhostButton
import com.nfctool.app.ui.components.GlassCard
import com.nfctool.app.ui.components.RadarPulse
import com.nfctool.app.ui.components.SectionHeader
import com.nfctool.app.ui.components.StatusBadge
import com.nfctool.app.ui.components.TechChip
import com.nfctool.app.ui.components.ThinDivider
import com.nfctool.app.ui.components.ambientGlow
import com.nfctool.app.ui.theme.MonoText
import com.nfctool.app.ui.theme.StatusError
import com.nfctool.app.ui.theme.StatusInfo
import com.nfctool.app.ui.theme.StatusOk
import com.nfctool.app.ui.theme.StatusWarn
import com.nfctool.app.ui.theme.extras
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.nfctool.app.core.nfc.MifareBackup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.nfctool.app.ui.components.AppAlertDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.material.icons.rounded.Delete

/**
 * 读取页：主扫描界面 + 卡片详情。
 */
@Composable
fun ReadPage(
    vm: MainViewModel,
    onOpenClone: () -> Unit,
) {
    val status by vm.nfcStatus.collectAsStateWithLifecycle()
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val appContext = LocalContext.current.applicationContext

    // 进入读取页开启扫描态，离开时关闭
    LaunchedEffect(Unit) {
        vm.startScanning()
        vm.refreshStatus()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .ambientGlow(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 48.dp, bottom = 110.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ReadHeader(
                adapterPresent = status.adapterPresent,
                enabled = status.enabled,
                antenna = status.antennaPosition,
                onOpenSettings = vm::openNfcSettings,
            )
        }

        item {
            ScanHero(
                scanning = ui.scanning,
                busy = ui.busy,
                hasTag = ui.overview != null,
                onToggleScan = {
                    if (ui.overview != null) {
                        // 清除当前标签，回到扫描态等待下一张
                        vm.clearSession()
                        vm.startScanning()
                    } else {
                        vm.showToast(appContext.getString(R.string.read_hero_idle_title))
                    }
                },
            )
        }

        if (!status.adapterPresent) {
            item { WarningCard(stringResource(R.string.read_warn_no_hw)) }
        } else if (!status.enabled) {
            item {
                WarningCard(stringResource(R.string.read_warn_nfc_off)) {
                    vm.openNfcSettings()
                }
            }
        } else if (!status.tagIntentAllowed) {
            item {
                WarningCard(stringResource(R.string.read_warn_intent_blocked)) {
                    vm.openDefaultAppsSettings()
                }
            }
        }

        ui.error?.let { err ->
            item { WarningCard(err, isError = true) }
        }

        val overview = ui.overview
        if (overview != null) {
            item {
                SectionHeader(
                    stringResource(R.string.read_section_tag_info),
                    trailing = stringResource(overview.cardType.categoryRes),
                )
            }
            item { TagIdentityCard(overview, vm) }
            item { TechSupportCard(overview) }
            // NDEF 数据：必须区分「不支持 / 为空 / 读取失败 / 正常」四种情况，
            // 否则用户看到的是一片空白，不知道到底发生了什么。
            when (overview.ndefState) {
                NdefManager.NdefState.UNSUPPORTED -> {
                    item { SectionHeader(stringResource(R.string.read_section_ndef), trailing = stringResource(R.string.read_section_ndef_unsupported)) }
                    item { InfoNote(stringResource(R.string.read_ndef_unsupported_note)) }
                }

                NdefManager.NdefState.EMPTY -> {
                    item { SectionHeader(stringResource(R.string.read_section_ndef), trailing = stringResource(R.string.read_section_ndef_empty)) }
                    item {
                        InfoNote(stringResource(R.string.read_ndef_empty_note))
                    }
                }

                NdefManager.NdefState.ERROR -> {
                    item { SectionHeader(stringResource(R.string.read_section_ndef), trailing = stringResource(R.string.read_section_ndef_error)) }
                    item { WarningCard(overview.ndefError ?: stringResource(R.string.read_ndef_read_failed), isError = true) }
                }

                NdefManager.NdefState.OK -> {
                    val records = overview.ndefRecords.orEmpty()
                    item {
                        SectionHeader(
                            stringResource(R.string.read_section_ndef),
                            trailing = stringResource(R.string.read_ndef_records_count, records.size, overview.ndefCapacity),
                        )
                    }
                    if (records.isEmpty()) {
                        item { InfoNote(stringResource(R.string.read_ndef_empty_msg)) }
                    } else {
                        item { NdefRecordsCard(records) }
                    }
                }
            }
            item {
                SectionHeader(
                    stringResource(R.string.read_section_capability),
                    trailing = status.antennaPosition.ifEmpty { stringResource(R.string.settings_antenna_unknown) },
                )
            }
            item { DeviceCapabilityCard(vm) }
            // 块级数据：MIFARE / Ultralight 的原始数据读取入口
            if (overview.mifareBlocks > 0 || overview.ultralightPages > 0) {
                item { SectionHeader(stringResource(R.string.read_section_block), trailing = stringResource(R.string.read_section_block_trailing)) }
                item { BlockDataCard(vm) }
            }
            // 整卡备份：Classic 走扇区（要密钥），Ultralight / NTAG 走页（无需密钥），
            // 两种共用同一张卡片，内部按卡片类型分流
            item { MifareBackupCard(vm) }
            item { ExportCard(vm) }
            // 读完一张卡之后，最自然的下一步就是「把它复制到另一张」
            item { CloneEntryCard(onOpen = onOpenClone) }
            // 有 NDEF 内容时，可以把第一条记录存成模板
            if (overview.ndefState == NdefManager.NdefState.OK &&
                !overview.ndefRecords.isNullOrEmpty()
            ) {
                item { TemplateSaveCard(vm) }
            }
        } else if (status.canScan) {
            item { EmptyHintCard() }
        }
    }
}

/**
 * 中性提示条（不是警告，只是说明当前状态）。
 */
@Composable
private fun InfoNote(message: String) {
    val e = extras
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(e.accent.primary.copy(alpha = 0.08f))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Info,
            contentDescription = null,
            tint = e.accent.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ReadHeader(
    adapterPresent: Boolean,
    enabled: Boolean,
    antenna: String,
    onOpenSettings: () -> Unit,
) {
    val e = extras
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = when {
                    !adapterPresent -> stringResource(R.string.read_status_no_adapter)
                    !enabled -> stringResource(R.string.read_status_disabled)
                    else -> stringResource(R.string.read_status_ready) + " · " + antenna.ifEmpty { stringResource(R.string.read_cap_enabled) }
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) e.textTertiary else StatusWarn,
            )
        }
        StatusBadge(
            text = if (!adapterPresent) stringResource(R.string.read_status_unavailable) else if (enabled) stringResource(R.string.read_status_online) else stringResource(R.string.read_status_offline),
            color = when {
                !adapterPresent -> StatusError
                enabled -> StatusOk
                else -> StatusWarn
            },
        )
    }
}

@Composable
private fun ScanHero(
    scanning: Boolean,
    busy: Boolean,
    hasTag: Boolean,
    onToggleScan: () -> Unit,
) {
    val e = extras
    GlassCard(accent = true) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(150.dp)
                    .padding(bottom = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                RadarPulse(
                    modifier = Modifier.fillMaxSize(),
                    active = !busy && !hasTag,
                    color = e.accent.primary,
                )
                Box(
                    Modifier.size(66.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Nfc,
                        contentDescription = null,
                        modifier = Modifier.size(46.dp),
                        tint = if (busy) e.accent.secondary else e.accent.primary,
                    )
                }
            }

            Text(
                text = when {
                    busy -> stringResource(R.string.read_hero_busy_title)
                    hasTag -> stringResource(R.string.read_hero_hastag_title)
                    else -> stringResource(R.string.read_hero_idle_title)
                },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = when {
                    busy -> stringResource(R.string.read_hero_busy_desc)
                    hasTag -> stringResource(R.string.read_hero_hastag_desc)
                    else -> stringResource(R.string.read_hero_idle_desc)
                },
                style = MaterialTheme.typography.bodySmall,
                color = e.textTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            if (hasTag) {
                Spacer(Modifier.height(12.dp))
                GhostButton(
                    text = stringResource(R.string.read_hero_rescan),
                    onClick = onToggleScan,
                )
            }
        }
    }
}

@Composable
private fun WarningCard(
    message: String,
    isError: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val e = extras
    val color = if (isError) StatusError else StatusWarn
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(color.copy(alpha = 0.10f))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isError) Icons.Rounded.Warning else Icons.Rounded.Info,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelMedium,
                color = color,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onAction() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun EmptyHintCard() {
    val e = extras
    GlassCard {
        Column {
            Text(
                stringResource(R.string.read_waiting),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            HintRow(stringResource(R.string.tech_ndef_name), stringResource(R.string.read_hint_ndef))
            HintRow(stringResource(R.string.tech_mifare_classic_name), stringResource(R.string.read_hint_mifare))
            HintRow(stringResource(R.string.tech_mifare_ultralight_name), stringResource(R.string.read_hint_ultralight))
            HintRow(stringResource(R.string.card_iso15693_name), stringResource(R.string.read_hint_iso15693))
            HintRow(stringResource(R.string.tech_iso_dep_name), stringResource(R.string.read_hint_isodep))
            HintRow(stringResource(R.string.read_hint_raw), stringResource(R.string.read_hint_raw))
        }
    }
}

@Composable
private fun HintRow(title: String, desc: String) {
    val e = extras
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
    ) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(e.accent.primary.copy(alpha = 0.6f))
        )
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(120.dp),
        )
        Text(
            desc,
            style = MaterialTheme.typography.bodySmall,
            color = e.textTertiary,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TagIdentityCard(overview: TagOverview, vm: MainViewModel) {
    val e = extras
    val clipboard = LocalClipboardManager.current
    val appContext = LocalContext.current.applicationContext

    GlassCard(accent = true) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    e.accent.primary.copy(alpha = 0.28f),
                                    e.accent.secondary.copy(alpha = 0.18f),
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Bolt,
                        contentDescription = null,
                        tint = e.accent.primary,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(overview.cardType.displayNameRes),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(overview.cardType.summaryRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = e.textTertiary,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            ThinDivider()
            Spacer(Modifier.height(10.dp))

            DataRow(
                label = stringResource(R.string.read_label_uid),
                value = Hex.formatUid(overview.uid),
                mono = true,
                valueColor = e.accent.primary,
                onClick = {
                    clipboard.setText(AnnotatedString(Hex.formatUid(overview.uid)))
                    vm.showToast(appContext.getString(R.string.toast_copied_uid))
                },
            )
            DataRow(
                label = stringResource(R.string.read_label_uid_len),
                value = stringResource(R.string.unit_bytes, overview.uid.size),
                mono = true,
            )
            DataRow(
                label = stringResource(R.string.read_label_uid_reversed),
                value = Hex.formatUid(Hex.reversed(overview.uid)),
                mono = true,
            )
            if (overview.mifareSize > 0) {
                DataRow(label = stringResource(R.string.read_label_capacity), value = "${overview.mifareSize / 1024} KB", mono = true)
                DataRow(label = stringResource(R.string.read_label_sectors_blocks), value = "${overview.mifareSectors} / ${overview.mifareBlocks}", mono = true)
            }
            if (overview.ultralightPages > 0) {
                DataRow(label = stringResource(R.string.read_label_total_pages), value = "${overview.ultralightPages}", mono = true)
                DataRow(label = stringResource(R.string.read_label_user_pages), value = "${overview.ultralightPages - 4}", mono = true)
            }
            if (overview.ndefCapacity > 0) {
                DataRow(label = stringResource(R.string.read_label_ndef_capacity), value = stringResource(R.string.unit_bytes, overview.ndefCapacity), mono = true)
                DataRow(
                    label = stringResource(R.string.read_label_writable),
                    value = if (overview.ndefWritable) stringResource(R.string.read_writable_yes) else stringResource(R.string.read_writable_no_locked),
                    valueColor = if (overview.ndefWritable) StatusOk else StatusWarn,
                )
            }
            if (overview.isoDepProtocol >= 0) {
                DataRow(
                    label = stringResource(R.string.read_label_protocol),
                    value = when (overview.isoDepProtocol) {
                        0 -> "T=0"
                        1 -> "T=1"
                        2 -> "T=CL"
                        3 -> "T=10"
                        4 -> "T=11"
                        else -> "T=${overview.isoDepProtocol}"
                    },
                    mono = true,
                )
            }
            overview.historicalBytes?.let { hb ->
                DataRow(label = stringResource(R.string.read_label_hist_bytes), value = Hex.toHex(hb), mono = true)
            }
        }
    }
}

@Composable
private fun TechSupportCard(overview: TagOverview) {
    val e = extras
    GlassCard {
        Column {
            Text(
                stringResource(R.string.read_label_supported_tech),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                overview.techs.forEach { tech ->
                    TechChip(
                        text = stringResource(tech.displayNameRes),
                        color = when {
                            tech.readable && tech.writable -> e.accent.primary
                            tech.readable -> StatusInfo
                            else -> StatusWarn
                        },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            ThinDivider()
            Spacer(Modifier.height(8.dp))
            overview.techs.forEach { tech ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(
                        stringResource(tech.displayNameRes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(tech.descriptionRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = e.textTertiary,
                    )
                }
            }
            // 系统原始 techList：与上面整理过的清单对照，能发现未识别的技术
            if (overview.rawTechList.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                ThinDivider()
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.read_label_raw_tech_list),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                overview.rawTechList.forEach { raw ->
                    Text(
                        raw,
                        style = MonoText.caption,
                        color = e.textTertiary,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun NdefRecordsCard(records: List<com.nfctool.app.core.nfc.NdefRecordInfo>) {
    val e = extras
    GlassCard {
        Column {
            records.forEachIndexed { index, rec ->
                if (index > 0) {
                    Spacer(Modifier.height(10.dp))
                    ThinDivider()
                    Spacer(Modifier.height(10.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(e.accent.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            rec.typeLetter.toString(),
                            style = MonoText.caption,
                            color = e.accent.primary,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            rec.typeName,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "${rec.payloadSize} 字节",
                            style = MonoText.caption,
                            color = e.textTertiary,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                rec.text?.let {
                    PayloadBlock(stringResource(R.string.read_label_text), it)
                }
                rec.uri?.let {
                    PayloadBlock(stringResource(R.string.read_label_uri), it)
                }
                rec.mimeType?.let {
                    PayloadBlock(stringResource(R.string.read_label_mime), it)
                }
                // 细分类型解析出的结构化字段
                rec.details.forEach { detail ->
                    PayloadBlock(detail.label, detail.value)
                }
                // 协议层原始信息：排查问题时需要看到 TNF 与 type 字节
                Spacer(Modifier.height(6.dp))
                ThinDivider()
                Spacer(Modifier.height(6.dp))
                PayloadBlock(stringResource(R.string.read_label_tnf), tnfLabel(rec.tnf))
                if (rec.typeBytes.isNotEmpty()) {
                    PayloadBlock(stringResource(R.string.read_label_type_bytes), Hex.toHex(rec.typeBytes))
                }
                rec.id?.let {
                    PayloadBlock(stringResource(R.string.read_label_record_id), Hex.toHex(it))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.read_label_raw_data),
                    style = MaterialTheme.typography.labelSmall,
                    color = e.textTertiary,
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.22f))
                        .padding(10.dp)
                ) {
                    Text(
                        Hex.toHex(rec.raw),
                        style = MonoText.caption,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun PayloadBlock(label: String, value: String) {
    val e = extras
    Column(Modifier.padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = e.textTertiary)
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** TNF 编号的可读名称 */
private fun tnfLabel(tnf: Int): String = when (tnf) {
    0 -> "Empty (0)"
    1 -> "Well-known (1)"
    2 -> "MIME Media (2)"
    3 -> "Absolute URI (3)"
    4 -> "External (4)"
    5 -> "Unknown (5)"
    6 -> "Unchanged (6)"
    else -> "TNF $tnf"
}

/**
 * 块级数据卡片：读取并展示 MIFARE Classic 全卡 dump / Ultralight 用户页。
 *
 * 这两类数据是标签的原始内存内容，NDEF 之外经常还存着门禁卡号、
 * 余额、配置等，必须能看到才算「信息页完整」。
 */
@Composable
private fun BlockDataCard(vm: MainViewModel) {
    val e = extras
    val ui by vm.uiState.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(true) }

    GlassCard {
        Column {
            Text(
                stringResource(R.string.read_block_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.read_block_desc),
                style = MaterialTheme.typography.bodySmall,
                color = e.textTertiary,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (ui.mifareDump.isNotEmpty() || ui.ultralightPages.isNotEmpty()) {
                    GhostButton(
                        text = stringResource(R.string.action_clear),
                        modifier = Modifier.weight(1f),
                        onClick = { vm.clearBlockData() },
                    )
                }
                if (ui.overview?.mifareBlocks ?: 0 > 0) {
                    GhostButton(
                        text = stringResource(R.string.read_block_mifare, 0),
                        modifier = Modifier.weight(1f),
                    ) { vm.dumpMifare { success, msg -> ok = success; message = msg } }
                }
                if ((ui.overview?.ultralightPages ?: 0) > 0) {
                    GhostButton(
                        text = stringResource(R.string.read_block_ultralight, 0),
                        modifier = Modifier.weight(1f),
                    ) { vm.dumpUltralight { success, msg -> ok = success; message = msg } }
                }
            }

            // MIFARE 块
            if (ui.mifareDump.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                ThinDivider()
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.read_block_mifare, ui.mifareDump.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = e.accent.primary,
                )
                Spacer(Modifier.height(6.dp))
                ui.mifareDump.entries.sortedBy { it.key }.forEach { (block, data) ->
                    HexBlockRow(stringResource(R.string.read_block_block_n, block), data)
                }
            }

            // Ultralight 页
            if (ui.ultralightPages.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                ThinDivider()
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.read_block_ultralight, ui.ultralightPages.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = e.accent.primary,
                )
                Spacer(Modifier.height(6.dp))
                ui.ultralightPages.forEachIndexed { index, data ->
                    HexBlockRow(stringResource(R.string.read_block_page_n, index + 4), data)
                }
            }
        }
    }

    message?.let {
        com.nfctool.app.ui.components.AppAlertDialog(
            title = if (ok) stringResource(R.string.read_dump_done_title) else stringResource(R.string.read_dump_fail_title),
            message = it,
            onDismiss = { message = null },
        )
    }
}

/**
 * 单行十六进制块数据。
 */
@Composable
private fun HexBlockRow(label: String, data: ByteArray) {
    val e = extras
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MonoText.caption,
            color = e.textTertiary,
            modifier = Modifier.width(64.dp),
        )
        Text(
            text = Hex.toHex(data),
            style = MonoText.caption,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 导出数据卡片。
 */
@Composable
private fun ExportCard(vm: MainViewModel) {
    val e = extras
    var message by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(true) }

    GlassCard {
        Column {
            Text(
                stringResource(R.string.read_export_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.read_export_desc),
                style = MaterialTheme.typography.bodySmall,
                color = e.textTertiary,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton(
                    text = stringResource(R.string.read_export_json),
                    modifier = Modifier.weight(1f),
                    onClick = { vm.exportJson { success, msg -> ok = success; message = msg } },
                )
                GhostButton(
                    text = stringResource(R.string.read_export_text),
                    modifier = Modifier.weight(1f),
                    onClick = { vm.exportText { success, msg -> ok = success; message = msg } },
                )
            }
        }
    }

    message?.let {
        com.nfctool.app.ui.components.AppAlertDialog(
            title = if (ok) stringResource(R.string.read_export_done_title) else stringResource(R.string.read_export_fail_title),
            message = it,
            onDismiss = { message = null },
        )
    }
}

@Composable
private fun DeviceCapabilityCard(vm: MainViewModel) {
    val status by vm.nfcStatus.collectAsStateWithLifecycle()
    val e = extras
    GlassCard {
        Column {
            CapabilityRow(
                stringResource(R.string.read_cap_secure_nfc),
                when {
                    !status.secureNfcSupported -> stringResource(R.string.read_cap_not_supported)
                    status.secureNfcEnabled -> stringResource(R.string.read_cap_enabled)
                    else -> stringResource(R.string.read_cap_supported_off)
                },
                status.secureNfcSupported && status.secureNfcEnabled,
            )
            CapabilityRow(
                stringResource(R.string.read_cap_observe),
                when {
                    !status.observeModeSupported -> stringResource(R.string.read_cap_not_supported)
                    status.observeModeEnabled -> stringResource(R.string.read_cap_enabled)
                    else -> stringResource(R.string.read_cap_can_enable)
                },
                status.observeModeEnabled,
            )
            CapabilityRow(
                stringResource(R.string.read_cap_reader_option),
                when {
                    !status.readerOptionSupported -> stringResource(R.string.read_cap_not_supported)
                    status.readerOptionEnabled -> stringResource(R.string.read_cap_enabled)
                    else -> stringResource(R.string.read_cap_can_enable)
                },
                status.readerOptionEnabled,
            )
            CapabilityRow(stringResource(R.string.read_cap_antenna), status.antennaPosition.ifEmpty { stringResource(R.string.settings_antenna_unknown) }, status.antennaPosition.isNotEmpty())
            CapabilityRow(
                stringResource(R.string.read_cap_tag_intent),
                if (status.tagIntentAllowed) stringResource(R.string.read_cap_allowed) else stringResource(R.string.read_cap_blocked),
                status.tagIntentAllowed,
            )
        }
    }
}

@Composable
private fun CapabilityRow(label: String, value: String, ok: Boolean) {
    val e = extras
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = e.textTertiary,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (ok) StatusOk else e.textTertiary)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) StatusOk else e.textTertiary,
            )
        }
    }
}


/**
 * MIFARE Classic 整卡备份 / 恢复。
 *
 * 备份的对象是**当前已读取的这张卡**（走 ViewModel 里保存的 session）；
 * 恢复的对象是**下一张贴上来的卡**（走 launchWrite 的挂起写入）——
 * 这两个方向不一样，UI 上要让用户看得明白：备份是"现在"，恢复是"下一张"。
 */
private data class RestoreConfirm(
    val title: String,
    val body: String,
    val onConfirm: () -> Unit,
)

/**
 * 整卡备份 / 恢复（MIFARE Classic 与 Ultralight / NTAG 共用）。
 *
 * 两种卡片的结构完全不同，但入口合并成一张卡片更省事：
 * - **Classic**：按扇区读写，需要密钥认证
 * - **Ultralight / NTAG**：按页读写，没有扇区概念、不需要密钥
 *
 * 两个方向也不一样：备份的对象是**当前已读取的这张卡**（走 ViewModel 的 session），
 * 恢复的对象是**下一张贴上来的卡**（走 launchWrite 挂起写入）。
 */
@Composable
private fun MifareBackupCard(vm: MainViewModel) {
    val e = extras
    val appContext = LocalContext.current.applicationContext
    val ui by vm.uiState.collectAsStateWithLifecycle()
    // Classic 有块数、Ultralight 有页数，用哪个有值来分流
    val isClassic = (ui.overview?.mifareBlocks ?: 0) > 0
    var message by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(true) }
    var keyInput by remember { mutableStateOf("") }
    var confirmDialog by remember { mutableStateOf<RestoreConfirm?>(null) }
    val savedKeys by vm.mifareKeys.collectAsStateWithLifecycle()

    val pickBackupFile = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?.toString(Charsets.UTF_8)
        }.getOrNull()
        if (text.isNullOrBlank()) {
            ok = false
            message = appContext.getString(R.string.err_backup_empty)
            return@rememberLauncherForActivityResult
        }
        // 两种备份的字段差异很大（扇区/块 vs 页），靠 kind 标记分流解析
        when (MifareBackup.detectKind(text)) {
            MifareBackup.KIND_CLASSIC -> MifareBackup.fromJson(text)
                .onSuccess { backup ->
                    confirmDialog = RestoreConfirm(
                        title = appContext.getString(
                            R.string.mifare_restore_confirm_title,
                            backup.restorableBlocks,
                        ),
                        body = appContext.getString(R.string.mifare_restore_confirm_msg),
                    ) {
                        vm.buildMifareKeyCandidates(keyInput)
                            .onSuccess { keys ->
                                vm.restoreMifare(backup, keys) { success, msg ->
                                    ok = success
                                    message = msg
                                }
                            }
                            .onFailure { ok = false; message = it.message }
                    }
                }
                .onFailure { ok = false; message = it.message }

            MifareBackup.KIND_ULTRALIGHT -> MifareBackup.fromUltralightJson(text)
                .onSuccess { backup ->
                    confirmDialog = RestoreConfirm(
                        title = appContext.getString(
                            R.string.ul_restore_confirm_title,
                            backup.restorablePages,
                        ),
                        body = appContext.getString(R.string.ul_restore_confirm_msg),
                    ) {
                        vm.restoreUltralight(backup) { success, msg ->
                            ok = success
                            message = msg
                        }
                    }
                }
                .onFailure { ok = false; message = it.message }

            else -> {
                ok = false
                message = appContext.getString(R.string.err_backup_empty)
            }
        }
    }

    GlassCard {
        Column {
            Text(
                stringResource(R.string.mifare_backup_section),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (isClassic) {
                    stringResource(R.string.mifare_backup_desc)
                } else {
                    stringResource(R.string.ul_backup_desc)
                },
                style = MaterialTheme.typography.bodySmall,
                color = e.textTertiary,
            )
            Spacer(Modifier.height(12.dp))

            // 密钥只有 Classic 需要 —— Ultralight / NTAG 没有扇区认证
            if (isClassic) {
                NfcTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    placeholder = stringResource(R.string.mifare_backup_key_ph),
                    mono = true,
                )
                Spacer(Modifier.height(8.dp))
                GhostButton(
                    text = stringResource(R.string.mifare_keybook_save),
                    onClick = {
                        vm.saveMifareKey(keyInput)
                            .onSuccess { saved ->
                                // 回填规范化后的大写 hex，让用户看到存进去的是什么
                                keyInput = saved
                                ok = true
                                message = appContext.getString(R.string.mifare_keybook_saved)
                            }
                            .onFailure { ok = false; message = it.message }
                    },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.mifare_backup_key_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = e.textTertiary,
                )
                Spacer(Modifier.height(12.dp))

                // 密钥本：备份 / 恢复时按这里的顺序依次尝试
                Text(
                    stringResource(R.string.mifare_keybook_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                if (savedKeys.isEmpty()) {
                    Text(
                        stringResource(R.string.mifare_keybook_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = e.textTertiary,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        savedKeys.forEach { hex ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(e.glassSurface)
                                    .border(
                                        BorderStroke(1.dp, e.glassBorder),
                                        RoundedCornerShape(10.dp),
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = hex,
                                    style = MonoText.body,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { vm.removeMifareKey(hex) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Delete,
                                        contentDescription = stringResource(R.string.action_delete),
                                        tint = StatusError,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            Text(
                if (isClassic) {
                    stringResource(R.string.mifare_backup_note)
                } else {
                    stringResource(R.string.ul_backup_note)
                },
                style = MaterialTheme.typography.bodySmall,
                color = StatusWarn,
            )
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton(
                    text = stringResource(R.string.mifare_backup_btn),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (isClassic) {
                            vm.buildMifareKeyCandidates(keyInput)
                                .onSuccess { keys ->
                                    vm.backupMifare(keys) { success, msg ->
                                        ok = success
                                        message = msg
                                    }
                                }
                                .onFailure { ok = false; message = it.message }
                        } else {
                            vm.backupUltralight { success, msg ->
                                ok = success
                                message = msg
                            }
                        }
                    },
                )
                GhostButton(
                    text = stringResource(R.string.mifare_restore_btn),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (isClassic) {
                            vm.buildMifareKeyCandidates(keyInput)
                                .onSuccess { pickBackupFile.launch(arrayOf("application/json", "text/plain")) }
                                .onFailure { ok = false; message = it.message }
                        } else {
                            pickBackupFile.launch(arrayOf("application/json", "text/plain"))
                        }
                    },
                )
            }
        }
    }

    // 恢复会覆盖目标卡内容，必须明确确认一次
    confirmDialog?.let { confirm ->
        AlertDialog(
            onDismissRequest = { confirmDialog = null },
            title = { Text(confirm.title) },
            text = { Text(confirm.body) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDialog = null
                    confirm.onConfirm()
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDialog = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    message?.let {
        AppAlertDialog(
            title = if (ok) {
                stringResource(R.string.read_export_done_title)
            } else {
                stringResource(R.string.read_export_fail_title)
            },
            message = it,
            onDismiss = { message = null },
        )
    }
}

/**
 * 读取页的「存为模板」入口。
 *
 * 读到的卡 → 存成模板 → 以后在写入页点一下就填好表单写到别的卡。
 * 只取**第一条**记录：多记录标签的语义通常是"主内容 + 辅助记录"，
 * 全存进来反而会让模板变得难以理解。
 */
@Composable
private fun TemplateSaveCard(vm: MainViewModel) {
    val e = extras
    var name by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(true) }
    // 点击回调不是 @Composable 上下文，文案必须在组合期先取好
    val savedText = stringResource(R.string.template_saved)
    val failTitle = stringResource(R.string.read_export_fail_title)

    GlassCard {
        Column {
            Text(
                stringResource(R.string.template_section),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.read_template_desc),
                style = MaterialTheme.typography.bodySmall,
                color = e.textTertiary,
            )
            Spacer(Modifier.height(12.dp))
            NfcTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = stringResource(R.string.template_name_ph),
            )
            Spacer(Modifier.height(8.dp))
            GhostButton(
                text = stringResource(R.string.template_save),
                onClick = {
                    vm.saveOverviewAsTemplate(name)
                        .onSuccess {
                            name = ""
                            ok = true
                            message = savedText
                        }
                        .onFailure { ok = false; message = it.message }
                },
            )
        }
    }

    message?.let {
        AppAlertDialog(
            title = if (ok) savedText else failTitle,
            message = it,
            onDismiss = { message = null },
        )
    }
}

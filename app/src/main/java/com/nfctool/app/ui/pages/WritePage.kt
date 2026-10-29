package com.nfctool.app.ui.pages

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContactPage
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PinDrop
import androidx.compose.material.icons.rounded.QrCode
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nfctool.app.core.nfc.WriteEntry
import com.nfctool.app.core.nfc.WriteGroup
import com.nfctool.app.ui.MainViewModel
import com.nfctool.app.ui.components.GlassCard
import com.nfctool.app.ui.components.SectionHeader
import com.nfctool.app.ui.theme.extras
import androidx.compose.ui.res.stringResource
import com.nfctool.app.R
import com.nfctool.app.ui.components.AppAlertDialog
import com.nfctool.app.ui.theme.StatusError
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nfctool.app.ui.components.GhostButton

/**
 * 写入页：只展示写入项目录。
 *
 * 每个条目点进去是各自的表单页（见 [WriteDetailPage]），
 * 这样类型再多也不会把这一页堆满。
 */
@Composable
fun WritePage(
    vm: MainViewModel,
    onOpenEntry: (String) -> Unit,
    onOpenClone: () -> Unit,
) {
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val overview = ui.overview

    // 目录始终展示全部条目：写入不再依赖先读一次标签，
    // 标签技术不匹配时由写入环节给出明确错误（如「该标签不支持 MIFARE Classic」）。
    val available = WriteEntry.entries

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 48.dp, bottom = 110.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text(
                    stringResource(R.string.tab_write),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    if (overview != null) {
                        stringResource(R.string.write_subtitle_last, stringResource(overview.cardType.displayNameRes))
                    } else {
                        stringResource(R.string.write_subtitle_idle)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = extras.textTertiary,
                )
            }
        }

        // 复制到另一张标签 —— 与下面按类型写入并列，但性质不同（先读后写），
        // 所以单独做成一张高亮卡片放在目录最上面
        item { CloneEntryCard(onOpen = onOpenClone) }

        // 我的模板（收藏夹）：常用的内容一键填好表单
        item { TemplateCard(vm) }

        if (overview == null) {
            item {
                GlassCard {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(R.string.write_no_preread_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.write_no_preread_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = extras.textTertiary,
                        )
                    }
                }
            }
        } else if (!overview.ndefWritable && overview.ndefCapacity == 0) {
            item {
                GlassCard {
                    Column {
                        Text(
                            stringResource(R.string.write_no_ndef_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.write_no_ndef_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = extras.textTertiary,
                        )
                    }
                }
            }
        }

        WriteGroup.entries.forEach { group ->
            val items = available.filter { it.group == group }
            if (items.isEmpty()) return@forEach

            item(key = "header_${group.name}") {
                SectionHeader(
                    title = stringResource(group.labelRes),
                    trailing = stringResource(R.string.write_group_count, items.size),
                )
            }
            items.forEach { entry ->
                item(key = entry.id) {
                    WriteEntryCard(entry = entry, onClick = { onOpenEntry(entry.id) })
                }
            }
        }
    }
}

/**
 * 目录里的一个写入项。
 */
@Composable
private fun WriteEntryCard(
    entry: WriteEntry,
    onClick: () -> Unit,
) {
    val e = extras
    GlassCard(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(e.accent.primary.copy(alpha = 0.12f))
                    .border(
                        BorderStroke(1.dp, e.accent.primary.copy(alpha = 0.28f)),
                        RoundedCornerShape(12.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = iconOf(entry),
                    contentDescription = stringResource(entry.titleRes),
                    tint = e.accent.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(entry.titleRes),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(entry.summaryRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = e.textTertiary,
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = e.textTertiary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * 每个写入项的图标。
 */
private fun iconOf(entry: WriteEntry): ImageVector = when (entry) {
    WriteEntry.TEXT -> Icons.Rounded.TextFields
    WriteEntry.URI -> Icons.Rounded.Link
    WriteEntry.GEO -> Icons.Rounded.PinDrop
    WriteEntry.WIFI -> Icons.Rounded.Wifi
    WriteEntry.VCARD -> Icons.Rounded.ContactPage
    WriteEntry.MECARD -> Icons.Rounded.ContactPage
    WriteEntry.VCALENDAR -> Icons.Rounded.CalendarMonth
    WriteEntry.SMART_POSTER -> Icons.Rounded.Campaign
    WriteEntry.IMAGE -> Icons.Rounded.Image
    WriteEntry.QRCODE -> Icons.Rounded.QrCode
    WriteEntry.ANDROID_APP -> Icons.Rounded.Android
    WriteEntry.HANDOVER -> Icons.Rounded.SwapHoriz
    WriteEntry.BLUETOOTH_OOB -> Icons.Rounded.Bluetooth
    WriteEntry.AID -> Icons.Rounded.Apps
    WriteEntry.MIME -> Icons.Rounded.DataObject
    WriteEntry.EXTERNAL -> Icons.Rounded.DataObject
    WriteEntry.MIFARE_CLASSIC -> Icons.Rounded.Tag
    WriteEntry.MIFARE_ULTRALIGHT -> Icons.Rounded.Tag
    WriteEntry.ISO15693 -> Icons.Rounded.Tag
    WriteEntry.DESFIRE -> Icons.Rounded.Tag
    WriteEntry.FORMAT -> Icons.Rounded.Delete
    WriteEntry.RESTORE_MIFARE -> Icons.Rounded.Restore
}

/**
 * 我的模板（收藏夹）。
 *
 * 数据来自两处：从别的应用分享进来的内容、读到的标签。
 * 点一条模板 → [MainViewModel.applyTemplate] 把内容放回 pendingPrefill →
 * `NfctoolAppRoot` 的 LaunchedEffect 自动跳到对应的写入详情页并预填表单，
 * 所以这里**不需要处理导航**。
 */
@Composable
private fun TemplateCard(vm: MainViewModel) {
    val e = extras
    val templates by vm.templates.collectAsStateWithLifecycle()
    // 有分享内容进来时，顺手提供「存为模板」
    val prefill by vm.pendingPrefill.collectAsStateWithLifecycle()
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
                stringResource(R.string.template_desc),
                style = MaterialTheme.typography.bodySmall,
                color = e.textTertiary,
            )
            Spacer(Modifier.height(12.dp))

            if (templates.isEmpty()) {
                Text(
                    stringResource(R.string.template_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = e.textTertiary,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    templates.forEach { template ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(e.glassSurface)
                                .border(
                                    BorderStroke(1.dp, e.glassBorder),
                                    RoundedCornerShape(12.dp),
                                )
                                .clickable { vm.applyTemplate(template) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    template.name,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    template.summary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = e.textTertiary,
                                    maxLines = 1,
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { vm.removeTemplate(template.id) },
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

            if (prefill != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.template_from_share),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                NfcTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = stringResource(R.string.template_name_ph),
                )
                Spacer(Modifier.height(8.dp))
                GhostButton(
                    text = stringResource(R.string.template_save),
                    onClick = {
                        vm.savePrefillAsTemplate(name)
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
    }

    message?.let {
        AppAlertDialog(
            title = if (ok) savedText else failTitle,
            message = it,
            onDismiss = { message = null },
        )
    }
}

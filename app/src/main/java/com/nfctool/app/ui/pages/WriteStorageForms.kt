package com.nfctool.app.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nfctool.app.core.nfc.Hex
import com.nfctool.app.ui.MainViewModel
import com.nfctool.app.ui.components.GhostButton
import com.nfctool.app.ui.components.NeonButton
import com.nfctool.app.ui.theme.extras
import androidx.compose.ui.res.stringResource
import com.nfctool.app.R
import androidx.compose.ui.platform.LocalContext

/**
 * MIFARE Classic 块级写入表单。
 */
@Composable
internal fun MifareClassicForm(
    report: (Boolean, String) -> Unit,
    vm: MainViewModel,
    sectors: Int,
    blocks: Int,
) {
    val appContext = LocalContext.current.applicationContext
    var keyHex by remember { mutableStateOf("FFFFFFFFFFFF") }
    var blockIndex by remember { mutableStateOf("0") }
    var blockData by remember { mutableStateOf("") }
    var confirmRestore by remember { mutableStateOf(false) }

    FormBlock {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 没读过标签时扇区数未知，写入时按实际标签检测
            MiniStat(stringResource(R.string.write_ms_sectors), if (sectors > 0) "$sectors" else stringResource(R.string.action_detecting))
            MiniStat(stringResource(R.string.write_ms_blocks), if (blocks > 0) "$blocks" else stringResource(R.string.action_detecting))
        }
        Spacer(Modifier.height(12.dp))

        FieldLabel(stringResource(R.string.write_ms_key_a))
        Spacer(Modifier.height(6.dp))
        NfcTextField(
            value = keyHex,
            onValueChange = { keyHex = it },
            placeholder = "FFFFFFFFFFFF",
            singleLine = true,
            mono = true,
        )

        Spacer(Modifier.height(10.dp))
        FieldLabel(stringResource(R.string.write_ms_block_no))
        Spacer(Modifier.height(6.dp))
        NfcTextField(
            value = blockIndex,
            onValueChange = { blockIndex = it.filter(Char::isDigit) },
            placeholder = "0",
            singleLine = true,
            keyboardType = KeyboardType.Number,
        )

        Spacer(Modifier.height(10.dp))
        FieldLabel(stringResource(R.string.write_ms_block_data))
        Spacer(Modifier.height(6.dp))
        NfcTextField(
            value = blockData,
            onValueChange = { blockData = it },
            placeholder = "00 01 02 ...",
            singleLine = false,
            mono = true,
        )

        Spacer(Modifier.height(12.dp))
        NeonButton(
            text = stringResource(R.string.write_ms_write_block),
            modifier = Modifier.fillMaxWidth(),
            enabled = blockIndex.isNotBlank() && blockData.isNotBlank(),
        ) {
            val idx = blockIndex.toIntOrNull() ?: 0
            val data = Hex.parseLenient(blockData)
            if (data.size != 16) {
                report(false, appContext.getString(R.string.write_ms_block_size_err, data.size))
            } else {
                val key = runCatching { Hex.parseLenient(keyHex) }.getOrNull()
                vm.writeMifareBlock(idx, data, key, report)
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton(
                text = stringResource(R.string.write_ms_read_all),
                modifier = Modifier.weight(1f),
            ) { vm.dumpMifare(report) }
            GhostButton(
                text = stringResource(R.string.write_ms_restore),
                modifier = Modifier.weight(1f),
                onClick = { confirmRestore = true },
            )
        }
    }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text(stringResource(R.string.write_ms_restore_title), style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    stringResource(R.string.write_ms_restore_msg),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.restoreMifare(report)
                    confirmRestore = false
                }) { Text(stringResource(R.string.action_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = false }) { Text(stringResource(R.string.action_cancel)) }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }
}

/**
 * MIFARE Ultralight 页级写入表单。
 */
@Composable
internal fun UltralightForm(
    report: (Boolean, String) -> Unit,
    vm: MainViewModel,
    pages: Int,
) {
    val appContext = LocalContext.current.applicationContext
    var pageIndex by remember { mutableStateOf("4") }
    var pageData by remember { mutableStateOf("") }

    FormBlock {
        // 没读过标签时页数未知，写入时按实际标签检测
        MiniStat(stringResource(R.string.write_ms_user_pages), if (pages > 0) "$pages" else stringResource(R.string.action_detecting))
        Spacer(Modifier.height(12.dp))

        FieldLabel(stringResource(R.string.write_ms_page_no))
        Spacer(Modifier.height(6.dp))
        NfcTextField(
            value = pageIndex,
            onValueChange = { pageIndex = it.filter(Char::isDigit) },
            placeholder = "4",
            singleLine = true,
            keyboardType = KeyboardType.Number,
        )

        Spacer(Modifier.height(10.dp))
        FieldLabel(stringResource(R.string.write_ms_page_data))
        Spacer(Modifier.height(6.dp))
        NfcTextField(
            value = pageData,
            onValueChange = { pageData = it },
            placeholder = "41 42 43 44",
            singleLine = false,
            mono = true,
        )

        Spacer(Modifier.height(12.dp))
        NeonButton(
            text = stringResource(R.string.write_ms_write_page),
            modifier = Modifier.fillMaxWidth(),
            enabled = pageIndex.isNotBlank() && pageData.isNotBlank(),
        ) {
            val idx = pageIndex.toIntOrNull() ?: 4
            val data = Hex.parseLenient(pageData)
            if (data.size != 4) {
                report(false, appContext.getString(R.string.write_ms_page_size_err, data.size))
            } else {
                vm.writeUltralightPage(idx, data, report)
            }
        }

        Spacer(Modifier.height(10.dp))
        GhostButton(
            text = stringResource(R.string.write_ms_read_all_pages),
            modifier = Modifier.fillMaxWidth(),
        ) { vm.dumpUltralight(report) }
    }
}

/**
 * ISO 15693 块级写入表单。
 */
@Composable
internal fun Iso15693Form(
    report: (Boolean, String) -> Unit,
    vm: MainViewModel,
) {
    val appContext = LocalContext.current.applicationContext
    var blockIndex by remember { mutableStateOf("0") }
    var blockData by remember { mutableStateOf("") }

    FormBlock(hint = stringResource(R.string.write_iso_hint)) {
        FieldLabel(stringResource(R.string.write_ms_block_no))
        Spacer(Modifier.height(6.dp))
        NfcTextField(
            value = blockIndex,
            onValueChange = { blockIndex = it.filter(Char::isDigit) },
            placeholder = "0",
            singleLine = true,
            keyboardType = KeyboardType.Number,
        )

        Spacer(Modifier.height(10.dp))
        FieldLabel(stringResource(R.string.write_iso_block_data))
        Spacer(Modifier.height(6.dp))
        NfcTextField(
            value = blockData,
            onValueChange = { blockData = it },
            placeholder = "00 00 00 00",
            singleLine = false,
            mono = true,
        )

        Spacer(Modifier.height(12.dp))
        NeonButton(
            text = stringResource(R.string.write_ms_write_block),
            modifier = Modifier.fillMaxWidth(),
            enabled = blockIndex.isNotBlank() && blockData.isNotBlank(),
        ) {
            val idx = blockIndex.toIntOrNull() ?: 0
            val data = Hex.parseLenient(blockData)
            if (data.size != 4) {
                report(false, appContext.getString(R.string.write_iso_block_size_err, data.size))
            } else {
                vm.writeIso15693Block(idx, data, report)
            }
        }
    }
}

/**
 * DESFire 高级操作表单（复用控制台面板）。
 */
@Composable
internal fun DesfireEntryForm(
    report: (Boolean, String) -> Unit,
    vm: MainViewModel,
) {
    Column {
        Text(
            stringResource(R.string.write_desfire_hint),
            style = MaterialTheme.typography.bodySmall,
            color = extras.textTertiary,
        )
        Spacer(Modifier.height(10.dp))
        DesfireCard(vm, report)
    }
}

/**
 * 格式化标签表单。
 */
@Composable
internal fun FormatForm(
    report: (Boolean, String) -> Unit,
    vm: MainViewModel,
) {
    var confirm by remember { mutableStateOf(false) }

    FormBlock {
        Text(
            stringResource(R.string.write_format_blank),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.write_format_desc),
            style = MaterialTheme.typography.bodySmall,
            color = extras.textTertiary,
        )
        Spacer(Modifier.height(12.dp))
        NeonButton(
            text = stringResource(R.string.write_format_button),
            modifier = Modifier.fillMaxWidth(),
            danger = true,
            onClick = { confirm = true },
        )
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.write_format_confirm_title), style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    stringResource(R.string.write_format_confirm_msg),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.formatTag(report)
                    confirm = false
                }) { Text(stringResource(R.string.action_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text("取消") }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }
}

/**
 * 恢复 MIFARE 出厂设置表单。
 */
@Composable
internal fun RestoreMifareForm(
    report: (Boolean, String) -> Unit,
    vm: MainViewModel,
) {
    var confirm by remember { mutableStateOf(false) }

    FormBlock {
        Text(
            stringResource(R.string.write_restore_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.write_restore_desc),
            style = MaterialTheme.typography.bodySmall,
            color = extras.textTertiary,
        )
        Spacer(Modifier.height(12.dp))
        NeonButton(
            text = stringResource(R.string.write_restore_title),
            modifier = Modifier.fillMaxWidth(),
            danger = true,
            onClick = { confirm = true },
        )
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.write_ms_restore_title), style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    stringResource(R.string.write_ms_restore_msg),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.restoreMifare(report)
                    confirm = false
                }) { Text(stringResource(R.string.action_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text("取消") }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }
}

/**
 * 表单里的紧凑统计值。
 */
@Composable
internal fun MiniStat(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = extras.textTertiary,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

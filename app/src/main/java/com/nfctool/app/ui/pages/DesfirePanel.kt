package com.nfctool.app.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nfctool.app.core.nfc.Hex
import com.nfctool.app.ui.MainViewModel
import com.nfctool.app.ui.components.DataRow
import com.nfctool.app.ui.components.GlassCard
import com.nfctool.app.ui.components.GhostButton
import com.nfctool.app.ui.components.ThinDivider
import com.nfctool.app.ui.theme.MonoText
import com.nfctool.app.ui.theme.extras
import androidx.compose.ui.res.stringResource
import com.nfctool.app.R
import androidx.compose.ui.platform.LocalContext

/**
 * DESFire 卡片信息与文件操作面板。
 */
@Composable
fun DesfireCard(
    vm: MainViewModel,
    onReport: (Boolean, String) -> Unit,
) {
    val info by vm.desfireInfo.collectAsStateWithLifecycle()
    val appContext = LocalContext.current.applicationContext
    var fileId by remember { mutableStateOf("01") }

    GlassCard {
        Column {
            Text(
                stringResource(R.string.write_desfire_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.write_desfire_desc),
                style = MaterialTheme.typography.bodySmall,
                color = extras.textTertiary,
            )
            Spacer(Modifier.height(12.dp))

            GhostButton(
                text = stringResource(R.string.write_desfire_read),
                modifier = Modifier.fillMaxWidth(),
            ) { vm.readDesfireInfo(onReport) }

            info?.let { card ->
                Spacer(Modifier.height(14.dp))
                ThinDivider()
                Spacer(Modifier.height(10.dp))

                DataRow(
                    label = stringResource(R.string.write_desfire_version),
                    value = card.version?.displayName ?: stringResource(R.string.write_desfire_read_failed),
                    mono = true,
                )
                card.keySettings?.let {
                    DataRow(label = stringResource(R.string.write_desfire_keys), value = it.displayName, mono = true)
                }
                DataRow(
                    label = stringResource(R.string.write_desfire_file_count),
                    value = "${card.fileIds.size}",
                    mono = true,
                )

                if (card.fileIds.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.write_desfire_file_id),
                        style = MaterialTheme.typography.labelMedium,
                        color = extras.textTertiary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        card.fileIds.joinToString("  ") { "0x${Hex.toHexNoSep(it)}" },
                        style = MonoText.body,
                        color = extras.accent.primary,
                    )
                }

                Spacer(Modifier.height(14.dp))
                ThinDivider()
                Spacer(Modifier.height(12.dp))

                Text(
                    stringResource(R.string.write_desfire_read_content),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                NfcTextField(
                    value = fileId,
                    onValueChange = { fileId = it.filter { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } },
                    placeholder = "01",
                    singleLine = true,
                    mono = true,
                    keyboardType = KeyboardType.Text,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton(
                        text = stringResource(R.string.write_desfire_read_file),
                        modifier = Modifier.weight(1f),
                        enabled = fileId.isNotBlank(),
                    ) {
                        val parsed = parseFileId(fileId)
                        if (parsed == null) {
                            onReport(false, appContext.getString(R.string.write_desfire_fileid_err))
                        } else {
                            vm.readDesfireFile(parsed, onReport)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 解析文件 ID：接受 "01" / "0x01" / "1" 形式，统一按十六进制解释。
 */
private fun parseFileId(input: String): Int? {
    val cleaned = input.removePrefix("0x").removePrefix("0X")
    if (cleaned.isEmpty()) return null
    return cleaned.toIntOrNull(16)
}

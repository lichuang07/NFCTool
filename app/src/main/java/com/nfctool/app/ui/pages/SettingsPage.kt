package com.nfctool.app.ui.pages

import androidx.activity.compose.LocalActivity
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
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nfctool.app.ui.MainViewModel
import com.nfctool.app.ui.components.GlassCard
import com.nfctool.app.ui.components.SectionHeader
import com.nfctool.app.ui.components.TechChip
import com.nfctool.app.ui.components.ThinDivider
import com.nfctool.app.ui.theme.MonoText
import com.nfctool.app.ui.theme.extras
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import com.nfctool.app.R
import com.nfctool.app.core.i18n.LocalePref
import android.os.Build
import com.nfctool.app.ui.theme.accentLabelRes

/**
 * 设置页。
 */
@Composable
fun SettingsPage(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val language by vm.language.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val appContext = LocalContext.current.applicationContext
    val status by vm.nfcStatus.collectAsStateWithLifecycle()
    val hce by vm.hceStatus.collectAsStateWithLifecycle()
    val ui by vm.uiState.collectAsStateWithLifecycle()
    var showAbout by remember { mutableStateOf(false) }
    val appVersion = remember {
        runCatching {
            val pm = appContext.packageManager
            val info = pm.getPackageInfo(appContext.packageName, 0)
            info.versionName ?: "—"
        }.getOrDefault("—")
    }

    LaunchedEffect(Unit) {
        vm.refreshHceStatus()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 48.dp, bottom = 110.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(
                stringResource(R.string.tab_settings),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        // ---- 外观 ----
        item { SectionHeader(stringResource(R.string.settings_section_appearance), trailing = stringResource(R.string.settings_section_appearance_trailing)) }
        item {
            GlassCard {
                Column {
                    Text(
                        stringResource(R.string.settings_theme_mode),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "dark" to stringResource(R.string.settings_theme_dark),
                            "light" to stringResource(R.string.settings_theme_light),
                            "system" to stringResource(R.string.settings_theme_system),
                        ).forEach { (key, label) ->
                            TechChip(
                                text = label,
                                selected = settings.themeMode == key,
                                onClick = { vm.updateSettings { it.copy(themeMode = key) } },
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.settings_accent),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.nfctool.app.ui.theme.AccentPalettes.forEach { palette ->
                            AccentSwatch(
                                palette = palette,
                                selected = settings.accentColor == palette.name,
                                onClick = { vm.updateSettings { it.copy(accentColor = palette.name) } },
                            )
                        }
                    }
                }
            }
        }


        // ---- 语言 ----
        item { SectionHeader(stringResource(R.string.settings_language)) }
        item {
            GlassCard {
                Column {
                    Text(
                        stringResource(R.string.settings_language),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            LocalePref.SYSTEM to R.string.settings_language_system,
                            LocalePref.CHINESE to R.string.settings_language_zh,
                            LocalePref.ENGLISH to R.string.settings_language_en,
                        ).forEach { (tag, labelRes) ->
                            TechChip(
                                text = stringResource(labelRes),
                                selected = language == tag,
                                onClick = { vm.setLanguage(tag, activity) },
                            )
                        }
                    }
                }
            }
        }

        // ---- 扫描行为 ----
        item { SectionHeader(stringResource(R.string.settings_section_scan)) }
        item {
            GlassCard {
                Column {
                    SettingSwitch(
                        title = stringResource(R.string.settings_vibrate),
                        desc = stringResource(R.string.settings_vibrate_desc),
                        checked = settings.vibrateOnScan,
                    ) { v -> vm.updateSettings { it.copy(vibrateOnScan = v) } }
                    ThinDivider()
                    SettingSwitch(
                        title = stringResource(R.string.settings_skip_ndef),
                        desc = stringResource(R.string.settings_skip_ndef_desc),
                        checked = settings.skipNdefCheck,
                    ) { v -> vm.updateSettings { it.copy(skipNdefCheck = v) } }
                    ThinDivider()
                    SettingSwitch(
                        title = stringResource(R.string.settings_poll_nfc_b),
                        desc = stringResource(R.string.settings_poll_nfc_b_desc),
                        checked = settings.includeNfcB,
                    ) { v -> vm.updateSettings { it.copy(includeNfcB = v) } }
                    ThinDivider()
                    SettingSwitch(
                        title = stringResource(R.string.settings_background_scan),
                        desc = stringResource(R.string.settings_background_scan_desc),
                        checked = ui.backgroundScanActive,
                    ) { v ->
                        if (v) vm.startBackgroundScan() else vm.stopBackgroundScan()
                    }
                    ThinDivider()
                    SettingSwitch(
                        title = stringResource(R.string.settings_export_secrets),
                        desc = stringResource(R.string.settings_export_secrets_desc),
                        checked = settings.exportSecrets,
                    ) { v -> vm.updateSettings { it.copy(exportSecrets = v) } }
                }
            }
        }

        // ---- 主机卡模拟 ----
        item { SectionHeader(stringResource(R.string.settings_section_hce), trailing = stringResource(R.string.settings_section_hce_trailing)) }
        item {
            GlassCard {
                Column {
                    if (!hce.deviceSupported) {
                        WarningRow(stringResource(R.string.settings_hce_unsupported))
                    } else {
                        InfoRow(stringResource(R.string.settings_hce_supported), stringResource(R.string.action_yes))
                        InfoRow(
                            stringResource(R.string.settings_hce_aids),
                            if (hce.aidsRegistered.isEmpty()) stringResource(R.string.settings_hce_aids_none)
                            else hce.aidsRegistered.joinToString(", "),
                        )
                        InfoRow(
                            stringResource(R.string.settings_hce_default),
                            if (hce.isDefaultForAid) stringResource(R.string.settings_hce_default_this)
                            else stringResource(R.string.settings_hce_default_other),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    ThinDivider()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.settings_hce_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = extras.textTertiary,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        com.nfctool.app.ui.components.GhostButton(
                            text = stringResource(R.string.settings_hce_open),
                            modifier = Modifier.weight(1f),
                            enabled = hce.deviceSupported,
                            onClick = vm::openHceSettings,
                        )
                        com.nfctool.app.ui.components.GhostButton(
                            text = stringResource(R.string.settings_hce_refresh),
                            modifier = Modifier.weight(1f),
                            onClick = vm::refreshHceStatus,
                        )
                    }
                }
            }
        }

        // ---- 设备信息 ----
        item { SectionHeader(stringResource(R.string.settings_section_device)) }
        item {
            GlassCard {
                Column {
                    InfoRow(
                        stringResource(R.string.settings_nfc_adapter),
                        if (status.adapterPresent) stringResource(R.string.settings_nfc_present) else stringResource(R.string.settings_nfc_absent),
                    )
                    InfoRow(
                        stringResource(R.string.settings_nfc_state),
                        if (status.enabled) stringResource(R.string.settings_nfc_on) else stringResource(R.string.settings_nfc_off),
                    )
                    InfoRow(stringResource(R.string.settings_secure_nfc), if (status.secureNfcSupported) stringResource(R.string.settings_supported) else stringResource(R.string.settings_unsupported))
                    InfoRow(stringResource(R.string.settings_observe_mode), if (status.observeModeSupported) stringResource(R.string.settings_supported) else stringResource(R.string.settings_unsupported))
                    InfoRow(stringResource(R.string.settings_reader_option), if (status.readerOptionSupported) stringResource(R.string.settings_supported) else stringResource(R.string.settings_unsupported))
                    InfoRow(stringResource(R.string.settings_antenna), status.antennaPosition.ifEmpty { stringResource(R.string.settings_antenna_unknown) })
                    InfoRow(stringResource(R.string.settings_target_sdk), "${Build.VERSION.SDK_INT} (Android ${Build.VERSION.RELEASE})")
                    InfoRow(stringResource(R.string.settings_min_sdk), stringResource(R.string.settings_min_sdk_value))
                }
            }
        }

        // ---- 关于 ----
        item { SectionHeader(stringResource(R.string.settings_section_about)) }
        item {
            GlassCard {
                Column {
                    InfoRow(stringResource(R.string.settings_version), appVersion)
                    InfoRow(stringResource(R.string.settings_build), stringResource(R.string.settings_build_value))
                    Spacer(Modifier.height(14.dp))
                    ThinDivider()
                    Spacer(Modifier.height(14.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.settings_supported_tags),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                stringResource(R.string.settings_supported_tags_value),
                                style = MaterialTheme.typography.bodySmall,
                                color = extras.textTertiary,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    com.nfctool.app.ui.components.GhostButton(
                        text = stringResource(R.string.settings_about_button),
                        modifier = Modifier.fillMaxWidth(),
                        icon = { Icon(Icons.Rounded.Info, null, Modifier.size(16.dp)) },
                        onClick = { showAbout = true },
                    )
                }
            }
        }
    }

    if (showAbout) {
        com.nfctool.app.ui.components.AppAlertDialog(
            title = stringResource(R.string.settings_about_title),
            message = stringResource(R.string.settings_about_body),
            onDismiss = { showAbout = false },
        )
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    desc: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val e = extras
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = e.textTertiary,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF06080F),
                checkedTrackColor = e.accent.primary,
                checkedBorderColor = e.accent.primary,
                uncheckedThumbColor = e.textTertiary,
                uncheckedTrackColor = e.glassSurface,
                uncheckedBorderColor = e.glassBorder,
            ),
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val e = extras
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = e.textTertiary)
        Text(value, style = MonoText.body, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun WarningRow(message: String) {
    val e = extras
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Warning,
            contentDescription = null,
            tint = com.nfctool.app.ui.theme.StatusWarn,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = com.nfctool.app.ui.theme.StatusWarn,
        )
    }
}

@Composable
private fun AccentSwatch(
    palette: com.nfctool.app.ui.theme.AccentPalette,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(shape)
            .clickable { onClick() }
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(palette.primary)
                .then(
                    if (selected) {
                        Modifier.border(
                            androidx.compose.foundation.BorderStroke(2.dp, palette.primary),
                            RoundedCornerShape(10.dp),
                        )
                    } else Modifier
                )
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(accentLabelRes(palette.name)),
            style = MonoText.caption,
            color = if (selected) palette.primary else extras.textTertiary,
        )
    }
}



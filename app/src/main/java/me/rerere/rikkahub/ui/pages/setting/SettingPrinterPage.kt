package me.rerere.rikkahub.ui.pages.setting

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import me.rerere.rikkahub.common.ui.components.InlineSpinner
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupItem
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import org.koin.androidx.compose.koinViewModel

/**
 * 打印机服务页：当前设备与连接状态、添加/更换设备（BLE 扫描）、重新连接自检、
 * 测试打印、移除设备与自动打印开关（默认关闭，开启时明确告知影响）。
 */
@Composable
fun SettingPrinterPage(vm: PrinterVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val scanResults by vm.scanResults.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val resultMessage by vm.resultMessage.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var showAutoPrintDialog by remember { mutableStateOf(false) }
    var showEnergyDialog by remember { mutableStateOf(false) }

    // 蓝牙权限：扫描前检查，缺失则申请，授权后自动继续扫描
    var pendingScan by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (pendingScan) {
            pendingScan = false
            if (vm.missingPermissions().isEmpty()) {
                vm.scan()
            } else {
                vm.notifyPermissionDenied()
            }
        }
    }

    val startScan: () -> Unit = {
        val missing = vm.missingPermissions()
        if (missing.isNotEmpty()) {
            pendingScan = true
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            vm.scan()
        }
    }

    LaunchedEffect(resultMessage) {
        resultMessage?.let {
            snackbarHostState.showSnackbar(it)
            vm.clearResult()
        }
    }

    Scaffold(
        topBar = {
            OneUITopAppBar(
                title = stringResource(R.string.printer_page_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = { BackButton() }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding
        ) {
            // 当前设备与操作
            item {
                val config = settings.printerConfig
                SettingsGroup(title = stringResource(R.string.printer_page_device_group)) {
                    if (config.isConfigured) {
                        SettingGroupItem(
                            title = config.name.ifBlank { stringResource(R.string.printer_page_device_unnamed) },
                            subtitle = config.protocol.displayName,
                            icon = { Icon(Icons.Rounded.Print, null, modifier = Modifier.size(20.dp)) }
                        )
                        // 打印浓度（下次打印生效，无需重连；调整后可直接测试打印对比效果）
                        SettingGroupItem(
                            title = stringResource(R.string.printer_page_energy),
                            subtitle = stringResource(R.string.printer_page_energy_desc),
                            trailing = {
                                Select(
                                    options = energyOptions(config.energyPercent),
                                    selectedOption = selectedEnergyOption(config.energyPercent),
                                    onOptionSelected = { option ->
                                        if (option.isCustomEntry) {
                                            showEnergyDialog = true
                                        } else {
                                            vm.setEnergyPercent(option.percent)
                                        }
                                    },
                                    optionToString = { it.label }
                                )
                            }
                        )
                        SettingGroupItem(
                            title = stringResource(R.string.printer_page_reconnect),
                            subtitle = stringResource(R.string.printer_page_reconnect_desc),
                            icon = { Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(20.dp)) },
                            onClick = { if (busy == null) vm.probePrinter() }
                        )
                        SettingGroupItem(
                            title = stringResource(R.string.printer_page_test_print),
                            subtitle = stringResource(R.string.printer_page_test_print_desc),
                            icon = { Icon(Icons.Rounded.Print, null, modifier = Modifier.size(20.dp)) },
                            onClick = { if (busy == null) vm.testPrint() }
                        )
                        SettingGroupItem(
                            title = stringResource(R.string.printer_page_remove_device),
                            subtitle = stringResource(R.string.printer_page_remove_device_desc),
                            icon = { Icon(Icons.Rounded.RemoveCircleOutline, null, modifier = Modifier.size(20.dp)) },
                            onClick = { vm.removeDevice() }
                        )
                    } else {
                        SettingGroupItem(
                            title = stringResource(R.string.printer_page_device_none),
                            subtitle = stringResource(R.string.printer_page_device_none_desc),
                            icon = { Icon(Icons.Rounded.Print, null, modifier = Modifier.size(20.dp)) }
                        )
                    }
                    // 自动打印开关（默认关闭；开启时弹说明确认）
                    SettingGroupItem(
                        title = stringResource(R.string.printer_page_auto_print),
                        subtitle = stringResource(R.string.printer_page_auto_print_desc),
                        trailing = {
                            HapticSwitch(
                                checked = config.autoPrint,
                                onCheckedChange = { enabled ->
                                    if (enabled) {
                                        showAutoPrintDialog = true
                                    } else {
                                        vm.setAutoPrint(false)
                                    }
                                }
                            )
                        }
                    )
                }
            }

            // 扫描与添加设备
            item {
                SettingsGroup(title = stringResource(R.string.printer_page_scan_group)) {
                    SettingGroupItem(
                        title = stringResource(R.string.printer_page_scan),
                        subtitle = stringResource(R.string.printer_page_scan_desc),
                        icon = {
                            if (scanning) {
                                InlineSpinner(size = 20.dp, strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Rounded.BluetoothSearching, null, modifier = Modifier.size(20.dp))
                            }
                        },
                        onClick = { if (!scanning && busy == null) startScan() }
                    )
                }
            }

            // 扫描结果（疑似打印机优先）
            if (scanning || scanResults.isNotEmpty()) {
                item {
                    SettingsGroup(title = stringResource(R.string.printer_page_scan_results_group)) {
                        if (scanResults.isEmpty()) {
                            SettingGroupItem(
                                title = stringResource(R.string.printer_page_no_devices),
                                subtitle = stringResource(R.string.printer_page_no_devices_desc)
                            )
                        } else {
                            scanResults.forEach { printer ->
                                SettingGroupItem(
                                    title = printer.name,
                                    subtitle = stringResource(
                                        R.string.printer_page_scan_result_fmt,
                                        printer.address,
                                        printer.rssi
                                    ),
                                    icon = { Icon(Icons.Rounded.Add, null, modifier = Modifier.size(20.dp)) },
                                    onClick = { if (busy == null) vm.saveDevice(printer) }
                                )
                            }
                        }
                    }
                }
            }

            // 帮助说明
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.printer_page_help_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.padding(top = 4.dp))
                        Text(
                            text = stringResource(R.string.printer_page_help_content),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    // 自动打印开启确认：明确告知 AI 调用打印工具后将直接执行
    if (showAutoPrintDialog) {
        AlertDialog(
            onDismissRequest = { showAutoPrintDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            title = { Text(stringResource(R.string.printer_page_auto_print_confirm_title)) },
            text = { Text(stringResource(R.string.printer_page_auto_print_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAutoPrintDialog = false
                        vm.setAutoPrint(true)
                    }
                ) {
                    Text(
                        stringResource(R.string.printer_page_auto_print_confirm_enable),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showAutoPrintDialog = false }) {
                    Text(
                        stringResource(R.string.printer_page_auto_print_confirm_cancel),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }

    // 自定义浓度滑杆（30%..100%，默认 55%）
    if (showEnergyDialog) {
        var sliderPercent by remember { mutableStateOf(settings.printerConfig.energyPercent.toFloat()) }
        AlertDialog(
            onDismissRequest = { showEnergyDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            title = { Text(stringResource(R.string.printer_energy_custom_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.printer_energy_custom_value_fmt, sliderPercent.toInt()),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Slider(
                        value = sliderPercent,
                        onValueChange = { sliderPercent = it },
                        valueRange = 30f..100f,
                    )
                    Text(
                        text = stringResource(R.string.printer_energy_custom_hint),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEnergyDialog = false
                        vm.setEnergyPercent(sliderPercent.toInt())
                    }
                ) {
                    Text(
                        stringResource(R.string.printer_energy_dialog_confirm),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showEnergyDialog = false }) {
                    Text(
                        stringResource(R.string.printer_energy_dialog_cancel),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }
}

/** 浓度选项：浅/中/深三个预置 + "自定义"入口（当前值为非预置时显示当前百分比） */
private data class EnergyOption(val label: String, val percent: Int, val isCustomEntry: Boolean = false)

private val ENERGY_PRESETS = listOf(50, 65, 75)

@Composable
private fun energyOptions(currentPercent: Int): List<EnergyOption> = buildList {
    add(EnergyOption(stringResource(R.string.printer_energy_light), 50))
    add(EnergyOption(stringResource(R.string.printer_energy_medium), 65))
    add(EnergyOption(stringResource(R.string.printer_energy_dark), 75))
    if (currentPercent in ENERGY_PRESETS) {
        add(EnergyOption(stringResource(R.string.printer_energy_custom_more), currentPercent, isCustomEntry = true))
    } else {
        add(
            EnergyOption(
                stringResource(R.string.printer_energy_custom_fmt, currentPercent),
                currentPercent,
                isCustomEntry = true
            )
        )
    }
}

@Composable
private fun selectedEnergyOption(currentPercent: Int): EnergyOption {
    val options = energyOptions(currentPercent)
    // 预置值选中对应项；自定义值选中"自定义"入口
    return options.firstOrNull { it.percent == currentPercent && !it.isCustomEntry }
        ?: options.last()
}

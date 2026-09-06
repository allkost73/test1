package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.model.DiagnosticTab
import com.example.model.ElmConnectionState
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.DtcScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.ParametersScreen
import com.example.ui.screens.TerminalScreen
import com.example.ui.screens.TuningScreen
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.GaugeGreen
import com.example.ui.theme.GaugeRed
import com.example.ui.theme.GaugeYellow
import com.example.ui.theme.SitrakOrange
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.viewmodel.SitrakDiagnosticViewModel

@Composable
fun SitrakMainScreen(
    viewModel: SitrakDiagnosticViewModel = viewModel()
) {
    val context = LocalContext.current
    val currentTab by viewModel.currentTab.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val telemetry by viewModel.telemetry.collectAsState()
    val activeFaults by viewModel.activeFaults.collectAsState()
    val selectedModuleFilter by viewModel.selectedModuleFilter.collectAsState()
    val truckConfig by viewModel.truckConfig.collectAsState()
    val cylinderCutout by viewModel.cylinderCutout.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val terminalLogs by viewModel.terminalLogs.collectAsState()
    val pairedDevices by viewModel.pairedDevices.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val isDiscovering by viewModel.isDiscovering.collectAsState()
    val selectedProtocol by viewModel.selectedProtocol.collectAsState()
    val isSimulationMode by viewModel.isSimulationMode.collectAsState()
    val savedReports by viewModel.savedReports.collectAsState()
    val statusNotice by viewModel.statusNotice.collectAsState()
    val rpmHistory by viewModel.rpmHistory.collectAsState()
    val boostHistory by viewModel.boostHistory.collectAsState()
    val ecuStates by viewModel.ecuStates.collectAsState()
    val detectedCanBus by viewModel.detectedCanBus.collectAsState()
    val isCanConnected by viewModel.isCanConnected.collectAsState()
    val ignitionDetected by viewModel.ignitionDetected.collectAsState()
    val isDiagnosingEcus by viewModel.isDiagnosingEcus.collectAsState()

    // Bluetooth Permissions Launcher for Android 12+ and Android <= 11
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        viewModel.refreshPairedDevices()
    }

    LaunchedEffect(Unit) {
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasConnect = ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            val hasScan = ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasConnect) permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (!hasScan) permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            val hasFineLocation = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasFineLocation) permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("main_scaffold"),
        containerColor = DarkBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            SitrakTopBar(
                connectionState = connectionState,
                batteryVoltage = telemetry.batteryVoltage,
                onStatusPillClick = { viewModel.selectTab(DiagnosticTab.HISTORY) }
            )
        },
        bottomBar = {
            SitrakBottomNavBar(
                currentTab = currentTab,
                activeFaultCount = activeFaults.size,
                onTabSelected = { viewModel.selectTab(it) }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding()
                )
        ) {
            when (currentTab) {
                DiagnosticTab.DASHBOARD -> DashboardScreen(
                    telemetry = telemetry,
                    activeFaults = activeFaults,
                    truckConfig = truckConfig,
                    onNavigateTab = { viewModel.selectTab(it) },
                    onQuickSaveReport = { viewModel.saveCurrentReport() },
                    ecuStates = ecuStates,
                    isCanConnected = isCanConnected,
                    detectedCanBus = detectedCanBus,
                    isSimulationMode = isSimulationMode
                )

                DiagnosticTab.DTC -> DtcScreen(
                    faults = activeFaults,
                    isScanning = isScanning,
                    selectedModule = selectedModuleFilter,
                    onSelectModule = { viewModel.setModuleFilter(it) },
                    onScanRequested = { viewModel.scanAllModules() },
                    onClearFaultsRequested = { viewModel.clearAllFaultCodes() },
                    ecuStates = ecuStates,
                    detectedCanBus = detectedCanBus,
                    isCanConnected = isCanConnected,
                    ignitionDetected = ignitionDetected,
                    isDiagnosingEcus = isDiagnosingEcus,
                    onDiagnoseEcusRequested = { viewModel.testAndDiagnoseEcus() },
                    isSimulationMode = isSimulationMode
                )

                DiagnosticTab.PARAMETERS -> ParametersScreen(
                    telemetry = telemetry,
                    rpmHistory = rpmHistory,
                    boostHistory = boostHistory
                )

                DiagnosticTab.TUNING -> TuningScreen(
                    truckConfig = truckConfig,
                    activeCylinderCutout = cylinderCutout,
                    onUpdateSpeedLimit = { viewModel.updateSpeedLimit(it) },
                    onUpdateIdleRpm = { viewModel.updateIdleRpm(it) },
                    onTriggerDpfRegen = { viewModel.triggerDpfRegeneration() },
                    onResetAdBlueDerate = { viewModel.resetAdBlueDerate() },
                    onTestCylinderCutout = { viewModel.testCylinderCutout(it) },
                    onUpdateComfortSettings = { buzzer, drl, delay, step, profile ->
                        viewModel.updateComfortSettings(buzzer, drl, delay, step, profile)
                    }
                )

                DiagnosticTab.TERMINAL -> TerminalScreen(
                    terminalLogs = terminalLogs,
                    onSendCommand = { viewModel.sendTerminalCommand(it) },
                    onClearLogs = { viewModel.clearTerminalLogs() }
                )

                DiagnosticTab.HISTORY -> HistoryScreen(
                    connectionState = connectionState,
                    isSimulationMode = isSimulationMode,
                    pairedDevices = pairedDevices,
                    discoveredDevices = discoveredDevices,
                    isDiscovering = isDiscovering,
                    selectedProtocol = selectedProtocol,
                    isBluetoothEnabled = viewModel.isBluetoothEnabled(),
                    savedReports = savedReports,
                    onToggleSimulation = { viewModel.setSimulationMode(it) },
                    onRefreshDevices = { viewModel.refreshPairedDevices() },
                    onStartDiscovery = { viewModel.startDiscovery() },
                    onStopDiscovery = { viewModel.stopDiscovery() },
                    onSelectProtocol = { viewModel.setProtocol(it) },
                    onConnectDevice = { viewModel.connectDevice(it) },
                    onDisconnect = { viewModel.disconnect() },
                    onSaveReport = { viewModel.saveCurrentReport() },
                    onDeleteReport = { viewModel.deleteReport(it) }
                )
            }

            // Notification Banner floating at the top of content
            AnimatedVisibility(
                visible = statusNotice != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(12.dp)
            ) {
                statusNotice?.let { notice ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(DarkSurfaceElevated)
                            .border(1.dp, SitrakOrange, RoundedCornerShape(10.dp))
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = SitrakOrange,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = notice,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = TextPrimary
                            )
                            IconButton(
                                onClick = { viewModel.clearStatusNotice() },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Dismiss",
                                    tint = TextMuted,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SitrakTopBar(
    connectionState: ElmConnectionState,
    batteryVoltage: Float,
    onStatusPillClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSurface)
            .border(width = 0.5.dp, color = DarkBorder)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Brand & App Title
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(SitrakOrange),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "S",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Black,
                            fontSize = 18.sp
                        ),
                        color = Color.Black
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "SITRAK S7H",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                    Text(
                        text = "ELM327 Bluetooth Classic",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = TextMuted
                    )
                }
            }

            // Status Pill & 24V Readout
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 24V Voltage Badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(DarkSurfaceElevated)
                        .border(1.dp, DarkBorder, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.BatteryChargingFull,
                            contentDescription = "24V",
                            tint = if (batteryVoltage > 25f) GaugeGreen else GaugeYellow,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = String.format("%.1fV", batteryVoltage),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            ),
                            color = TextPrimary
                        )
                    }
                }

                // Connection status chip (clickable to jump to History/BT tab)
                val (statusDotColor, statusText) = when (connectionState) {
                    is ElmConnectionState.Connected -> Pair(GaugeGreen, if (connectionState.isSimulation) "Симулятор" else "ELM327")
                    is ElmConnectionState.Connecting -> Pair(SitrakOrange, "Связь...")
                    is ElmConnectionState.Error -> Pair(GaugeRed, "Ошибка")
                    is ElmConnectionState.Disconnected -> Pair(TextMuted, "Откл")
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(DarkSurfaceElevated)
                        .border(1.dp, statusDotColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .clickable(onClick = onStatusPillClick)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .background(statusDotColor, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SitrakBottomNavBar(
    currentTab: DiagnosticTab,
    activeFaultCount: Int,
    onTabSelected: (DiagnosticTab) -> Unit
) {
    NavigationBar(
        containerColor = DarkSurface,
        tonalElevation = 0.dp,
        modifier = Modifier
            .navigationBarsPadding()
            .border(width = 0.5.dp, color = DarkBorder)
    ) {
        val navItems = listOf(
            Triple(DiagnosticTab.DASHBOARD, "Приборы", Icons.Default.Speed),
            Triple(DiagnosticTab.DTC, "Ошибки", Icons.Default.Warning),
            Triple(DiagnosticTab.PARAMETERS, "Датчики", Icons.Default.Analytics),
            Triple(DiagnosticTab.TUNING, "Тюнинг", Icons.Default.Build),
            Triple(DiagnosticTab.TERMINAL, "ELM", Icons.Default.Terminal),
            Triple(DiagnosticTab.HISTORY, "Связь", Icons.Default.Bluetooth)
        )

        navItems.forEach { (tab, label, icon) ->
            val isSelected = currentTab == tab

            NavigationBarItem(
                selected = isSelected,
                onClick = { onTabSelected(tab) },
                icon = {
                    if (tab == DiagnosticTab.DTC && activeFaultCount > 0) {
                        BadgedBox(
                            badge = {
                                Badge(
                                    containerColor = GaugeRed,
                                    contentColor = Color.White
                                ) {
                                    Text(activeFaultCount.toString(), fontSize = 9.sp)
                                }
                            }
                        ) {
                            Icon(imageVector = icon, contentDescription = label)
                        }
                    } else {
                        Icon(imageVector = icon, contentDescription = label)
                    }
                },
                label = {
                    Text(
                        text = label,
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color.Black,
                    selectedTextColor = SitrakOrange,
                    indicatorColor = SitrakOrange,
                    unselectedIconColor = TextMuted,
                    unselectedTextColor = TextMuted
                )
            )
        }
    }
}

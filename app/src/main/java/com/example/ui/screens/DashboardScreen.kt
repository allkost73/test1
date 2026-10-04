package com.example.ui.screens

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ElectricMeter
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.DiagnosticTab
import com.example.model.DrivingMode
import com.example.model.DtcCode
import com.example.model.EcuModuleState
import com.example.model.EcuStatus
import com.example.model.ElmConnectionState
import com.example.model.ElmProtocol
import com.example.model.LiveTelemetry
import com.example.model.TruckConfiguration
import com.example.model.TruckModule
import kotlin.math.abs
import com.example.ui.components.CircularDialGauge
import com.example.ui.components.LinearBarGauge
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

@Composable
fun DashboardScreen(
    telemetry: LiveTelemetry,
    activeFaults: List<DtcCode>,
    truckConfig: TruckConfiguration,
    onNavigateTab: (DiagnosticTab) -> Unit,
    onQuickSaveReport: () -> Unit,
    ecuStates: Map<TruckModule, EcuModuleState> = emptyMap(),
    isCanConnected: Boolean = true,
    detectedCanBus: String? = null,
    isSimulationMode: Boolean = false,
    onSelectDrivingMode: (String) -> Unit = {},
    connectionState: ElmConnectionState = ElmConnectionState.Disconnected,
    onDiagnoseEcusRequested: () -> Unit = {},
    onToggleSimulation: (Boolean) -> Unit = {},
    onSelectProtocol: (ElmProtocol) -> Unit = {},
    onScanCanBus: () -> Unit = {},
    onDisconnect: () -> Unit = {},
    onEmergencyResetCan: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val onlineEcuCount = ecuStates.values.count { it.status == EcuStatus.ONLINE }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("dashboard_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Truck Hero Header Card
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .border(1.dp, DarkBorder, RoundedCornerShape(20.dp))
                    .background(DarkSurfaceElevated)
            ) {
                Column {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.sitrak_hero),
                            contentDescription = "Sitrak S7H Truck",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        // Gradient vignette over hero image
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            Color.Transparent,
                                            DarkSurfaceElevated.copy(alpha = 0.95f)
                                        )
                                    )
                                )
                        )

                        // Top Badges
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .background(SitrakOrange, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "SITRAK S7H / C7H",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = Color.Black
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
                                    .border(1.dp, TelemetryCyan.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (onlineEcuCount > 0) "24V • CAN J1939 250k" else "24V БОРТСЕТЬ • CAN 250k",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = TelemetryCyan
                                )
                            }
                        }
                    }

                    // Specs info bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Двигатель",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                            Text(
                                text = "MC13.48 (MAN D26)",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = TextPrimary
                            )
                        }
                        Column {
                            Text(
                                text = "Трансмиссия",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                            Text(
                                text = "ZF TraXon 12TX",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = TextPrimary
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "Ограничитель",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                            Text(
                                text = "${truckConfig.speedLimitKmH} км/ч",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = SitrakOrange
                                )
                            )
                        }
                    }
                }
            }
        }

        // Driving Mode Switcher (Сбалансированный / Экономичный / Тяжёлый)
        item {
            val activeMode = DrivingMode.fromString(truckConfig.throttleProfile)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(DarkSurfaceElevated)
                    .border(1.dp, DarkBorder, RoundedCornerShape(16.dp))
                    .padding(14.dp)
                    .testTag("card_driving_mode_selector")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            tint = SitrakOrange,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Режим движения:",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextSecondary
                        )
                    }

                    val modeColor = when (activeMode) {
                        DrivingMode.BALANCED -> TelemetryCyan
                        DrivingMode.ECO -> GaugeGreen
                        DrivingMode.HEAVY -> SitrakOrange
                    }

                    Box(
                        modifier = Modifier
                            .background(modeColor.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                            .border(1.dp, modeColor.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = activeMode.title,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = modeColor
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DrivingMode.entries.forEach { mode ->
                        val isSelected = activeMode == mode
                        val (btnColor, activeBorder) = when (mode) {
                            DrivingMode.BALANCED -> Pair(TelemetryCyan, TelemetryCyan)
                            DrivingMode.ECO -> Pair(GaugeGreen, GaugeGreen)
                            DrivingMode.HEAVY -> Pair(SitrakOrange, SitrakOrange)
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) btnColor.copy(alpha = 0.22f) else DarkSurface)
                                .border(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) activeBorder else DarkBorder,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable { onSelectDrivingMode(mode.title) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = mode.title,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = if (isSelected) btnColor else TextPrimary,
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = mode.subtitle,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = if (isSelected) btnColor.copy(alpha = 0.85f) else TextMuted,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = activeMode.description,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
            }
        }

        // Connection & ECU Diagnostic Status Card
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(DarkSurfaceElevated)
                    .border(1.dp, DarkBorder, RoundedCornerShape(16.dp))
                    .padding(14.dp)
                    .testTag("dashboard_ecu_status_card")
            ) {
                when (connectionState) {
                    is ElmConnectionState.Disconnected -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(GaugeRed, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Сканер ELM327 не подключен к Sitrak",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Для отображения приборов подключитесь к сканеру ELM327 Bluetooth в разъеме авто или запустите эмулятор.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onNavigateTab(DiagnosticTab.HISTORY) },
                                colors = ButtonDefaults.buttonColors(containerColor = SitrakOrange),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(imageVector = Icons.Default.Bluetooth, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Подключить сканер", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = { onToggleSimulation(true) },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = TelemetryCyan, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Включить демо", color = TextPrimary, fontSize = 12.sp)
                            }
                        }
                    }

                    is ElmConnectionState.Connecting -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = SitrakOrange,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Подключение к автомобилю...",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = connectionState.step,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SitrakOrange
                                    )
                                }
                            }
                            Button(
                                onClick = onDisconnect,
                                colors = ButtonDefaults.buttonColors(containerColor = GaugeRed.copy(alpha = 0.85f)),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("Отмена", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    is ElmConnectionState.Disconnecting -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = GaugeRed,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Отключение адаптера...",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = TextPrimary
                                )
                                Text(
                                    text = "Сброс диагностических сессий и защита шины CAN Sitrak",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                        }
                    }

                    is ElmConnectionState.Error -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(GaugeRed, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Ошибка связи со сканером ELM327",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = GaugeRed
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = connectionState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onNavigateTab(DiagnosticTab.HISTORY) },
                                colors = ButtonDefaults.buttonColors(containerColor = SitrakOrange),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Повторить подключение", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = { onToggleSimulation(true) },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Режим эмулятора", color = TextPrimary, fontSize = 12.sp)
                            }
                        }
                    }

                    is ElmConnectionState.Connected -> {
                        // Header Row: Device info & Refresh action
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(GaugeGreen, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = if (connectionState.isSimulation) "Эмулятор Sitrak S7H (MC13)" else connectionState.deviceName,
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = detectedCanBus ?: connectionState.protocol,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TelemetryCyan
                                    )
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (connectionState.isSimulation) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(GaugeGreen.copy(alpha = 0.2f))
                                            .border(1.dp, GaugeGreen.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text("ДЕМО", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold), color = GaugeGreen)
                                    }
                                } else {
                                    IconButton(
                                        onClick = onDiagnoseEcusRequested,
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Опросить блоки", tint = SitrakOrange)
                                    }
                                }
                                Button(
                                    onClick = onDisconnect,
                                    colors = ButtonDefaults.buttonColors(containerColor = GaugeRed.copy(alpha = 0.85f)),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text("Отключить", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // ECU module state pills (ECM, TCU, EBS, SCR, CBCU)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            TruckModule.entries.forEach { module ->
                                val state = ecuStates[module]
                                val isOnline = state?.status == EcuStatus.ONLINE || (connectionState.isSimulation)
                                val pillBg = if (isOnline) GaugeGreen.copy(alpha = 0.15f) else Color(0xFF21262D)
                                val pillBorder = if (isOnline) GaugeGreen.copy(alpha = 0.6f) else DarkBorder
                                val textColor = if (isOnline) GaugeGreen else TextMuted

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(pillBg)
                                        .border(1.dp, pillBorder, RoundedCornerShape(8.dp))
                                        .padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = module.code,
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = textColor
                                        )
                                        Text(
                                            text = if (isOnline) "В СЕТИ" else "НЕТ",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                                            color = textColor
                                        )
                                    }
                                }
                            }
                        }

                        // Offline ECU Warning Banner & Quick CAN Protocol Switcher
                        if (!connectionState.isSimulation && (onlineEcuCount == 0 || !isCanConnected)) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(GaugeYellow.copy(alpha = 0.12f))
                                    .border(1.dp, GaugeYellow.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                    .padding(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = GaugeYellow, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Блоки Sitrak не отвечают на запросы CAN",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            color = TextPrimary
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Button(
                                            onClick = onScanCanBus,
                                            colors = ButtonDefaults.buttonColors(containerColor = TelemetryCyan),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Icon(imageVector = Icons.Default.Tune, contentDescription = null, tint = Color.Black, modifier = Modifier.size(13.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Автоскан CAN", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Button(
                                            onClick = onDiagnoseEcusRequested,
                                            colors = ButtonDefaults.buttonColors(containerColor = SitrakOrange),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Icon(imageVector = Icons.Default.Refresh, contentDescription = null, tint = Color.Black, modifier = Modifier.size(13.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Опросить", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "1. Зажигание Sitrak (Кл. 15 24V) должно быть включено.\n2. Выберите безопасный протокол ISO 29/250k или нажмите «Сброс шины CAN»:",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                    color = TextSecondary
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Button(
                                    onClick = onEmergencyResetCan,
                                    colors = ButtonDefaults.buttonColors(containerColor = GaugeRed.copy(alpha = 0.85f)),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(imageVector = Icons.Default.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Сброс шины CAN (ATPC/ATZ) — Оживить приборы", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "• Внимание 24V: разъем OBD-2 Sitrak выдает +24V. Обычный адаптер 12V может сгореть и закоротить шину CAN!\n• Если панель погасла: извлеките адаптер из разъема и выключите зажигание на 15 секунд.",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, lineHeight = 14.sp),
                                    color = GaugeYellow
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                // Quick Protocol Buttons (ISO 29/250k prioritized for Sitrak)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    listOf(
                                        ElmProtocol.ISO_15765_29_250 to "ISO 29/250k",
                                        ElmProtocol.ISO_15765_29_500 to "ISO 29/500k",
                                        ElmProtocol.ISO_15765_11_500 to "ISO 11/500k",
                                        ElmProtocol.AUTO to "АВТО",
                                        ElmProtocol.J1939_250K to "J1939 250k"
                                    ).forEach { (proto, label) ->
                                        val isCurrent = detectedCanBus?.contains(label.take(6)) == true || (proto == ElmProtocol.AUTO && detectedCanBus == null)
                                        val btnBg = if (isCurrent) SitrakOrange else Color(0xFF21262D)
                                        val btnTextColor = if (isCurrent) Color.Black else SitrakOrange

                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(btnBg)
                                                .border(1.dp, SitrakOrange.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                                                .clickable {
                                                    onSelectProtocol(proto)
                                                }
                                                .padding(vertical = 6.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = label,
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                                color = btnTextColor,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Active Fault Alert Banner (if present)
        if (activeFaults.isNotEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(GaugeRed.copy(alpha = 0.12f))
                        .border(1.dp, GaugeRed.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .clickable { onNavigateTab(DiagnosticTab.DTC) }
                        .padding(14.dp)
                        .testTag("active_faults_banner")
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .background(GaugeRed.copy(alpha = 0.2f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "DTC Warning",
                                tint = GaugeRed
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Обнаружено кодов неисправностей: ${activeFaults.size}",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            Text(
                                text = activeFaults.firstOrNull()?.let { "${it.spnFmi}: ${it.title}" } ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                maxLines = 1
                            )
                        }
                        Button(
                            onClick = { onNavigateTab(DiagnosticTab.DTC) },
                            colors = ButtonDefaults.buttonColors(containerColor = GaugeRed),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(text = "Обзор", fontSize = 12.sp, color = Color.White)
                        }
                    }
                }
            }
        }

        // Primary Circular Gauges (Tachometer & Speedometer)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularDialGauge(
                    value = telemetry.rpm,
                    minValue = 0f,
                    maxValue = 2500f,
                    title = "Обороты MC13",
                    unit = "об/мин",
                    activeColor = SitrakOrange,
                    greenZoneStart = 1000f,
                    greenZoneEnd = 1500f,
                    redZoneStart = 2100f,
                    modifier = Modifier.weight(1f),
                    testTag = "rpm_gauge"
                )

                CircularDialGauge(
                    value = telemetry.speedKmH,
                    minValue = 0f,
                    maxValue = 140f,
                    title = "Скорость Sitrak",
                    unit = "км/ч",
                    activeColor = TelemetryCyan,
                    redZoneStart = 95f,
                    modifier = Modifier.weight(1f),
                    testTag = "speed_gauge"
                )
            }
        }

        // Section Title: Pressures & Telemetry
        item {
            Text(
                text = "Давление и жизненные параметры",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = TextPrimary
            )
        }

        // Linear Gauges Grid
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LinearBarGauge(
                    title = "Топливная рампа Common Rail",
                    value = telemetry.fuelRailPressureBar,
                    unit = "бар",
                    minValue = 0f,
                    maxValue = 2000f,
                    activeColor = SitrakOrange,
                    warningThreshold = 1750f,
                    dangerThreshold = 1900f,
                    testTag = "gauge_rail_pressure"
                )

                LinearBarGauge(
                    title = "Давление наддува (Турбина)",
                    value = telemetry.boostPressureBar,
                    unit = "бар",
                    minValue = 0f,
                    maxValue = 3.2f,
                    activeColor = TelemetryCyan,
                    warningThreshold = 2.6f,
                    dangerThreshold = 3.0f,
                    testTag = "gauge_boost"
                )

                LinearBarGauge(
                    title = "Давление моторного масла",
                    value = telemetry.oilPressureBar,
                    unit = "бар",
                    minValue = 0f,
                    maxValue = 8f,
                    activeColor = GaugeGreen,
                    warningThreshold = 5.5f,
                    testTag = "gauge_oil_pressure"
                )

                LinearBarGauge(
                    title = "Температура ОЖ двигателя",
                    value = telemetry.coolantTempC,
                    unit = "°C",
                    minValue = 40f,
                    maxValue = 120f,
                    activeColor = GaugeGreen,
                    warningThreshold = 96f,
                    dangerThreshold = 104f,
                    testTag = "gauge_coolant"
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    LinearBarGauge(
                        title = "Тормоза Контур 1",
                        value = telemetry.brakeAirTank1Bar,
                        unit = "бар",
                        minValue = 0f,
                        maxValue = 12f,
                        activeColor = TelemetryCyan,
                        modifier = Modifier.weight(1f),
                        testTag = "gauge_air_1"
                    )

                    LinearBarGauge(
                        title = "Тормоза Контур 2",
                        value = telemetry.brakeAirTank2Bar,
                        unit = "бар",
                        minValue = 0f,
                        maxValue = 12f,
                        activeColor = TelemetryCyan,
                        modifier = Modifier.weight(1f),
                        testTag = "gauge_air_2"
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    LinearBarGauge(
                        title = "Реагент AdBlue",
                        value = telemetry.adBlueLevelPct,
                        unit = "%",
                        minValue = 0f,
                        maxValue = 100f,
                        activeColor = TelemetryCyan,
                        modifier = Modifier.weight(1f),
                        testTag = "gauge_adblue"
                    )

                    LinearBarGauge(
                        title = "Бортовая сеть (АКБ)",
                        value = telemetry.batteryVoltage,
                        unit = "В",
                        minValue = 18f,
                        maxValue = 32f,
                        activeColor = GaugeGreen,
                        modifier = Modifier.weight(1f),
                        testTag = "gauge_battery"
                    )
                }

                // Steering Angle Sensor SAS Quick Tile on Dashboard
                val absSteer = abs(telemetry.steeringAngleDeg)
                val steerColor = when {
                    absSteer <= 1.5f -> GaugeGreen
                    absSteer <= 10f -> GaugeYellow
                    else -> GaugeRed
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkSurface)
                        .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
                        .clickable { onNavigateTab(DiagnosticTab.TUNING) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "SAS",
                            tint = TelemetryCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Угол рулевого колеса (SAS):",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            Text(
                                text = if (telemetry.isSteeringCalibrated) "Откалиброван (0.0° зафиксирован)" else "Требуется калибровка нуля",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (telemetry.isSteeringCalibrated) GaugeGreen else GaugeYellow
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = String.format(java.util.Locale.US, "%+.1f°", telemetry.steeringAngleDeg),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            ),
                            color = steerColor
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // Quick Shortcuts
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { onNavigateTab(DiagnosticTab.TUNING) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(imageVector = Icons.Default.Build, contentDescription = null, tint = SitrakOrange)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Калибровки", color = TextPrimary)
                }

                Button(
                    onClick = onQuickSaveReport,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SitrakOrange)
                ) {
                    Text("Сохранить отчет", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

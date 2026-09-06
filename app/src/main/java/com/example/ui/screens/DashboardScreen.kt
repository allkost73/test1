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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ElectricMeter
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import com.example.model.DtcCode
import com.example.model.EcuModuleState
import com.example.model.EcuStatus
import com.example.model.LiveTelemetry
import com.example.model.TruckConfiguration
import com.example.model.TruckModule
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

        // ECU Link Status Strip (Mini Badges for ECM, TCU, EBS, SCR, CBCU)
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkSurfaceElevated)
                    .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
                    .clickable { onNavigateTab(DiagnosticTab.DTC) }
                    .padding(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Sensors,
                            contentDescription = null,
                            tint = SitrakOrange,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Блоки CAN:",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TruckModule.entries.forEach { module ->
                            val state = ecuStates[module]
                            val dotColor = when (state?.status) {
                                EcuStatus.ONLINE -> GaugeGreen
                                EcuStatus.OFFLINE -> GaugeRed
                                else -> if (isSimulationMode) GaugeGreen else TextMuted
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .background(dotColor, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = module.code,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }

                // If not connected to CAN or ECUs offline, show notice
                if (!isSimulationMode && (onlineEcuCount == 0 || !isCanConnected)) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "⚠️ Блоки не отвечают. Включите зажигание Sitrak (Кл. 15). Нажмите для проверки.",
                        style = MaterialTheme.typography.labelSmall,
                        color = GaugeYellow
                    )
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

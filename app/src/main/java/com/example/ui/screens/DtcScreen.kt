package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.DtcCode
import com.example.model.DtcSeverity
import com.example.model.EcuModuleState
import com.example.model.EcuStatus
import com.example.model.TruckModule
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
fun DtcScreen(
    faults: List<DtcCode>,
    isScanning: Boolean,
    selectedModule: TruckModule?,
    onSelectModule: (TruckModule?) -> Unit,
    onScanRequested: () -> Unit,
    onClearFaultsRequested: () -> Unit,
    ecuStates: Map<TruckModule, EcuModuleState> = emptyMap(),
    detectedCanBus: String? = null,
    isCanConnected: Boolean = true,
    ignitionDetected: Boolean = true,
    isDiagnosingEcus: Boolean = false,
    onDiagnoseEcusRequested: () -> Unit = {},
    isSimulationMode: Boolean = false,
    hasScannedRealTruck: Boolean = true,
    modifier: Modifier = Modifier
) {
    var showClearConfirmation by remember { mutableStateOf(false) }
    var selectedDetailDtc by remember { mutableStateOf<DtcCode?>(null) }

    val filteredFaults = remember(faults, selectedModule) {
        if (selectedModule == null) faults else faults.filter { it.module == selectedModule }
    }

    val onlineEcuCount = remember(ecuStates) {
        ecuStates.values.count { it.status == EcuStatus.ONLINE }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("dtc_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Top Action Header
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(DarkSurfaceElevated)
                    .border(1.dp, DarkBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Диагностика Sitrak S7H",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        Text(
                            text = "Чтение SPN-FMI и OBD-II кодов по CAN",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }

                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = SitrakOrange,
                            strokeWidth = 2.5.dp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onScanRequested,
                        enabled = !isScanning,
                        colors = ButtonDefaults.buttonColors(containerColor = SitrakOrange),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1.2f)
                            .testTag("btn_scan_modules")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Scan",
                            tint = Color.Black
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Опросить CAN", color = Color.Black, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = { showClearConfirmation = true },
                        enabled = !isScanning && faults.isNotEmpty(),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("btn_clear_dtc")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ClearAll,
                            contentDescription = "Clear",
                            tint = if (faults.isNotEmpty()) GaugeRed else TextMuted
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "Сбросить",
                            color = if (faults.isNotEmpty()) GaugeRed else TextMuted
                        )
                    }
                }
            }
        }

        // ECU Network Status Section
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(DarkSurfaceElevated)
                    .border(1.dp, DarkBorder, RoundedCornerShape(16.dp))
                    .padding(14.dp)
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
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Связь с блоками управления (ECU)",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                    }

                    TextButton(
                        onClick = onDiagnoseEcusRequested,
                        enabled = !isDiagnosingEcus && !isScanning,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        if (isDiagnosingEcus) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                color = SitrakOrange,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text("Тест связи", color = SitrakOrange, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // List of 5 modules with CAN headers and status
                TruckModule.entries.forEach { module ->
                    val ecu = ecuStates[module] ?: EcuModuleState(module)
                    val statusColor = when (ecu.status) {
                        EcuStatus.ONLINE -> GaugeGreen
                        EcuStatus.OFFLINE -> GaugeRed
                        EcuStatus.TESTING -> SitrakOrange
                        EcuStatus.UNKNOWN -> TextMuted
                    }
                    val statusText = when (ecu.status) {
                        EcuStatus.ONLINE -> "В сети (${ecu.pingMs}мс)"
                        EcuStatus.OFFLINE -> "Нет ответа"
                        EcuStatus.TESTING -> "Опрос..."
                        EcuStatus.UNKNOWN -> "Не опрошен"
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(DarkSurface)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(statusColor, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = module.code,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = TextPrimary
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "ID: ${module.can29Header}",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp
                                        ),
                                        color = TelemetryCyan
                                    )
                                }
                                Text(
                                    text = module.displayName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted
                                )
                            }
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = statusColor
                            )
                            if (ecu.activeDtcCount > 0) {
                                Text(
                                    text = "Ошибок: ${ecu.activeDtcCount}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = GaugeYellow
                                )
                            }
                        }
                    }
                }
            }
        }

        // Troubleshooting Banner if no ECUs respond or CAN disconnected
        if (!isSimulationMode && (onlineEcuCount == 0 || !isCanConnected)) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(DarkSurfaceElevated)
                        .border(1.dp, GaugeYellow.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = GaugeYellow,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Блоки Sitrak не отвечают по CAN",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = GaugeYellow
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Сканер ELM327 подключен и измеряет 24В (шкала вольтметра работает), но блоки управления не отдают данные. Основные причины:",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row {
                            Text("1. ", color = SitrakOrange, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(
                                "Зажигание выключено (Клемма 15). Без зажигания CAN-шина Sitrak засыпает. Поверните ключ в положение «ВКЛ / ON» (приборная панель должна загореться).",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        Row {
                            Text("2. ", color = SitrakOrange, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(
                                "Протокол CAN. Sitrak S7H использует SAE J1939 (29 бит / 250k) для MC11/MC13 и TraXon. Приложение автоматически отправляет 29-битные заголовки (18DA00F1).",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        Row {
                            Text("3. ", color = SitrakOrange, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(
                                "Предохранитель разъема OBD (F12) или целостность линий CAN-H / CAN-L на колодке Sitrak.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = onDiagnoseEcusRequested,
                        colors = ButtonDefaults.buttonColors(containerColor = SitrakOrange),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Повторить опрос блоков (CAN Ping)", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Module Filter Chips Horizontal Row
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedModule == null,
                    onClick = { onSelectModule(null) },
                    label = { Text("Все блоки (${faults.size})") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = SitrakOrange,
                        selectedLabelColor = Color.Black,
                        containerColor = DarkSurfaceElevated,
                        labelColor = TextPrimary
                    )
                )

                TruckModule.entries.forEach { module ->
                    val count = faults.count { it.module == module }
                    FilterChip(
                        selected = selectedModule == module,
                        onClick = { onSelectModule(module) },
                        label = { Text("${module.code} ($count)") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SitrakOrange,
                            selectedLabelColor = Color.Black,
                            containerColor = DarkSurfaceElevated,
                            labelColor = TextPrimary
                        )
                    )
                }
            }
        }

        // Empty state when no faults exist
        if (filteredFaults.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (!isSimulationMode && !hasScannedRealTruck) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .background(SitrakOrange.copy(alpha = 0.15f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Демо ошибки очищены",
                                    tint = SitrakOrange,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Ошибки демо-режима очищены",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Реальное подключение активно. Нажмите «Считать ошибки», чтобы опросить блоки Sitrak S7H.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = onScanRequested,
                                colors = ButtonDefaults.buttonColors(containerColor = SitrakOrange),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Считать ошибки с блоков Sitrak", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .background(GaugeGreen.copy(alpha = 0.15f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "No errors",
                                    tint = GaugeGreen,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Кодов неисправностей не обнаружено",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "ЭБУ двигателя MC13, АКПП и тормозная система работают в штатном режиме",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
        } else {
            // Faults list
            items(filteredFaults, key = { it.id }) { fault ->
                DtcItemCard(
                    fault = fault,
                    onClick = { selectedDetailDtc = fault }
                )
            }
        }
    }

    // Safety Confirmation Dialog for Clearing DTC
    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = GaugeRed)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Сброс кодов ошибок (04)")
                }
            },
            text = {
                Column {
                    Text(
                        text = "Вы собираетесь стереть память неисправностей во всех блоках управления Sitrak S7H (MC11/MC13, TraXon, WABCO EBS, SCR).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "ВНИМАНИЕ: Сброс не устраняет физическую неисправность. Убедитесь, что двигатель выключен, а зажигание включено (Кл. 15 ON).",
                        style = MaterialTheme.typography.bodySmall,
                        color = GaugeYellow
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirmation = false
                        onClearFaultsRequested()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GaugeRed)
                ) {
                    Text("Стереть ошибки", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) {
                    Text("Отмена", color = TextSecondary)
                }
            },
            containerColor = DarkSurfaceElevated,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary
        )
    }

    // Detail & Freeze Frame Dialog
    selectedDetailDtc?.let { dtc ->
        DtcDetailDialog(
            dtc = dtc,
            onDismiss = { selectedDetailDtc = null }
        )
    }
}

@Composable
fun DtcItemCard(
    fault: DtcCode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val severityColor = when (fault.severity) {
        DtcSeverity.CRITICAL -> GaugeRed
        DtcSeverity.WARNING -> GaugeYellow
        DtcSeverity.INFO -> TelemetryCyan
    }

    val severityText = when (fault.severity) {
        DtcSeverity.CRITICAL -> "КРИТИЧЕСКАЯ"
        DtcSeverity.WARNING -> "ПРЕДУПРЕЖДЕНИЕ"
        DtcSeverity.INFO -> "ИНФО"
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(DarkSurfaceElevated)
            .border(1.dp, DarkBorder, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(14.dp)
            .testTag("dtc_card_${fault.id}")
    ) {
        Column {
            // Badges row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .background(severityColor.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = fault.spnFmi,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            ),
                            color = severityColor
                        )
                    }

                    Box(
                        modifier = Modifier
                            .background(Color(0xFF262C36), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = fault.obdCode,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = TextSecondary
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .background(SitrakOrange.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = fault.module.displayName,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = SitrakOrange
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = fault.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = fault.description,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 2
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Статус: ${if (fault.isActive) "Активная" else "В памяти (Архив)"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (fault.isActive) GaugeRed else GaugeGreen
                )

                Text(
                    text = "Стоп-кадр и причины →",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = TelemetryCyan
                )
            }
        }
    }
}

@Composable
fun DtcDetailDialog(
    dtc: DtcCode,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = "${dtc.spnFmi} (${dtc.obdCode})",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    ),
                    color = SitrakOrange
                )
                Text(
                    text = dtc.module.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text(
                        text = dtc.title,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = dtc.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }

                if (dtc.freezeFrame.isNotEmpty()) {
                    item {
                        Text(
                            text = "Параметры стоп-кадра (Freeze Frame):",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = TelemetryCyan
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(DarkSurface, RoundedCornerShape(8.dp))
                                .border(1.dp, DarkBorder, RoundedCornerShape(8.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            dtc.freezeFrame.forEach { (key, value) ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(text = key, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                    Text(
                                        text = value,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        color = TextPrimary
                                    )
                                }
                            }
                        }
                    }
                }

                if (dtc.probableCauses.isNotEmpty()) {
                    item {
                        Text(
                            text = "Возможные причины и порядок устранения:",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = SitrakOrange
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            dtc.probableCauses.forEachIndexed { idx, cause ->
                                Row(verticalAlignment = Alignment.Top) {
                                    Text(
                                        text = "${idx + 1}. ",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                        color = SitrakOrange
                                    )
                                    Text(
                                        text = cause,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = SitrakOrange)
            ) {
                Text("Закрыть", color = Color.Black)
            }
        },
        containerColor = DarkSurfaceElevated,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary
    )
}

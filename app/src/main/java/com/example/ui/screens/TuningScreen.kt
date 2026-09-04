package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import com.example.model.TruckConfiguration
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
fun TuningScreen(
    truckConfig: TruckConfiguration,
    activeCylinderCutout: Int?,
    onUpdateSpeedLimit: (Int) -> Unit,
    onUpdateIdleRpm: (Int) -> Unit,
    onTriggerDpfRegen: () -> Unit,
    onResetAdBlueDerate: () -> Unit,
    onTestCylinderCutout: (Int) -> Unit,
    onUpdateComfortSettings: (Boolean, String, Int, Int, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var tempSpeedLimit by remember(truckConfig.speedLimitKmH) { mutableFloatStateOf(truckConfig.speedLimitKmH.toFloat()) }
    var tempIdleRpm by remember(truckConfig.idleRpm) { mutableFloatStateOf(truckConfig.idleRpm.toFloat()) }

    var reverseBuzzer by remember(truckConfig.reverseBuzzer) { mutableStateOf(truckConfig.reverseBuzzer) }
    var drlMode by remember(truckConfig.drlMode) { mutableStateOf(truckConfig.drlMode) }
    var headlightDelay by remember(truckConfig.headlightDelaySec) { mutableIntStateOf(truckConfig.headlightDelaySec) }
    var cruiseStep by remember(truckConfig.cruiseStepKmH) { mutableIntStateOf(truckConfig.cruiseStepKmH) }
    var throttleProfile by remember(truckConfig.throttleProfile) { mutableStateOf(truckConfig.throttleProfile) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("tuning_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section 1: Speed Limiter
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Speed, contentDescription = null, tint = SitrakOrange)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Ограничитель скорости Sitrak",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                    }

                    Text(
                        text = "${tempSpeedLimit.toInt()} км/ч",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        ),
                        color = SitrakOrange
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Slider(
                    value = tempSpeedLimit,
                    onValueChange = { tempSpeedLimit = it },
                    valueRange = 80f..130f,
                    steps = 9,
                    colors = SliderDefaults.colors(
                        thumbColor = SitrakOrange,
                        activeTrackColor = SitrakOrange,
                        inactiveTrackColor = Color(0xFF262C36)
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("slider_speed_limit")
                )

                // Quick preset buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(85, 90, 100, 110, 120).forEach { limit ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (tempSpeedLimit.toInt() == limit) SitrakOrange else Color(0xFF21262D))
                                .clickable { tempSpeedLimit = limit.toFloat() }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "$limit",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = if (tempSpeedLimit.toInt() == limit) Color.Black else TextSecondary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = { onUpdateSpeedLimit(tempSpeedLimit.toInt()) },
                    colors = ButtonDefaults.buttonColors(containerColor = SitrakOrange),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().testTag("btn_save_speed_limit")
                ) {
                    Text("Записать в ЭБУ двигателя (${tempSpeedLimit.toInt()} км/ч)", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Section 2: Idle RPM Calibration
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.RotateRight, contentDescription = null, tint = TelemetryCyan)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Обороты холостого хода (ХХ)",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                    }

                    Text(
                        text = "${tempIdleRpm.toInt()} об/мин",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        ),
                        color = TelemetryCyan
                    )
                }

                Text(
                    text = "Используется для зимнего прогрева или при работе с гидравлическим насосом КОМ",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Slider(
                    value = tempIdleRpm,
                    onValueChange = { tempIdleRpm = it },
                    valueRange = 550f..800f,
                    steps = 4,
                    colors = SliderDefaults.colors(
                        thumbColor = TelemetryCyan,
                        activeTrackColor = TelemetryCyan,
                        inactiveTrackColor = Color(0xFF262C36)
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("slider_idle_rpm")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(580, 600, 650, 700, 750).forEach { rpm ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (tempIdleRpm.toInt() == rpm) TelemetryCyan else Color(0xFF21262D))
                                .clickable { tempIdleRpm = rpm.toFloat() }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "$rpm",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = if (tempIdleRpm.toInt() == rpm) Color.Black else TextSecondary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = { onUpdateIdleRpm(tempIdleRpm.toInt()) },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().testTag("btn_save_idle_rpm")
                ) {
                    Text("Применить калибровку ХХ (${tempIdleRpm.toInt()} об/мин)", color = TextPrimary)
                }
            }
        }

        // Section 3: DPF Service Regeneration & AdBlue Reset
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(DarkSurfaceElevated)
                    .border(1.dp, DarkBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.LocalFireDepartment, contentDescription = null, tint = GaugeYellow)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Сервисная регенерация сажевого DPF",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Условия запуска: КПП в нейтрали (N), стояночный тормоз ВКЛЮЧЕН, температура ОЖ > 70 °C.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (truckConfig.dpfRegenInProgress) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Идет прожиг фильтра (Т выхлопа ~ 580°C)...",
                                style = MaterialTheme.typography.bodySmall,
                                color = GaugeYellow
                            )
                            Text(
                                text = "${truckConfig.dpfRegenProgressPct} %",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { truckConfig.dpfRegenProgressPct / 100f },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                            color = GaugeYellow,
                            trackColor = Color(0xFF262C36)
                        )
                    }
                } else {
                    Button(
                        onClick = onTriggerDpfRegen,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().testTag("btn_trigger_dpf")
                    ) {
                        Icon(imageVector = Icons.Default.LocalFireDepartment, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Запустить прожиг сажевого фильтра", color = Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // AdBlue Derate Reset Button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(DarkSurface)
                        .border(1.dp, DarkBorder, RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Сброс ограничения мощности AdBlue",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = TextPrimary
                        )
                        Text(
                            text = "Снимает блокировку крутящего момента (дератирование 25%)",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                    Button(
                        onClick = onResetAdBlueDerate,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TelemetryCyan),
                        modifier = Modifier.testTag("btn_reset_adblue")
                    ) {
                        Text("Сбросить", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Section 4: Cylinder Cutout Diagnostic Test
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(DarkSurfaceElevated)
                    .border(1.dp, DarkBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.ElectricBolt, contentDescription = null, tint = SitrakOrange)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Тест отключения цилиндров (MC13)",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Позволяет отключать форсунки по одной на холостом ходу для локализации троящей форсунки Common Rail.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    (1..6).forEach { cyl ->
                        val isCut = activeCylinderCutout == cyl
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isCut) GaugeRed else Color(0xFF21262D))
                                .border(1.dp, if (isCut) GaugeRed else DarkBorder, RoundedCornerShape(10.dp))
                                .clickable { onTestCylinderCutout(cyl) }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "№$cyl",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = if (isCut) Color.White else TextPrimary
                                )
                                Text(
                                    text = if (isCut) "ОТКЛ" else "АКТИВ",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                    color = if (isCut) Color.White else GaugeGreen
                                )
                            }
                        }
                    }
                }
            }
        }

        // Section 5: Cabin & CBCU Settings
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(DarkSurfaceElevated)
                    .border(1.dp, DarkBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Text(
                    text = "Кузовной блок CBCU / Комфорт",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Reverse Buzzer Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Зуммер заднего хода", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                        Text("Звуковой сигнал при включении R", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    }
                    Switch(
                        checked = reverseBuzzer,
                        onCheckedChange = {
                            reverseBuzzer = it
                            onUpdateComfortSettings(reverseBuzzer, drlMode, headlightDelay, cruiseStep, throttleProfile)
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = SitrakOrange, checkedTrackColor = SitrakOrange.copy(alpha = 0.5f))
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // DRL Mode Selection
                Text("Режим дневных ходовых огней (ДХО):", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("Автоматические ДХО", "Постоянно", "Отключены").forEach { mode ->
                        val selected = drlMode == mode
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) SitrakOrange else Color(0xFF21262D))
                                .clickable {
                                    drlMode = mode
                                    onUpdateComfortSettings(reverseBuzzer, drlMode, headlightDelay, cruiseStep, throttleProfile)
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = mode,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = if (selected) Color.Black else TextSecondary,
                                maxLines = 1
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Throttle Profile
                Text("Отклик педали акселератора:", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("Стандарт", "Эко", "Тяжелый груз").forEach { prof ->
                        val selected = throttleProfile == prof
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) TelemetryCyan else Color(0xFF21262D))
                                .clickable {
                                    throttleProfile = prof
                                    onUpdateComfortSettings(reverseBuzzer, drlMode, headlightDelay, cruiseStep, throttleProfile)
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = prof,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = if (selected) Color.Black else TextSecondary,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

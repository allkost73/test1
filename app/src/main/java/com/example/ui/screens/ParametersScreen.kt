package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.LiveTelemetry
import com.example.ui.components.RealtimeTelemetryChart
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.GaugeGreen
import com.example.ui.theme.GaugeYellow
import com.example.ui.theme.SitrakOrange
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun ParametersScreen(
    telemetry: LiveTelemetry,
    rpmHistory: List<Float>,
    boostHistory: List<Float>,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("parameters_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Real-time Oscilloscope Graphs
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RealtimeTelemetryChart(
                    title = "Обороты двигателя MC13 (RPM)",
                    points = rpmHistory,
                    minY = 400f,
                    maxY = 2200f,
                    unit = "об/мин",
                    lineColor = SitrakOrange,
                    testTag = "chart_rpm"
                )

                RealtimeTelemetryChart(
                    title = "Давление наддува турбокомпрессора",
                    points = boostHistory,
                    minY = 0.5f,
                    maxY = 3.0f,
                    unit = "бар",
                    lineColor = TelemetryCyan,
                    testTag = "chart_boost"
                )
            }
        }

        // Section: Engine MC11 / MC13
        item {
            SensorGroupCard(
                groupTitle = "1. Двигатель Sinotruk MC13 (MAN D26)",
                accentColor = SitrakOrange,
                sensors = listOf(
                    SensorItem("Обороты коленвала (SPN 190)", "${telemetry.rpm.toInt()} об/мин"),
                    SensorItem("Скорость грузовика (SPN 84)", "${telemetry.speedKmH.toInt()} км/ч"),
                    SensorItem("Нагрузка на двигатель", "${telemetry.engineLoadPct.toInt()} %"),
                    SensorItem("Температура ОЖ (SPN 110)", "${telemetry.coolantTempC.toInt()} °C"),
                    SensorItem("Давление масла (SPN 100)", String.format("%.2f бар", telemetry.oilPressureBar)),
                    SensorItem("Температура масла (SPN 175)", "${telemetry.oilTempC.toInt()} °C"),
                    SensorItem("Температура воздуха на впуске", "${telemetry.intakeAirTempC.toInt()} °C")
                )
            )
        }

        // Section: Common Rail Fuel System
        item {
            SensorGroupCard(
                groupTitle = "2. Топливная система Common Rail (Bosch)",
                accentColor = TelemetryCyan,
                sensors = listOf(
                    SensorItem("Давление в рампе (SPN 157)", "${telemetry.fuelRailPressureBar.toInt()} бар"),
                    SensorItem("Мгновенный часовой расход (SPN 183)", String.format("%.1f л/ч", telemetry.fuelRateLPerH)),
                    SensorItem("Статус дозирующего клапана MeUN", "Штатный ШИМ (12%)"),
                    SensorItem("Клапан ограничения давления (PRV)", "Закрыт (Герметичен)")
                )
            )
        }

        // Section: Pneumatic Braking System WABCO EBS
        item {
            SensorGroupCard(
                groupTitle = "3. Пневматическая тормозная система WABCO",
                accentColor = GaugeGreen,
                sensors = listOf(
                    SensorItem("Давление в контуре 1 (SPN 1087)", String.format("%.1f бар", telemetry.brakeAirTank1Bar)),
                    SensorItem("Давление в контуре 2 (SPN 1088)", String.format("%.1f бар", telemetry.brakeAirTank2Bar)),
                    SensorItem("Стояночный тормоз (Ручник)", if (telemetry.parkingBrakeActive) "АКТИВЕН (Заторможен)" else "ОТПУЩЕН"),
                    SensorItem("Регулятор давления компрессора", "Сброс (9.0 бар)")
                )
            )
        }

        // Section: SCR / AdBlue / DPF Aftertreatment
        item {
            SensorGroupCard(
                groupTitle = "4. Экологический комплекс SCR / AdBlue / DPF",
                accentColor = GaugeYellow,
                sensors = listOf(
                    SensorItem("Уровень мочевины AdBlue (SPN 1761)", "${telemetry.adBlueLevelPct.toInt()} %"),
                    SensorItem("Степень заполнения DPF (SPN 3719)", "${telemetry.dpfSootLoadPct.toInt()} %"),
                    SensorItem("Температура ОГ на входе (SPN 3242)", "${telemetry.exhaustTempC.toInt()} °C"),
                    SensorItem("Статус дератирования крутящего момента", "Блокировка снята (100% мощности)")
                )
            )
        }

        // Section: Transmission & Electrics
        item {
            SensorGroupCard(
                groupTitle = "5. Трансмиссия TraXon и бортсеть 24В",
                accentColor = SitrakOrange,
                sensors = listOf(
                    SensorItem("Текущая передача (SPN 523)", if (telemetry.gearCurrent == 0) "Нейтраль (N)" else "${telemetry.gearCurrent} передача"),
                    SensorItem("Гидрозамедлитель (Ретардер SPN 520)", if (telemetry.retarderActive) "ВКЛЮЧЕН" else "ВЫКЛЮЧЕН"),
                    SensorItem("Бортовое напряжение (SPN 168)", String.format("%.1f В (24V сеть)", telemetry.batteryVoltage)),
                    SensorItem("Ток заряда генератора", "42 А")
                )
            )
        }
    }
}

data class SensorItem(val label: String, val value: String)

@Composable
fun SensorGroupCard(
    groupTitle: String,
    accentColor: androidx.compose.ui.graphics.Color,
    sensors: List<SensorItem>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(DarkSurfaceElevated, RoundedCornerShape(14.dp))
            .border(1.dp, DarkBorder, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Text(
            text = groupTitle,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = accentColor
        )

        Spacer(modifier = Modifier.height(10.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            sensors.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Text(
                        text = item.value,
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

package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import com.example.model.DrivingMode
import com.example.model.LiveTelemetry
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

import java.util.Locale

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
    telemetry: LiveTelemetry = LiveTelemetry(),
    isWritingCalibration: Boolean = false,
    onCalibrateVoltage: (Float) -> Unit = {},
    onResetVoltageCalibration: () -> Unit = {},
    onAdjustVoltageStep: (Float) -> Unit = {},
    onUpdateDrivingMode: (String) -> Unit = {},
    isCalibratingSteering: Boolean = false,
    onCalibrateSteeringZero: () -> Unit = {},
    onResetSteeringCalibration: () -> Unit = {},
    onAdjustSteeringOffset: (Float) -> Unit = {},
    onSimulateSteeringAngle: (Float) -> Unit = {},
    onClearEbsStopFault: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var tempSpeedLimit by remember(truckConfig.speedLimitKmH) { mutableFloatStateOf(truckConfig.speedLimitKmH.toFloat()) }
    var tempIdleRpm by remember(truckConfig.idleRpm) { mutableFloatStateOf(truckConfig.idleRpm.toFloat()) }
    var manualVoltText by remember { mutableStateOf("") }
    var steerSimSlider by remember(telemetry.steeringAngleDeg) { mutableFloatStateOf(telemetry.steeringAngleDeg) }

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
        // Section 0: Voltage Calibration (Калибровка вольтметра бортовой сети Sitrak 24В)
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(DarkSurfaceElevated)
                    .border(1.dp, DarkBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp)
                    .testTag("card_voltage_calibration")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.ElectricBolt, contentDescription = null, tint = GaugeYellow)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Калибровка вольтметра (24В)",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            Text(
                                text = if (telemetry.isVoltageCalibrated) "Пользовательская калибровка активна" else "Заводской делитель АЦП ELM327",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (telemetry.isVoltageCalibrated) GaugeGreen else TextMuted
                            )
                        }
                    }

                    Text(
                        text = String.format(Locale.US, "%.1f В", telemetry.batteryVoltage),
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        ),
                        color = if (telemetry.batteryVoltage in 26.0f..29.0f) GaugeGreen else GaugeYellow
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Voltage Diagnostic Detail Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(DarkSurface)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Сырое напряжение с адаптера (ATRV):",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                        if (telemetry.ecmModuleVoltage > 0f) {
                            Text(
                                text = "Напряжение ЭБУ Bosch EDC17 (PID 0142):",
                                style = MaterialTheme.typography.labelSmall,
                                color = TelemetryCyan
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = String.format(Locale.US, "%.2f В", if (telemetry.rawElmVoltage > 0f) telemetry.rawElmVoltage else telemetry.batteryVoltage),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        if (telemetry.ecmModuleVoltage > 0f) {
                            Text(
                                text = String.format(Locale.US, "%.2f В", telemetry.ecmModuleVoltage),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                                color = TelemetryCyan
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Quick Step adjustments (-0.5, -0.1, +0.1, +0.5)
                Text(
                    text = "Быстрая подгонка шагом:",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(-0.5f to "-0.5В", -0.1f to "-0.1В", 0.1f to "+0.1В", 0.5f to "+0.5В").forEach { (delta, label) ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF21262D))
                                .clickable { onAdjustVoltageStep(delta) }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Direct Target Input Field & Apply
                Text(
                    text = "Задать точное значение с мультиметра:",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = manualVoltText,
                        onValueChange = { manualVoltText = it },
                        placeholder = { Text("Напр. 27.8", fontSize = 13.sp, color = TextMuted) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SitrakOrange,
                            unfocusedBorderColor = DarkBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                    )

                    Button(
                        onClick = {
                            val parsed = manualVoltText.replace(",", ".").toFloatOrNull()
                            if (parsed != null && parsed in 10.0f..36.0f) {
                                onCalibrateVoltage(parsed)
                                manualVoltText = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SitrakOrange),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(50.dp)
                    ) {
                        Text("Откалибровать", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Footer Reset and hint
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Норма генератора: 27.2 – 28.6 В",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )

                    Text(
                        text = "Сбросить к заводскому АЦП",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = SitrakOrange,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onResetVoltageCalibration() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        // Section 1: Steering Angle Sensor Calibration (Калибровка датчика угла поворота руля SAS / WABCO EBS ESP)
        item {
            val absAngle = abs(telemetry.steeringAngleDeg)
            val isAngleInZeroZone = absAngle <= 1.5f
            val isAngleInSafeTolerance = absAngle <= 10.0f
            val canExecuteCalibration = !isCalibratingSteering

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(DarkSurfaceElevated)
                    .border(1.dp, DarkBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp)
                    .testTag("card_steering_calibration")
            ) {
                // Header Row with Icon, Title, Status & Big Readout
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = TelemetryCyan
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Калибровка датчика угла руля (SAS)",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            Text(
                                text = if (telemetry.isSteeringCalibrated) "Нулевая точка зафиксирована (0.0° ОК)" else "Требуется калибровка нулевой точки",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (telemetry.isSteeringCalibrated) GaugeGreen else GaugeYellow
                            )
                        }
                    }

                    Text(
                        text = String.format(Locale.US, "%+.1f°", telemetry.steeringAngleDeg),
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        ),
                        color = when {
                            isAngleInZeroZone -> GaugeGreen
                            isAngleInSafeTolerance -> GaugeYellow
                            else -> GaugeRed
                        }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Interactive Rotating Steering Wheel Component
                SteeringWheelVisualizer(
                    angleDeg = telemetry.steeringAngleDeg,
                    isCalibrated = telemetry.isSteeringCalibrated,
                    onAngleChange = { newAngle ->
                        steerSimSlider = newAngle
                        onSimulateSteeringAngle(newAngle)
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Diagnostic Readings Grid
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(DarkSurface)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Сырой сигнал датчика SAS (до смещения):",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                        Text(
                            text = String.format(Locale.US, "%+.1f°", telemetry.rawSteeringAngleDeg),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            ),
                            color = TextPrimary
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Калибровочное смещение нуля (Offset):",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                        Text(
                            text = String.format(Locale.US, "%+.1f°", telemetry.steeringCalibrationOffsetDeg),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            ),
                            color = TelemetryCyan
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Блок управления и протокол шины CAN:",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                        Text(
                            text = "WABCO EBS (0x0B / 18DA0BF1)",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = GaugeGreen
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Зона допуска нуля прямолинейного хода:",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                        Text(
                            text = if (isAngleInZeroZone) "В допуске ([-1.5° ... +1.5°])" else "Отклонение от центра",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = if (isAngleInZeroZone) GaugeGreen else GaugeYellow
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Interactive Steering Wheel Slider & Angle Presets for Testing & Simulator
                Text(
                    text = "Проверка вращения руля и отклика датчика:",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(6.dp))

                // Left / Right nudge buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val newAngle = (telemetry.steeringAngleDeg - 15f).coerceIn(-180f, 180f)
                            steerSimSlider = newAngle
                            onSimulateSteeringAngle(newAngle)
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TelemetryCyan),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(imageVector = Icons.Default.RotateLeft, contentDescription = null, tint = TelemetryCyan, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Влево (-15°)", fontSize = 11.sp, maxLines = 1)
                    }

                    OutlinedButton(
                        onClick = {
                            val newAngle = (telemetry.steeringAngleDeg + 15f).coerceIn(-180f, 180f)
                            steerSimSlider = newAngle
                            onSimulateSteeringAngle(newAngle)
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TelemetryCyan),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(imageVector = Icons.Default.RotateRight, contentDescription = null, tint = TelemetryCyan, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Вправо (+15°)", fontSize = 11.sp, maxLines = 1)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Slider(
                    value = steerSimSlider.coerceIn(-180f, 180f),
                    onValueChange = {
                        steerSimSlider = it
                        onSimulateSteeringAngle(it)
                    },
                    valueRange = -180f..180f,
                    colors = SliderDefaults.colors(
                        thumbColor = TelemetryCyan,
                        activeTrackColor = TelemetryCyan,
                        inactiveTrackColor = Color(0xFF262C36)
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("slider_steering_angle")
                )

                // Quick Angle Buttons (Expanded range)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(-90f to "-90°", -45f to "-45°", -15f to "-15°", 0.0f to "0° Прямо", 15f to "+15°", 45f to "+45°", 90f to "+90°").forEach { (angle, label) ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (abs(telemetry.steeringAngleDeg - angle) < 1.0f) TelemetryCyan else Color(0xFF21262D))
                                .clickable {
                                    steerSimSlider = angle
                                    onSimulateSteeringAngle(angle)
                                }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 9.sp),
                                color = if (abs(telemetry.steeringAngleDeg - angle) < 1.0f) Color.Black else TextSecondary,
                                maxLines = 1
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Pre-calibration Checklist
                Text(
                    text = "Контроль условий перед калибровкой:",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(DarkSurface)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Check 1: Stationary
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = GaugeGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "1. Автомобиль неподвижен (Скорость: 0 км/ч)",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextPrimary
                        )
                    }

                    // Check 2: Parking Brake
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (telemetry.parkingBrakeActive) Icons.Default.CheckCircle else Icons.Default.Cancel,
                            contentDescription = null,
                            tint = if (telemetry.parkingBrakeActive) GaugeGreen else GaugeRed,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (telemetry.parkingBrakeActive) "2. Стояночный тормоз ВКЛЮЧЕН (Ручник активен)" else "2. ВНИМАНИЕ: Затяните стояночный тормоз!",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (telemetry.parkingBrakeActive) TextPrimary else GaugeRed
                        )
                    }

                    // Check 3: Straight Wheel Alignment
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isAngleInSafeTolerance) Icons.Default.CheckCircle else Icons.Default.Cancel,
                            contentDescription = null,
                            tint = if (isAngleInSafeTolerance) GaugeGreen else GaugeYellow,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isAngleInSafeTolerance) "3. Руль выставлен прямо (допуск: ${String.format(Locale.US, "%.1f°", absAngle)} < 10°)" else "3. Выставьте рулевое колесо строго по центру (отклонение ${String.format(Locale.US, "%.1f°", absAngle)} > 10°)",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isAngleInSafeTolerance) TextPrimary else GaugeYellow
                        )
                    }

                    // Check 4: CAN connection
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = GaugeGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "4. Связь с блоком WABCO EBS (CAN-шина активна)",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextPrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Primary Calibration Action Button
                Button(
                    onClick = onCalibrateSteeringZero,
                    enabled = canExecuteCalibration,
                    colors = ButtonDefaults.buttonColors(containerColor = TelemetryCyan),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().testTag("btn_calibrate_steering")
                ) {
                    if (isCalibratingSteering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.Black,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Безопасная калибровка нуля WABCO EBS...", color = Color.Black, fontWeight = FontWeight.Bold)
                    } else {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Установить ноль руля (Зафиксировать 0.0° SAS)", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Button to clear / reset EBS STOP fault state
                OutlinedButton(
                    onClick = onClearEbsStopFault,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SitrakOrange),
                    border = BorderStroke(1.dp, SitrakOrange.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().testTag("btn_clear_ebs_stop")
                ) {
                    Icon(
                        imageVector = Icons.Default.Cancel,
                        contentDescription = null,
                        tint = SitrakOrange,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Снять ошибку «EBS СТОП» / Закрыть рутину WABCO",
                        color = SitrakOrange,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Micro Step Adjustments (-1.0, -0.2, +0.2, +1.0)
                Text(
                    text = "Тонкая подгонка нуля шагом:",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(-1.0f to "-1.0°", -0.2f to "-0.2°", 0.2f to "+0.2°", 1.0f to "+1.0°").forEach { (delta, label) ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF21262D))
                                .clickable { onAdjustSteeringOffset(delta) }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Footer with explanation and Reset to factory
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Допуск нуля: ±1.5° (WABCO ESP SPN 1807)",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )

                    Text(
                        text = "Сбросить к заводской",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = TelemetryCyan,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onResetSteeringCalibration() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
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
                    enabled = !isWritingCalibration,
                    colors = ButtonDefaults.buttonColors(containerColor = SitrakOrange),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().testTag("btn_save_speed_limit")
                ) {
                    if (isWritingCalibration) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.Black, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Запись в ЭБУ...", color = Color.Black, fontWeight = FontWeight.Bold)
                    } else {
                        Text("Записать в ЭБУ двигателя (${tempSpeedLimit.toInt()} км/ч)", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
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
                    enabled = !isWritingCalibration,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().testTag("btn_save_idle_rpm")
                ) {
                    if (isWritingCalibration) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = TelemetryCyan, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Запись в ЭБУ...", color = TextPrimary)
                    } else {
                        Text("Применить калибровку ХХ (${tempIdleRpm.toInt()} об/мин)", color = TextPrimary)
                    }
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

                Spacer(modifier = Modifier.height(16.dp))

                // Driving Modes & Throttle Response
                val activeMode = DrivingMode.fromString(truckConfig.throttleProfile)

                Text(
                    text = "Режим движения и отклик педали (MC13 + TraXon):",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Переключение программы управления крутящим моментом и коробкой передач:",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DrivingMode.entries.forEach { mode ->
                        val isSelected = activeMode == mode
                        val (accentColor, _) = when (mode) {
                            DrivingMode.BALANCED -> Pair(TelemetryCyan, TelemetryCyan)
                            DrivingMode.ECO -> Pair(GaugeGreen, GaugeGreen)
                            DrivingMode.HEAVY -> Pair(SitrakOrange, SitrakOrange)
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) accentColor.copy(alpha = 0.22f) else DarkSurface)
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) accentColor else DarkBorder,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable {
                                    throttleProfile = mode.title
                                    onUpdateDrivingMode(mode.title)
                                    onUpdateComfortSettings(reverseBuzzer, drlMode, headlightDelay, cruiseStep, mode.title)
                                }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = accentColor,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(3.dp))
                                    }
                                    Text(
                                        text = mode.title,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = if (isSelected) accentColor else TextPrimary,
                                        maxLines = 1
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = mode.subtitle,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = if (isSelected) accentColor.copy(alpha = 0.85f) else TextMuted,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Detail Explanation Banner for Active Mode
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(DarkSurface)
                        .border(1.dp, DarkBorder, RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val activeColor = when (activeMode) {
                                DrivingMode.BALANCED -> TelemetryCyan
                                DrivingMode.ECO -> GaugeGreen
                                DrivingMode.HEAVY -> SitrakOrange
                            }
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(activeColor, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Активен режим: ${activeMode.title} (${activeMode.subtitle})",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = activeColor
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = activeMode.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )

                        if (isWritingCalibration) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = SitrakOrange
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Запись параметров в ЭБУ Bosch EDC17 и TraXon...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SitrakOrange
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
fun SteeringWheelVisualizer(
    angleDeg: Float,
    isCalibrated: Boolean,
    onAngleChange: ((Float) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val absAngle = abs(angleDeg)
    val angleColor = when {
        absAngle <= 1.5f -> GaugeGreen
        absAngle <= 10.0f -> GaugeYellow
        else -> GaugeRed
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .pointerInput(angleDeg) {
                if (onAngleChange != null) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val delta = dragAmount.x / 1.8f
                        val newAngle = (angleDeg + delta).coerceIn(-180f, 180f)
                        onAngleChange(newAngle)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(190.dp)) {
            val center = this.center
            val radius = size.minDimension / 2f - 12.dp.toPx()
            val rimStroke = 14.dp.toPx()

            // 1. Static Reference Elements
            // Top Absolute Zero Notch Marker
            drawLine(
                color = GaugeGreen.copy(alpha = 0.85f),
                start = Offset(center.x, center.y - radius - 10.dp.toPx()),
                end = Offset(center.x, center.y - radius + 10.dp.toPx()),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )

            // Safe Zero Zone Sector Arc ([-1.5° .. +1.5°], centered at 270°)
            drawArc(
                color = GaugeGreen.copy(alpha = 0.35f),
                startAngle = 270f - 1.5f,
                sweepAngle = 3.0f,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = rimStroke + 6.dp.toPx(), cap = StrokeCap.Round)
            )

            // Dynamic Angular Sweep Arc from 0° (270°) to current angle
            if (absAngle > 0.5f) {
                val clampedSweep = angleDeg.coerceIn(-180f, 180f)
                drawArc(
                    color = angleColor.copy(alpha = 0.6f),
                    startAngle = 270f,
                    sweepAngle = clampedSweep,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // 2. Rotating Steering Wheel
            rotate(degrees = angleDeg, pivot = center) {
                // Outer Rim base
                drawCircle(
                    color = Color(0xFF232832),
                    radius = radius,
                    center = center,
                    style = Stroke(width = rimStroke)
                )
                // Rim outer highlight
                drawCircle(
                    color = DarkBorder,
                    radius = radius + (rimStroke / 2),
                    center = center,
                    style = Stroke(width = 1.dp.toPx())
                )
                drawCircle(
                    color = DarkBorder,
                    radius = radius - (rimStroke / 2),
                    center = center,
                    style = Stroke(width = 1.dp.toPx())
                )

                // Top center zero notch on the rotating wheel rim
                drawArc(
                    color = SitrakOrange,
                    startAngle = 265f,
                    sweepAngle = 10f,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = rimStroke, cap = StrokeCap.Butt)
                )

                // Spokes (3 Spokes: Left 180°, Right 0°, Bottom 90°)
                val spokeWidth = 10.dp.toPx()
                val hubRadius = 32.dp.toPx()

                // Left Spoke
                drawLine(
                    color = Color(0xFF323945),
                    start = Offset(center.x - hubRadius, center.y),
                    end = Offset(center.x - radius + (rimStroke / 2), center.y),
                    strokeWidth = spokeWidth,
                    cap = StrokeCap.Round
                )
                // Right Spoke
                drawLine(
                    color = Color(0xFF323945),
                    start = Offset(center.x + hubRadius, center.y),
                    end = Offset(center.x + radius - (rimStroke / 2), center.y),
                    strokeWidth = spokeWidth,
                    cap = StrokeCap.Round
                )
                // Bottom Spoke
                drawLine(
                    color = Color(0xFF323945),
                    start = Offset(center.x, center.y + hubRadius),
                    end = Offset(center.x, center.y + radius - (rimStroke / 2)),
                    strokeWidth = spokeWidth,
                    cap = StrokeCap.Round
                )

                // Central Airbag Hub
                drawCircle(
                    color = Color(0xFF181C23),
                    radius = hubRadius,
                    center = center
                )
                drawCircle(
                    color = DarkBorder,
                    radius = hubRadius,
                    center = center,
                    style = Stroke(width = 1.5.dp.toPx())
                )
                // Emblem inner badge
                drawCircle(
                    color = SitrakOrange.copy(alpha = 0.25f),
                    radius = hubRadius * 0.55f,
                    center = center
                )
            }
        }

        // Center Digital Readout Overlay
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = String.format(Locale.US, "%+.1f°", angleDeg),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp
                ),
                color = angleColor
            )
            Text(
                text = when {
                    absAngle <= 1.5f -> "ЦЕНТР"
                    angleDeg < 0 -> "ВЛЕВО"
                    else -> "ВПРАВО"
                },
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 8.sp
                ),
                color = angleColor
            )
            Text(
                text = "👆 Потяните",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 7.sp
                ),
                color = TextMuted
            )
        }
    }
}

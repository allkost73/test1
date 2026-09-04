package com.example.model

enum class TruckModule(val code: String, val displayName: String, val canId: String) {
    ECM("ECM", "Двигатель MC11 / MC13", "7E0"),
    TCU("TCU", "АКПП ZF TraXon", "7E1"),
    EBS("EBS", "Тормозная система WABCO", "7E2"),
    SCR("SCR", "Нейтрализация AdBlue / DPF", "7E4"),
    CBCU("CBCU", "Кузовная электроника VCU", "7E3")
}

enum class DtcSeverity {
    CRITICAL,
    WARNING,
    INFO
}

data class DtcCode(
    val id: String,
    val spnFmi: String,
    val obdCode: String,
    val module: TruckModule,
    val title: String,
    val description: String,
    val probableCauses: List<String>,
    val severity: DtcSeverity,
    val isActive: Boolean = true,
    val freezeFrame: Map<String, String> = emptyMap()
)

data class LiveTelemetry(
    val rpm: Float = 620f,
    val speedKmH: Float = 0f,
    val coolantTempC: Float = 84f,
    val oilPressureBar: Float = 3.8f,
    val oilTempC: Float = 88f,
    val fuelRailPressureBar: Float = 520f,
    val boostPressureBar: Float = 1.05f,
    val adBlueLevelPct: Float = 78f,
    val batteryVoltage: Float = 27.6f, // 24V commercial vehicle charging
    val fuelRateLPerH: Float = 2.4f,
    val brakeAirTank1Bar: Float = 8.4f,
    val brakeAirTank2Bar: Float = 8.2f,
    val gearCurrent: Int = 0, // 0 = Neutral
    val gearTarget: Int = 0,
    val retarderActive: Boolean = false,
    val exhaustTempC: Float = 310f,
    val dpfSootLoadPct: Float = 34f,
    val engineLoadPct: Float = 18f,
    val intakeAirTempC: Float = 24f,
    val parkingBrakeActive: Boolean = true
)

data class TruckConfiguration(
    val speedLimitKmH: Int = 90,
    val idleRpm: Int = 600,
    val reverseBuzzer: Boolean = true,
    val drlMode: String = "Автоматические ДХО",
    val headlightDelaySec: Int = 30,
    val cruiseStepKmH: Int = 1,
    val throttleProfile: String = "Стандарт",
    val adBlueDerateReset: Boolean = true,
    val dpfRegenInProgress: Boolean = false,
    val dpfRegenProgressPct: Int = 0
)

data class BluetoothDeviceInfo(
    val name: String,
    val address: String,
    val isPaired: Boolean,
    val isConnected: Boolean = false
)

sealed class ElmConnectionState {
    data object Disconnected : ElmConnectionState()
    data class Connecting(val step: String) : ElmConnectionState()
    data class Connected(val deviceName: String, val protocol: String, val isSimulation: Boolean) : ElmConnectionState()
    data class Error(val message: String) : ElmConnectionState()
}

data class TerminalLogItem(
    val timestamp: String,
    val command: String,
    val response: String,
    val isSuccess: Boolean = true
)

enum class DiagnosticTab(val title: String) {
    DASHBOARD("Приборы"),
    DTC("Ошибки DTC"),
    PARAMETERS("Параметры"),
    TUNING("Настройки"),
    TERMINAL("ELM Терминал"),
    HISTORY("История")
}

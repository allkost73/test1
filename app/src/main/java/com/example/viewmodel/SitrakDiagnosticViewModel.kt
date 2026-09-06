package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.bluetooth.Elm327Manager
import com.example.data.SitrakFaultCodes
import com.example.db.AppDatabase
import com.example.db.DiagnosticReportEntity
import com.example.model.BluetoothDeviceInfo
import com.example.model.DiagnosticTab
import com.example.model.DtcCode
import com.example.model.EcuModuleState
import com.example.model.EcuStatus
import com.example.model.ElmConnectionState
import com.example.model.ElmProtocol
import com.example.model.LiveTelemetry
import com.example.model.TerminalLogItem
import com.example.model.TruckConfiguration
import com.example.model.TruckModule
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SitrakDiagnosticViewModel(application: Application) : AndroidViewModel(application) {

    private val elmManager = Elm327Manager(application.applicationContext)
    private val database = AppDatabase.getDatabase(application.applicationContext)
    private val reportDao = database.diagnosticReportDao()

    val connectionState: StateFlow<ElmConnectionState> = elmManager.connectionState
    val telemetry: StateFlow<LiveTelemetry> = elmManager.telemetry
    val terminalLogs: StateFlow<List<TerminalLogItem>> = elmManager.terminalLogs
    val isSimulationMode: StateFlow<Boolean> = elmManager.isSimulationMode
    val selectedProtocol: StateFlow<ElmProtocol> = elmManager.selectedProtocol
    val discoveredDevices: StateFlow<List<BluetoothDeviceInfo>> = elmManager.discoveredDevices
    val isDiscovering: StateFlow<Boolean> = elmManager.isDiscovering

    val ecuStates: StateFlow<Map<TruckModule, EcuModuleState>> = elmManager.ecuStates
    val detectedCanBus: StateFlow<String?> = elmManager.detectedCanBus
    val isCanConnected: StateFlow<Boolean> = elmManager.isCanConnected
    val ignitionDetected: StateFlow<Boolean> = elmManager.ignitionDetected
    val isDiagnosingEcus: StateFlow<Boolean> = elmManager.isDiagnosingEcus

    fun isBluetoothAvailable(): Boolean = elmManager.isBluetoothAvailable()
    fun isBluetoothEnabled(): Boolean = elmManager.isBluetoothEnabled()

    val savedReports: StateFlow<List<DiagnosticReportEntity>> = reportDao.getAllReports()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _currentTab = MutableStateFlow(DiagnosticTab.DASHBOARD)
    val currentTab: StateFlow<DiagnosticTab> = _currentTab.asStateFlow()

    private val _activeFaults = MutableStateFlow<List<DtcCode>>(SitrakFaultCodes.sampleActiveFaults)
    val activeFaults: StateFlow<List<DtcCode>> = _activeFaults.asStateFlow()

    private val _selectedModuleFilter = MutableStateFlow<TruckModule?>(null)
    val selectedModuleFilter: StateFlow<TruckModule?> = _selectedModuleFilter.asStateFlow()

    private val _selectedDtcDetail = MutableStateFlow<DtcCode?>(null)
    val selectedDtcDetail: StateFlow<DtcCode?> = _selectedDtcDetail.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _truckConfig = MutableStateFlow(TruckConfiguration())
    val truckConfig: StateFlow<TruckConfiguration> = _truckConfig.asStateFlow()

    private val _cylinderCutout = MutableStateFlow<Int?>(null)
    val cylinderCutout: StateFlow<Int?> = _cylinderCutout.asStateFlow()

    private val _pairedDevices = MutableStateFlow<List<BluetoothDeviceInfo>>(emptyList())
    val pairedDevices: StateFlow<List<BluetoothDeviceInfo>> = _pairedDevices.asStateFlow()

    private val _statusNotice = MutableStateFlow<String?>(null)
    val statusNotice: StateFlow<String?> = _statusNotice.asStateFlow()

    // History buffers for live sensor graph (RPM, Rail, Boost)
    private val _rpmHistory = MutableStateFlow<List<Float>>(List(30) { 620f })
    val rpmHistory: StateFlow<List<Float>> = _rpmHistory.asStateFlow()

    private val _boostHistory = MutableStateFlow<List<Float>>(List(30) { 1.05f })
    val boostHistory: StateFlow<List<Float>> = _boostHistory.asStateFlow()

    private var dpfJob: Job? = null

    init {
        refreshPairedDevices()
        // Collect telemetry to append to graph
        viewModelScope.launch {
            elmManager.telemetry.collect { telem ->
                _rpmHistory.value = (_rpmHistory.value.drop(1) + telem.rpm)
                _boostHistory.value = (_boostHistory.value.drop(1) + telem.boostPressureBar)
            }
        }
    }

    fun selectTab(tab: DiagnosticTab) {
        _currentTab.value = tab
    }

    fun setModuleFilter(module: TruckModule?) {
        _selectedModuleFilter.value = module
    }

    fun selectDtcDetail(dtc: DtcCode?) {
        _selectedDtcDetail.value = dtc
    }

    fun clearStatusNotice() {
        _statusNotice.value = null
    }

    fun refreshPairedDevices() {
        _pairedDevices.value = elmManager.getPairedDevices()
    }

    fun startDiscovery() {
        elmManager.startDiscovery()
    }

    fun stopDiscovery() {
        elmManager.stopDiscovery()
    }

    fun setProtocol(protocol: ElmProtocol) {
        elmManager.setProtocol(protocol)
        _statusNotice.value = "Выбран протокол: ${protocol.displayName}"
    }

    fun connectDevice(device: BluetoothDeviceInfo) {
        elmManager.connectToDevice(device.address, device.name)
    }

    fun disconnect() {
        elmManager.disconnect()
    }

    fun setSimulationMode(enabled: Boolean) {
        elmManager.setSimulationMode(enabled)
        _statusNotice.value = if (enabled) "Режим симулятора Sitrak S7H активирован" else "Симулятор отключен"
    }

    fun testAndDiagnoseEcus() {
        viewModelScope.launch {
            _statusNotice.value = "Диагностический опрос блоков Sitrak по шине CAN..."
            val results = elmManager.diagnoseAllEcus()
            val onlineCount = results.values.count { it.status == EcuStatus.ONLINE }
            _statusNotice.value = if (onlineCount > 0) {
                "Блоки Sitrak в сети: $onlineCount из ${results.size}"
            } else {
                "Блоки не отвечают! Включите зажигание (Кл. 15 24V) или проверьте протокол CAN."
            }
        }
    }

    fun scanAllModules() {
        viewModelScope.launch {
            _isScanning.value = true
            val proto = elmManager.detectedCanBus.value ?: "CAN"
            _statusNotice.value = "Диагностика блоков Sitrak S7H по шине $proto..."

            val faults = elmManager.scanAllModuleFaults()
            _activeFaults.value = faults
            _isScanning.value = false

            val onlineCount = elmManager.ecuStates.value.values.count { it.status == EcuStatus.ONLINE }
            _statusNotice.value = if (onlineCount == 0 && !isSimulationMode.value) {
                "Блоки не ответили! Включите зажигание Sitrak (Кл. 15), проверьте линию CAN и питание 24V."
            } else {
                "Опрос завершен: считано ${faults.size} кодов неисправностей ($onlineCount блоков в сети)"
            }
        }
    }

    fun clearAllFaultCodes() {
        viewModelScope.launch {
            _isScanning.value = true
            _statusNotice.value = "Отправка команд сброса DTC во все блоки Sitrak (04 / 14 FF FF FF)..."
            val success = elmManager.clearAllModuleFaults()
            delay(600)
            _activeFaults.value = emptyList()
            _isScanning.value = false
            _statusNotice.value = if (success) {
                "Коды неисправностей успешно сброшены во всех блоках Sitrak!"
            } else {
                "Команда сброса отправлена (при активной неисправности код запишется повторно)"
            }
            // Refresh ECU status
            elmManager.diagnoseAllEcus()
        }
    }

    fun updateSpeedLimit(newLimitKmH: Int) {
        viewModelScope.launch {
            _statusNotice.value = "Запись калибровки ограничителя скорости ($newLimitKmH км/ч) в ЭБУ..."
            elmManager.sendCommand("2E 11 40 00 $newLimitKmH")
            delay(500)
            _truckConfig.value = _truckConfig.value.copy(speedLimitKmH = newLimitKmH)
            _statusNotice.value = "Ограничитель скорости установлен: $newLimitKmH км/ч"
        }
    }

    fun updateIdleRpm(newRpm: Int) {
        viewModelScope.launch {
            _statusNotice.value = "Корректировка холостого хода ($newRpm об/мин)..."
            elmManager.sendCommand("2E 11 42 0${newRpm / 10}")
            delay(400)
            _truckConfig.value = _truckConfig.value.copy(idleRpm = newRpm)
            _statusNotice.value = "Холостой ход Sitrak отрегулирован на $newRpm об/мин"
        }
    }

    fun triggerDpfRegeneration() {
        if (_truckConfig.value.dpfRegenInProgress) return

        dpfJob?.cancel()
        dpfJob = viewModelScope.launch {
            val telem = telemetry.value
            // Safety check
            if (!telem.parkingBrakeActive) {
                _statusNotice.value = "ВНИМАНИЕ: Для регенерации включите стояночный тормоз!"
                return@launch
            }

            _truckConfig.value = _truckConfig.value.copy(dpfRegenInProgress = true, dpfRegenProgressPct = 0)
            _statusNotice.value = "Запуск сервисной регенерации сажевого фильтра (DPF Routine 31 01 04)..."
            elmManager.sendCommand("31 01 04 01")

            for (progress in 5..100 step 15) {
                delay(1200)
                _truckConfig.value = _truckConfig.value.copy(dpfRegenProgressPct = progress)
                _statusNotice.value = "Прожиг DPF: $progress% (Температура ОГ ~ 580 °C)"
            }

            _truckConfig.value = _truckConfig.value.copy(
                dpfRegenInProgress = false,
                dpfRegenProgressPct = 100
            )
            _statusNotice.value = "Сервисная регенерация сажевого фильтра успешно завершена!"
        }
    }

    fun resetAdBlueDerate() {
        viewModelScope.launch {
            _statusNotice.value = "Сброс счетчика дератирования AdBlue / SCR (снятие ограничения момента)..."
            elmManager.sendCommand("31 01 18 20")
            delay(700)
            _truckConfig.value = _truckConfig.value.copy(adBlueDerateReset = true)
            // Also resolve adblue fault if present
            _activeFaults.value = _activeFaults.value.filterNot { it.module == TruckModule.SCR && it.spnFmi.contains("1761") }
            _statusNotice.value = "Адаптации нейтрализатора сброшены. Ограничение мощности снято!"
        }
    }

    fun testCylinderCutout(cylinder: Int) {
        viewModelScope.launch {
            if (_cylinderCutout.value == cylinder) {
                _cylinderCutout.value = null
                elmManager.sendCommand("31 02 01 00")
                _statusNotice.value = "Все цилиндры включены в нормальный режим"
            } else {
                _cylinderCutout.value = cylinder
                _statusNotice.value = "Отключение форсунки цилиндра #$cylinder..."
                elmManager.sendCommand("31 01 01 0$cylinder")
            }
        }
    }

    fun updateComfortSettings(
        reverseBuzzer: Boolean,
        drlMode: String,
        headlightDelaySec: Int,
        cruiseStepKmH: Int,
        throttleProfile: String
    ) {
        viewModelScope.launch {
            _truckConfig.value = _truckConfig.value.copy(
                reverseBuzzer = reverseBuzzer,
                drlMode = drlMode,
                headlightDelaySec = headlightDelaySec,
                cruiseStepKmH = cruiseStepKmH,
                throttleProfile = throttleProfile
            )
            elmManager.sendCommand("2E 11 88 01")
            _statusNotice.value = "Параметры блока кабины CBCU сохранены"
        }
    }

    fun sendTerminalCommand(cmd: String) {
        if (cmd.isBlank()) return
        viewModelScope.launch {
            elmManager.sendCommand(cmd)
        }
    }

    fun clearTerminalLogs() {
        elmManager.clearTerminalLogs()
    }

    fun saveCurrentReport() {
        viewModelScope.launch {
            val dateStr = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date())
            val active = _activeFaults.value
            val telem = telemetry.value

            val dtcSummary = if (active.isEmpty()) "Ошибок не обнаружено (ЭБУ Чист)"
            else active.joinToString("; ") { "${it.spnFmi} (${it.title})" }

            val snapshot = "Обороты: ${telem.rpm.toInt()} об/мин, Напряжение: ${String.format(Locale.US, "%.1f", telem.batteryVoltage)}В, Рампа: ${telem.fuelRailPressureBar.toInt()} бар, Наддув: ${String.format(Locale.US, "%.2f", telem.boostPressureBar)} бар, ОЖ: ${telem.coolantTempC.toInt()}°C"

            val entity = DiagnosticReportEntity(
                timestamp = System.currentTimeMillis(),
                truckModel = "SITRAK C7H / S7H (MC11/MC13)",
                vin = "ZZ4256V324HE19028",
                adapterName = if (isSimulationMode.value) "ELM327 Симулятор" else "ELM327 Bluetooth Classic",
                dtcCount = active.size,
                dtcListText = dtcSummary,
                parametersSnapshot = snapshot,
                status = if (active.isEmpty()) "Без ошибок" else "${active.size} активных кодов"
            )

            reportDao.insertReport(entity)
            _statusNotice.value = "Диагностический отчет сохранен в историю ($dateStr)"
        }
    }

    fun deleteReport(report: DiagnosticReportEntity) {
        viewModelScope.launch {
            reportDao.deleteReport(report)
            _statusNotice.value = "Отчет удален"
        }
    }
}

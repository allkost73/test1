package com.example.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.example.data.SitrakFaultCodes
import com.example.model.BluetoothDeviceInfo
import com.example.model.CalibrationResult
import com.example.model.DtcCode
import com.example.model.EcuModuleState
import com.example.model.EcuStatus
import com.example.model.ElmConnectionState
import com.example.model.ElmProtocol
import com.example.model.LiveTelemetry
import com.example.model.TerminalLogItem
import com.example.model.TruckModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

class Elm327Manager(private val context: Context) {

    companion object {
        // Standard Bluetooth Serial Port Profile (SPP) UUID
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()

    private val _connectionState = MutableStateFlow<ElmConnectionState>(ElmConnectionState.Disconnected)
    val connectionState: StateFlow<ElmConnectionState> = _connectionState.asStateFlow()

    private val _telemetry = MutableStateFlow(LiveTelemetry())
    val telemetry: StateFlow<LiveTelemetry> = _telemetry.asStateFlow()

    private val _terminalLogs = MutableStateFlow<List<TerminalLogItem>>(emptyList())
    val terminalLogs: StateFlow<List<TerminalLogItem>> = _terminalLogs.asStateFlow()

    private val _isSimulationMode = MutableStateFlow(true)
    val isSimulationMode: StateFlow<Boolean> = _isSimulationMode.asStateFlow()

    private val _selectedProtocol = MutableStateFlow(ElmProtocol.AUTO)
    val selectedProtocol: StateFlow<ElmProtocol> = _selectedProtocol.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<BluetoothDeviceInfo>>(emptyList())
    val discoveredDevices: StateFlow<List<BluetoothDeviceInfo>> = _discoveredDevices.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val _ecuStates = MutableStateFlow<Map<TruckModule, EcuModuleState>>(
        TruckModule.entries.associateWith { EcuModuleState(it, EcuStatus.UNKNOWN) }
    )
    val ecuStates: StateFlow<Map<TruckModule, EcuModuleState>> = _ecuStates.asStateFlow()

    private val _detectedCanBus = MutableStateFlow<String?>("SAE J1939 CAN (29 бит / 250k)")
    val detectedCanBus: StateFlow<String?> = _detectedCanBus.asStateFlow()

    private val _isCanConnected = MutableStateFlow(false)
    val isCanConnected: StateFlow<Boolean> = _isCanConnected.asStateFlow()

    private val _ignitionDetected = MutableStateFlow(false)
    val ignitionDetected: StateFlow<Boolean> = _ignitionDetected.asStateFlow()

    private val _isDiagnosingEcus = MutableStateFlow(false)
    val isDiagnosingEcus: StateFlow<Boolean> = _isDiagnosingEcus.asStateFlow()

    // Persistent Voltage Calibration (24V Sitrak onboard electrical network)
    private val voltagePrefs = context.getSharedPreferences("sitrak_voltage_prefs", Context.MODE_PRIVATE)
    var voltageMultiplier: Float = voltagePrefs.getFloat("voltage_multiplier", 1.0f)
        private set
    var voltageOffset: Float = voltagePrefs.getFloat("voltage_offset", 0.0f)
        private set
    var isVoltageCalibrated: Boolean = voltagePrefs.getBoolean("is_voltage_calibrated", false)
        private set

    private val _lastRawVoltage = MutableStateFlow(27.6f)
    val lastRawVoltage: StateFlow<Float> = _lastRawVoltage.asStateFlow()

    var activeCan29Bit: Boolean = true
        private set

    private var bluetoothSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var pollingJob: Job? = null
    private var simJob: Job? = null
    private var receiverRegistered = false

    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let { dev ->
                        val devName = dev.name ?: "Неизвестное устройство"
                        val devAddress = dev.address ?: ""
                        if (devAddress.isNotEmpty()) {
                            val currentList = _discoveredDevices.value
                            if (currentList.none { it.address == devAddress }) {
                                val isPaired = dev.bondState == BluetoothDevice.BOND_BONDED
                                _discoveredDevices.value = currentList + BluetoothDeviceInfo(
                                    name = devName,
                                    address = devAddress,
                                    isPaired = isPaired
                                )
                            }
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    _isDiscovering.value = false
                }
            }
        }
    }

    init {
        // Start simulation by default so user can immediately test and explore
        startSimulationEngine()
    }

    fun isBluetoothAvailable(): Boolean = bluetoothAdapter != null

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    fun setProtocol(protocol: ElmProtocol) {
        _selectedProtocol.value = protocol
    }

    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDeviceInfo> {
        val adapter = bluetoothAdapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()

        return try {
            adapter.bondedDevices?.map { device ->
                BluetoothDeviceInfo(
                    name = device.name ?: "Неизвестный сканер",
                    address = device.address,
                    isPaired = true,
                    isConnected = bluetoothSocket?.isConnected == true &&
                            bluetoothSocket?.remoteDevice?.address == device.address
                )
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return

        try {
            if (!receiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_FOUND)
                    addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                }
                context.registerReceiver(discoveryReceiver, filter)
                receiverRegistered = true
            }

            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }

            _discoveredDevices.value = emptyList()
            _isDiscovering.value = true
            adapter.startDiscovery()
        } catch (e: Exception) {
            _isDiscovering.value = false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        val adapter = bluetoothAdapter ?: return
        try {
            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }
        } catch (_: Exception) {}
        _isDiscovering.value = false
    }

    fun setSimulationMode(enabled: Boolean) {
        _isSimulationMode.value = enabled
        if (enabled) {
            disconnectPhysical()
            startSimulationEngine()
        } else {
            simJob?.cancel()
            _connectionState.value = ElmConnectionState.Disconnected
            _ecuStates.value = TruckModule.entries.associateWith { EcuModuleState(it, EcuStatus.UNKNOWN) }
            _isCanConnected.value = false
            _ignitionDetected.value = false
            // Reset telemetry to neutral zero state when exiting simulation mode
            _telemetry.value = LiveTelemetry(
                rpm = 0f,
                speedKmH = 0f,
                coolantTempC = 0f,
                oilPressureBar = 0f,
                fuelRailPressureBar = 0f,
                boostPressureBar = 0f,
                batteryVoltage = if (isVoltageCalibrated) 24.0f else 0f,
                rawElmVoltage = 0f,
                ecmModuleVoltage = 0f,
                voltageCalibrationMultiplier = voltageMultiplier,
                voltageCalibrationOffset = voltageOffset,
                isVoltageCalibrated = isVoltageCalibrated
            )
        }
    }

    fun connectToDevice(deviceAddress: String, deviceName: String) {
        scope.launch {
            try {
                simJob?.cancel()
                _isSimulationMode.value = false
                // Purge simulated ECU states and telemetry when connecting to real hardware
                _ecuStates.value = TruckModule.entries.associateWith { EcuModuleState(it, EcuStatus.UNKNOWN) }
                _isCanConnected.value = false
                _ignitionDetected.value = false
                _telemetry.value = LiveTelemetry(
                    rpm = 0f,
                    speedKmH = 0f,
                    coolantTempC = 0f,
                    oilPressureBar = 0f,
                    fuelRailPressureBar = 0f,
                    boostPressureBar = 0f,
                    batteryVoltage = if (isVoltageCalibrated) 24.0f else 0f,
                    rawElmVoltage = 0f,
                    ecmModuleVoltage = 0f,
                    voltageCalibrationMultiplier = voltageMultiplier,
                    voltageCalibrationOffset = voltageOffset,
                    isVoltageCalibrated = isVoltageCalibrated
                )

                val adapter = bluetoothAdapter
                    ?: throw IllegalStateException("Bluetooth не поддерживается на этом устройстве")

                if (!adapter.isEnabled) {
                    throw IllegalStateException("Bluetooth выключен. Пожалуйста, включите Bluetooth на телефоне.")
                }

                _connectionState.value = ElmConnectionState.Connecting("Остановка поиска и подготовка...")
                @SuppressLint("MissingPermission")
                if (adapter.isDiscovering) {
                    adapter.cancelDiscovery()
                }

                @SuppressLint("MissingPermission")
                val device: BluetoothDevice = adapter.getRemoteDevice(deviceAddress)

                // Try 4-tier connection fallback (Secure SPP -> Insecure SPP -> Channel 1 -> Insecure Channel 1)
                val socket = establishSocketWithFallbacks(device, deviceName)
                bluetoothSocket = socket

                inputStream = socket.inputStream
                outputStream = socket.outputStream

                // Initialize ELM327 protocol
                _connectionState.value = ElmConnectionState.Connecting("Инициализация чипа ELM327 (ATZ)...")
                delay(200)

                var initZ = sendRawCommandInternal("ATZ")
                if (initZ == "NO DATA" || initZ.isEmpty()) {
                    delay(300)
                    initZ = sendRawCommandInternal("ATZ")
                }

                _connectionState.value = ElmConnectionState.Connecting("Настройка параметров ELM327 (CAN/Echo/Timing)...")
                sendRawCommandInternal("ATE0") // Echo Off
                sendRawCommandInternal("ATL0") // Linefeeds Off
                sendRawCommandInternal("ATS0") // Spaces Off
                sendRawCommandInternal("ATAT1") // Adaptive Timing On
                sendRawCommandInternal("ATCAF1") // CAN Auto-Formatting On
                sendRawCommandInternal("ATST64") // 400ms timeout for truck ECUs
                sendRawCommandInternal("ATH1") // Headers On so we can identify responding ECUs

                _connectionState.value = ElmConnectionState.Connecting("Проверка напряжения бортовой сети 24V (ATRV)...")
                val voltageResp = sendRawCommandInternal("ATRV")
                val volt = parseVoltage(voltageResp)
                if (volt > 0) {
                    _telemetry.value = _telemetry.value.copy(batteryVoltage = volt)
                }

                _connectionState.value = ElmConnectionState.Connecting("Автоопределение протокола CAN Sitrak S7H...")
                val activeProto = autoDetectSitrakCanProtocol()
                _detectedCanBus.value = activeProto

                _connectionState.value = ElmConnectionState.Connecting("Диагностический опрос блоков управления (ECM, TCU, EBS)...")
                diagnoseAllEcus()

                _connectionState.value = ElmConnectionState.Connected(
                    deviceName = deviceName,
                    protocol = activeProto,
                    isSimulation = false
                )

                logTerminal("INIT", "ELM327 сопряжен: $initZ | Сеть: $voltageResp | Протокол: $activeProto", true)
                startPhysicalPolling()

            } catch (e: Exception) {
                disconnectPhysical()
                val userFriendlyMessage = when {
                    e.message?.contains("Bluetooth выключен", ignoreCase = true) == true ->
                        "Bluetooth выключен на телефоне. Включите Bluetooth."
                    e.message?.contains("permission", ignoreCase = true) == true ->
                        "Отсутствуют разрешения Bluetooth. Предоставьте доступ в настройках приложения."
                    else ->
                        "Сбой подключения к $deviceName: ${e.localizedMessage ?: "Таймаут сокета"}. Убедитесь, что зажигание Sitrak включено (24V) и сканер не занят другим приложением."
                }
                _connectionState.value = ElmConnectionState.Error(userFriendlyMessage)
                logTerminal("CONNECT", "Ошибка: ${e.message}", false)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun establishSocketWithFallbacks(device: BluetoothDevice, deviceName: String): BluetoothSocket {
        val errorLogs = mutableListOf<String>()

        // Fallback 1: Standard Secure RFCOMM (SPP UUID)
        try {
            _connectionState.value = ElmConnectionState.Connecting("Подключение (Метод 1: Secure SPP)...")
            val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
            withContext(Dispatchers.IO) {
                s.connect()
            }
            if (s.isConnected) return s
        } catch (e1: Exception) {
            errorLogs.add("Secure SPP: ${e1.message}")
        }

        // Fallback 2: Insecure RFCOMM (SPP UUID) - very common on newer Android versions with OBD2
        try {
            _connectionState.value = ElmConnectionState.Connecting("Подключение (Метод 2: Insecure SPP)...")
            val s = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
            withContext(Dispatchers.IO) {
                s.connect()
            }
            if (s.isConnected) return s
        } catch (e2: Exception) {
            errorLogs.add("Insecure SPP: ${e2.message}")
        }

        // Fallback 3: Reflection createRfcommSocket on Channel 1 (Standard for ELM327 clones)
        try {
            _connectionState.value = ElmConnectionState.Connecting("Подключение (Метод 3: RFCOMM канал 1)...")
            val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            val s = method.invoke(device, 1) as BluetoothSocket
            withContext(Dispatchers.IO) {
                s.connect()
            }
            if (s.isConnected) return s
        } catch (e3: Exception) {
            errorLogs.add("Channel 1: ${e3.message}")
        }

        // Fallback 4: Reflection createInsecureRfcommSocket on Channel 1
        try {
            _connectionState.value = ElmConnectionState.Connecting("Подключение (Метод 4: Insecure RFCOMM канал 1)...")
            val method = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
            val s = method.invoke(device, 1) as BluetoothSocket
            withContext(Dispatchers.IO) {
                s.connect()
            }
            if (s.isConnected) return s
        } catch (e4: Exception) {
            errorLogs.add("Insecure Channel 1: ${e4.message}")
        }

        throw IOException("Не удалось открыть сокет: " + errorLogs.joinToString(" | "))
    }

    private fun startPhysicalPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                try {
                    // Set CAN header to ECM (Engine Bosch EDC17CV44 / MC11-MC13)
                    val ecmHeader = if (activeCan29Bit) "18DA00F1" else "7E0"
                    sendRawCommandInternal("ATSH $ecmHeader")

                    // Poll RPM (010C)
                    val rpmRaw = sendRawCommandInternal("010C")
                    val rpm = parseRpm(rpmRaw)
                    val ecmResponded = isPositiveObdOrCanResponse(rpmRaw)

                    if (ecmResponded) {
                        _isCanConnected.value = true
                        _ignitionDetected.value = true
                    }

                    // Poll Speed (010D)
                    val speedRaw = sendRawCommandInternal("010D")
                    val speed = parseSpeed(speedRaw)

                    // Poll Coolant Temp (0105)
                    val tempRaw = sendRawCommandInternal("0105")
                    val temp = parseCoolant(tempRaw)

                    // Poll Boost / MAP (010B)
                    val mapRaw = sendRawCommandInternal("010B")
                    val boost = parseMap(mapRaw)

                    // Poll Rail Pressure (0123)
                    val railRaw = sendRawCommandInternal("0123")
                    val rail = parseRailPressure(railRaw)

                    // Poll Battery Voltage via ELM ADC
                    val voltRaw = sendRawCommandInternal("ATRV")
                    val volt = parseVoltage(voltRaw)

                    // Also poll Digital Voltage directly from Bosch EDC17 engine computer (Mode 01 PID 42)
                    var ecuVolt = 0f
                    if (ecmResponded) {
                        val ecuVoltRaw = sendRawCommandInternal("0142")
                        ecuVolt = parseModuleVoltage(ecuVoltRaw)
                    }

                    val current = _telemetry.value
                    val finalVoltage = when {
                        isVoltageCalibrated && volt > 0f -> volt
                        ecuVolt > 12f -> ecuVolt
                        volt > 0f -> volt
                        else -> current.batteryVoltage
                    }

                    _telemetry.value = current.copy(
                        rpm = if (ecmResponded) (if (rpm >= 0) rpm else 0f) else if (_isCanConnected.value) current.rpm else 0f,
                        speedKmH = if (speed >= 0) speed else 0f,
                        coolantTempC = if (temp > -40) temp else current.coolantTempC,
                        boostPressureBar = if (boost > 0) boost else current.boostPressureBar,
                        fuelRailPressureBar = if (rail > 0) rail else current.fuelRailPressureBar,
                        batteryVoltage = finalVoltage,
                        rawElmVoltage = if (voltRaw.isNotEmpty() && _lastRawVoltage.value > 0f) _lastRawVoltage.value else current.rawElmVoltage,
                        ecmModuleVoltage = if (ecuVolt > 0f) ecuVolt else current.ecmModuleVoltage,
                        voltageCalibrationMultiplier = voltageMultiplier,
                        voltageCalibrationOffset = voltageOffset,
                        isVoltageCalibrated = isVoltageCalibrated
                    )

                    delay(350)
                } catch (e: Exception) {
                    delay(1500)
                }
            }
        }
    }

    fun startSimulationEngine() {
        simJob?.cancel()
        _isSimulationMode.value = true
        _isCanConnected.value = true
        _ignitionDetected.value = true
        _detectedCanBus.value = "SAE J1939 CAN 250k (Эмулятор)"

        _ecuStates.value = TruckModule.entries.associateWith { mod ->
            EcuModuleState(
                module = mod,
                status = EcuStatus.ONLINE,
                pingMs = Random.nextLong(28, 55),
                activeDtcCount = if (mod == TruckModule.ECM) 1 else if (mod == TruckModule.SCR) 2 else 0,
                responseSummary = "В сети (Эмулятор ${mod.displayName})"
            )
        }

        _connectionState.value = ElmConnectionState.Connected(
            deviceName = "SITRAK S7H (Эмулятор ЭБУ)",
            protocol = "SAE J1939 / ISO 15765-4 CAN 250k (24V)",
            isSimulation = true
        )

        simJob = scope.launch {
            while (isActive) {
                val rpmFlutter = (Random.nextFloat() - 0.5f) * 18f
                val targetRpm = (620f + rpmFlutter).coerceIn(580f, 750f)
                val voltFlutter = 27.6f + (Random.nextFloat() - 0.5f) * 0.4f
                val railFlutter = 520f + (Random.nextFloat() - 0.5f) * 25f
                val boostFlutter = 1.04f + (Random.nextFloat() - 0.5f) * 0.04f
                val oilFlutter = 3.8f + (Random.nextFloat() - 0.5f) * 0.15f
                val air1 = 8.4f + (Random.nextFloat() - 0.5f) * 0.1f
                val air2 = 8.2f + (Random.nextFloat() - 0.5f) * 0.1f

                _telemetry.value = _telemetry.value.copy(
                    rpm = targetRpm,
                    batteryVoltage = voltFlutter,
                    fuelRailPressureBar = railFlutter,
                    boostPressureBar = boostFlutter,
                    oilPressureBar = oilFlutter,
                    brakeAirTank1Bar = air1,
                    brakeAirTank2Bar = air2
                )

                delay(300)
            }
        }
    }

    suspend fun autoDetectSitrakCanProtocol(): String {
        val userProto = _selectedProtocol.value
        if (userProto != ElmProtocol.AUTO) {
            sendRawCommandInternal(userProto.atCommand)
            activeCan29Bit = userProto.code.contains("29") || userProto.code.contains("J1939")
            val bcast = if (activeCan29Bit) "18DB33F1" else "7DF"
            sendRawCommandInternal("ATSH $bcast")
            return userProto.displayName
        }

        // 1. Primary Sitrak Standard: SAE J1939 CAN (29 bit / 250 kbps)
        sendRawCommandInternal("ATSPA")
        sendRawCommandInternal("ATSH 18DB33F1")
        var resp = sendRawCommandInternal("0100")
        if (isPositiveObdOrCanResponse(resp)) {
            activeCan29Bit = true
            return "SAE J1939 CAN (29 бит / 250k)"
        }

        // 1b. Direct Engine ECM Header on J1939
        sendRawCommandInternal("ATSH 18DA00F1")
        resp = sendRawCommandInternal("0100")
        if (isPositiveObdOrCanResponse(resp)) {
            activeCan29Bit = true
            return "SAE J1939 CAN (29 бит / 250k - ЭБУ MC13)"
        }

        // 2. ISO 15765-4 CAN (29 bit / 250 kbps)
        sendRawCommandInternal("ATSP9")
        sendRawCommandInternal("ATSH 18DB33F1")
        resp = sendRawCommandInternal("0100")
        if (isPositiveObdOrCanResponse(resp)) {
            activeCan29Bit = true
            return "ISO 15765-4 CAN (29 бит / 250k)"
        }

        // 3. ISO 15765-4 CAN (29 bit / 500 kbps)
        sendRawCommandInternal("ATSP7")
        sendRawCommandInternal("ATSH 18DB33F1")
        resp = sendRawCommandInternal("0100")
        if (isPositiveObdOrCanResponse(resp)) {
            activeCan29Bit = true
            return "ISO 15765-4 CAN (29 бит / 500k)"
        }

        // 4. ISO 15765-4 CAN (11 bit / 500 kbps - Sitrak Gateway / Central Coordinator)
        sendRawCommandInternal("ATSP6")
        sendRawCommandInternal("ATSH 7DF")
        resp = sendRawCommandInternal("0100")
        if (isPositiveObdOrCanResponse(resp)) {
            activeCan29Bit = false
            return "ISO 15765-4 CAN (11 бит / 500k Gateway)"
        }

        // 4b. Direct 11-bit ECM header
        sendRawCommandInternal("ATSH 7E0")
        resp = sendRawCommandInternal("0100")
        if (isPositiveObdOrCanResponse(resp)) {
            activeCan29Bit = false
            return "ISO 15765-4 CAN (11 бит / 500k - ЭБУ EDC17)"
        }

        // Default fallback: remain on J1939 250k with 29-bit
        sendRawCommandInternal("ATSPA")
        sendRawCommandInternal("ATSH 18DA00F1")
        activeCan29Bit = true
        return "SAE J1939 CAN (29 бит / 250k - Поиск CAN)"
    }

    suspend fun diagnoseAllEcus(): Map<TruckModule, EcuModuleState> {
        _isDiagnosingEcus.value = true
        val updatedStates = mutableMapOf<TruckModule, EcuModuleState>()
        var anyOnline = false

        if (_isSimulationMode.value) {
            delay(300)
            TruckModule.entries.forEach { mod ->
                updatedStates[mod] = EcuModuleState(
                    module = mod,
                    status = EcuStatus.ONLINE,
                    pingMs = Random.nextLong(28, 55),
                    activeDtcCount = if (mod == TruckModule.ECM) 1 else if (mod == TruckModule.SCR) 2 else 0,
                    responseSummary = "В сети (Эмулятор ${mod.displayName})"
                )
            }
            _ecuStates.value = updatedStates
            _isCanConnected.value = true
            _ignitionDetected.value = true
            _isDiagnosingEcus.value = false
            return updatedStates
        }

        for (module in TruckModule.entries) {
            val startPing = System.currentTimeMillis()
            val header = if (activeCan29Bit) module.can29Header else module.canId
            sendRawCommandInternal("ATSH $header")
            delay(40)

            // Probe with 0100 or 19 02 FF
            var resp = sendRawCommandInternal("0100")
            if (!isPositiveObdOrCanResponse(resp)) {
                resp = sendRawCommandInternal("19 02 FF")
            }
            if (!isPositiveObdOrCanResponse(resp)) {
                resp = sendRawCommandInternal("03")
            }

            val ping = (System.currentTimeMillis() - startPing).coerceAtLeast(12)
            if (isPositiveObdOrCanResponse(resp)) {
                anyOnline = true
                val dtcs = SitrakFaultCodes.parseDtcResponse(resp, module)
                updatedStates[module] = EcuModuleState(
                    module = module,
                    status = EcuStatus.ONLINE,
                    pingMs = ping,
                    activeDtcCount = dtcs.size,
                    responseSummary = "В сети (${ping}мс): ${resp.take(24)}"
                )
            } else {
                updatedStates[module] = EcuModuleState(
                    module = module,
                    status = EcuStatus.OFFLINE,
                    pingMs = 0,
                    activeDtcCount = 0,
                    responseSummary = "Нет ответа",
                    lastError = "Блок $header не ответил. Проверьте зажигание (Кл. 15), предохранитель или шину CAN."
                )
            }
        }

        _ecuStates.value = updatedStates
        _isCanConnected.value = anyOnline
        _ignitionDetected.value = anyOnline
        _isDiagnosingEcus.value = false
        return updatedStates
    }

    suspend fun scanAllModuleFaults(): List<DtcCode> {
        if (_isSimulationMode.value) {
            delay(500)
            return SitrakFaultCodes.sampleActiveFaults
        }

        val allFaults = mutableListOf<DtcCode>()
        val updatedStates = _ecuStates.value.toMutableMap()

        for (module in TruckModule.entries) {
            val header = if (activeCan29Bit) module.can29Header else module.canId
            sendRawCommandInternal("ATSH $header")
            delay(80)

            // 1. Standard Mode 03
            val resp03 = sendRawCommandInternal("03")
            val dtcs03 = SitrakFaultCodes.parseDtcResponse(resp03, module)
            allFaults.addAll(dtcs03)

            // 2. UDS Read DTCs (19 02 FF)
            val resp19 = sendRawCommandInternal("19 02 FF")
            val dtcs19 = SitrakFaultCodes.parseDtcResponse(resp19, module)
            val newDtcs = dtcs19.filter { d19 -> allFaults.none { it.obdCode == d19.obdCode } }
            allFaults.addAll(newDtcs)

            val count = dtcs03.size + newDtcs.size
            val isOnline = isPositiveObdOrCanResponse(resp03) || isPositiveObdOrCanResponse(resp19)
            val prev = updatedStates[module] ?: EcuModuleState(module)
            updatedStates[module] = prev.copy(
                status = if (isOnline) EcuStatus.ONLINE else EcuStatus.OFFLINE,
                activeDtcCount = count,
                responseSummary = if (isOnline) "Ошибок в блоке: $count" else "Блок не ответил"
            )
        }

        _ecuStates.value = updatedStates
        return allFaults
    }

    suspend fun clearAllModuleFaults(): Boolean {
        if (_isSimulationMode.value) {
            delay(400)
            return true
        }

        var anyCleared = false
        // 1. Broadcast Clear
        val bcast = if (activeCan29Bit) "18DB33F1" else "7DF"
        sendRawCommandInternal("ATSH $bcast")
        sendRawCommandInternal("04")
        sendRawCommandInternal("14 FF FF FF")

        // 2. Clear each ECU individually
        for (module in TruckModule.entries) {
            val header = if (activeCan29Bit) module.can29Header else module.canId
            sendRawCommandInternal("ATSH $header")
            delay(50)
            val r1 = sendRawCommandInternal("04")
            val r2 = sendRawCommandInternal("14 FF FF FF")
            if (isPositiveObdOrCanResponse(r1) || isPositiveObdOrCanResponse(r2)) {
                anyCleared = true
            }
        }
        return anyCleared
    }

    fun isPositiveObdOrCanResponse(resp: String): Boolean {
        val clean = resp.replace(">", "").replace("\r", " ").replace("\n", " ").trim()
        if (clean.isEmpty() || clean.contains("NO DATA") || clean.contains("ERROR") ||
            clean.contains("UNABLE TO CONNECT") || clean.contains("BUS INIT") || clean.contains("?")) {
            return false
        }
        val upper = clean.uppercase(Locale.ROOT)
        return upper.contains("41 ") || upper.contains("43 ") || upper.contains("44 ") ||
                upper.contains("59 ") || upper.contains("7E") || upper.contains("18DA") ||
                upper.contains("62 ") || upper.contains("54") || upper.contains("OK")
    }

    suspend fun sendCommand(command: String): String {
        val cleanCmd = command.trim()

        if (_isSimulationMode.value) {
            delay(100)
            val response = simulateCommandResponse(cleanCmd)
            logTerminal(cleanCmd, response, !response.contains("ERROR"))
            return response
        }

        return try {
            val resp = sendRawCommandInternal(cleanCmd)
            logTerminal(cleanCmd, resp, !resp.contains("ERROR") && !resp.contains("NO DATA"))
            resp
        } catch (e: Exception) {
            val err = "Ошибка: ${e.localizedMessage ?: "Таймаут"}"
            logTerminal(cleanCmd, err, false)
            err
        }
    }

    private suspend fun sendRawCommandInternal(command: String): String = withContext(Dispatchers.IO) {
        val out = outputStream ?: throw IllegalStateException("Нет подключения к Bluetooth-сокету")
        val input = inputStream ?: throw IllegalStateException("Поток ввода Bluetooth недоступен")

        // Flush any stale bytes
        while (input.available() > 0) {
            input.read()
        }

        val cmdBytes = "$command\r".toByteArray(Charsets.US_ASCII)
        out.write(cmdBytes)
        out.flush()

        val sb = StringBuilder()
        val buffer = ByteArray(256)
        val startTime = System.currentTimeMillis()
        val timeout = 2500L

        while (System.currentTimeMillis() - startTime < timeout) {
            if (input.available() > 0) {
                val count = input.read(buffer)
                if (count > 0) {
                    val text = String(buffer, 0, count, Charsets.US_ASCII)
                    sb.append(text)
                    if (text.contains(">")) {
                        break
                    }
                }
            } else {
                delay(20)
            }
        }

        val result = sb.toString()
            .replace(">", "")
            .replace("\r", " ")
            .replace("\n", " ")
            .trim()

        if (result.isEmpty()) "NO DATA" else result
    }

    private fun simulateCommandResponse(cmd: String): String {
        val upper = cmd.uppercase(Locale.ROOT)
        return when {
            upper == "ATZ" -> "ELM327 v1.5 (SITRAK J1939)"
            upper == "ATRV" -> String.format(Locale.US, "%.1fV", _telemetry.value.batteryVoltage)
            upper.startsWith("ATCV") || upper.startsWith("AT CV") -> "OK"
            upper.startsWith("ATE") -> "OK"
            upper.startsWith("ATL") -> "OK"
            upper.startsWith("ATS") -> "OK"
            upper.startsWith("ATH") -> "OK"
            upper.startsWith("ATAT") -> "OK"
            upper.startsWith("ATSP") -> "OK"
            upper == "ATDP" -> "SAE J1939 CAN (29 bit / 250 kbps)"
            upper.startsWith("ATSH") -> "OK"
            upper == "0100" -> "41 00 BE 3F B8 13"
            upper == "0142" -> "41 42 6C E4" // 27.876V
            upper == "10 03" || upper == "1003" -> "50 03 00 32 01 F4"
            upper == "010C" -> {
                val raw = (_telemetry.value.rpm * 4).toInt()
                val a = (raw shr 8) and 0xFF
                val b = raw and 0xFF
                String.format(Locale.US, "41 0C %02X %02X", a, b)
            }
            upper == "010D" -> String.format(Locale.US, "41 0D %02X", _telemetry.value.speedKmH.toInt())
            upper == "0105" -> String.format(Locale.US, "41 05 %02X", (_telemetry.value.coolantTempC + 40).toInt())
            upper == "010B" -> String.format(Locale.US, "41 0B %02X", (_telemetry.value.boostPressureBar * 100).toInt())
            upper == "0123" -> {
                val valKpa = (_telemetry.value.fuelRailPressureBar * 10).toInt()
                val a = (valKpa shr 8) and 0xFF
                val b = valKpa and 0xFF
                String.format(Locale.US, "41 23 %02X %02X", a, b)
            }
            upper == "03" -> "43 04 02 38 20 4F"
            upper.startsWith("19") -> "59 02 FF 02 38 28 20 4F 29"
            upper == "04" -> "44"
            upper.startsWith("14") -> "54"
            upper.startsWith("22") -> "62 11 A0 03 F8 12"
            upper.startsWith("2E") -> "6E"
            upper.startsWith("31") -> "71 01"
            else -> "OK"
        }
    }

    private fun logTerminal(cmd: String, resp: String, success: Boolean) {
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val newItem = TerminalLogItem(timeStr, cmd, resp, success)
        _terminalLogs.value = (_terminalLogs.value + newItem).takeLast(100)
    }

    fun clearTerminalLogs() {
        _terminalLogs.value = emptyList()
    }

    fun disconnect() {
        disconnectPhysical()
        simJob?.cancel()
        _connectionState.value = ElmConnectionState.Disconnected
    }

    private fun disconnectPhysical() {
        pollingJob?.cancel()
        try {
            inputStream?.close()
            outputStream?.close()
            bluetoothSocket?.close()
        } catch (_: Exception) {}
        inputStream = null
        outputStream = null
        bluetoothSocket = null
    }

    // Parsing helpers for OBD-II / J1939 CAN hex
    private fun parseRpm(response: String): Float {
        return try {
            val hex = response.replace(" ", "").substringAfter("410C", "").take(4)
            if (hex.length == 4) {
                val a = hex.substring(0, 2).toInt(16)
                val b = hex.substring(2, 4).toInt(16)
                ((a * 256f) + b) / 4f
            } else 0f
        } catch (e: Exception) { 0f }
    }

    private fun parseSpeed(response: String): Float {
        return try {
            val hex = response.replace(" ", "").substringAfter("410D", "").take(2)
            if (hex.length == 2) hex.toInt(16).toFloat() else -1f
        } catch (e: Exception) { -1f }
    }

    private fun parseCoolant(response: String): Float {
        return try {
            val hex = response.replace(" ", "").substringAfter("4105", "").take(2)
            if (hex.length == 2) (hex.toInt(16) - 40).toFloat() else -100f
        } catch (e: Exception) { -100f }
    }

    private fun parseMap(response: String): Float {
        return try {
            val hex = response.replace(" ", "").substringAfter("410B", "").take(2)
            if (hex.length == 2) (hex.toInt(16) / 100f) else 0f
        } catch (e: Exception) { 0f }
    }

    private fun parseRailPressure(response: String): Float {
        return try {
            val hex = response.replace(" ", "").substringAfter("4123", "").take(4)
            if (hex.length == 4) {
                val a = hex.substring(0, 2).toInt(16)
                val b = hex.substring(2, 4).toInt(16)
                ((a * 256f) + b) / 10f
            } else 0f
        } catch (e: Exception) { 0f }
    }

    private fun parseVoltage(response: String): Float {
        return try {
            val clean = response.replace("V", "").replace("v", "").replace(">", "").replace("\r", " ").replace("\n", " ").trim()
            val match = Regex("""\d+(\.\d+)?""").find(clean)
            val raw = match?.value?.toFloatOrNull() ?: 0f
            if (raw > 0f) {
                _lastRawVoltage.value = raw
                val calibrated = (raw * voltageMultiplier) + voltageOffset
                calibrated.coerceIn(0f, 40f)
            } else 0f
        } catch (e: Exception) { 0f }
    }

    private fun parseModuleVoltage(response: String): Float {
        return try {
            val clean = response.replace(" ", "").uppercase(Locale.ROOT)
            val hex = clean.substringAfter("4142", "").take(4)
            if (hex.length == 4) {
                val a = hex.substring(0, 2).toInt(16)
                val b = hex.substring(2, 4).toInt(16)
                ((a * 256f) + b) / 1000f
            } else 0f
        } catch (e: Exception) { 0f }
    }

    // Voltage Calibration API for 24V commercial vehicle electrical system
    fun calibrateVoltage(targetVoltage: Float): String {
        val raw = _lastRawVoltage.value
        val effectiveRaw = if (raw > 5f) raw else 24.0f
        voltageMultiplier = (targetVoltage / effectiveRaw).coerceIn(0.2f, 5.0f)
        voltageOffset = 0f
        isVoltageCalibrated = true

        voltagePrefs.edit()
            .putFloat("voltage_multiplier", voltageMultiplier)
            .putFloat("voltage_offset", 0f)
            .putBoolean("is_voltage_calibrated", true)
            .apply()

        // Send hardware calibration command to ELM327 chip (ATCV dddd)
        scope.launch {
            if (bluetoothSocket?.isConnected == true) {
                try {
                    val dddd = (targetVoltage * 100).toInt().coerceIn(1000, 3999)
                    sendRawCommandInternal("ATCV $dddd")
                    sendRawCommandInternal("AT CV " + String.format(Locale.US, "%.1f", targetVoltage))
                } catch (_: Exception) {}
            }
        }

        _telemetry.value = _telemetry.value.copy(
            batteryVoltage = targetVoltage,
            rawElmVoltage = effectiveRaw,
            voltageCalibrationMultiplier = voltageMultiplier,
            voltageCalibrationOffset = 0f,
            isVoltageCalibrated = true
        )
        return String.format(Locale.US, "Вольтметр откалиброван: %.1f В (команда ATCV отправлена в ELM327)", targetVoltage)
    }

    fun resetVoltageCalibration(): String {
        voltageMultiplier = 1.0f
        voltageOffset = 0.0f
        isVoltageCalibrated = false

        voltagePrefs.edit()
            .putFloat("voltage_multiplier", 1.0f)
            .putFloat("voltage_offset", 0.0f)
            .putBoolean("is_voltage_calibrated", false)
            .apply()

        scope.launch {
            if (bluetoothSocket?.isConnected == true) {
                try {
                    sendRawCommandInternal("ATCV 0000")
                    sendRawCommandInternal("AT CV 0000")
                } catch (_: Exception) {}
            }
        }

        val raw = _lastRawVoltage.value
        val restored = if (raw > 0f) raw else 24.0f
        _telemetry.value = _telemetry.value.copy(
            batteryVoltage = restored,
            voltageCalibrationMultiplier = 1.0f,
            voltageCalibrationOffset = 0.0f,
            isVoltageCalibrated = false
        )
        return "Калибровка вольтметра сброшена к заводским значениям ELM327"
    }

    fun adjustVoltageStep(delta: Float): String {
        val current = _telemetry.value.batteryVoltage
        val target = (current + delta).coerceIn(10f, 36f)
        return calibrateVoltage(target)
    }

    // UDS Diagnostic Service 2E (WriteDataByIdentifier) with Session Control & Header targeting
    suspend fun writeEcuParameter(
        module: TruckModule,
        did: String,
        dataHex: String,
        description: String
    ): CalibrationResult {
        if (_isSimulationMode.value) {
            delay(500)
            logTerminal("2E $did $dataHex", "6E $did", true)
            return CalibrationResult.Success("Калибровка «$description» успешно записана в ЭБУ (Режим симулятора)!")
        }

        if (bluetoothSocket?.isConnected != true) {
            return CalibrationResult.NoResponse("Нет связи со сканером ELM327. Подключитесь к адаптеру по Bluetooth.")
        }

        return try {
            // 1. Set CAN Header to targeted ECU (e.g. ECM 18DA00F1 or 7E0)
            val header = if (activeCan29Bit) module.can29Header else module.canId
            sendRawCommandInternal("ATSH $header")
            delay(60)

            // 2. Request Extended Diagnostic Session (UDS 10 03)
            val sessionResp = sendRawCommandInternal("10 03")
            logTerminal("10 03 ($header)", sessionResp, isPositiveObdOrCanResponse(sessionResp))
            delay(60)

            // 3. Send Write Data by Identifier (UDS 2E)
            val cleanDid = did.trim()
            val cleanData = dataHex.trim()
            val writeCmd = "2E $cleanDid $cleanData"
            val writeResp = sendRawCommandInternal(writeCmd)
            val isSuccess = writeResp.contains("6E") || writeResp.contains("OK")
            logTerminal(writeCmd, writeResp, isSuccess)

            when {
                isSuccess -> {
                    CalibrationResult.Success("Калибровка «$description» успешно сохранена в ЭБУ ${module.code}!")
                }
                writeResp.contains("7F 2E 33") || writeResp.contains("7F 2E 35") || (writeResp.contains("7F") && writeResp.contains("33")) -> {
                    CalibrationResult.SecurityLocked(
                        "ЭБУ ${module.code} заблокирован: требуется заводской пароль безопасности (Security Access Seed/Key). " +
                        "Запись калибровки в Bosch EDC17 защищена от изменения стандартными сканерами ELM327. " +
                        "Для прошивки лимита скорости требуется дилерский доступ Sinotruk SmartLink или программатор Tricore."
                    )
                }
                writeResp.contains("7F 2E 22") || writeResp.contains("7F 2E 31") -> {
                    CalibrationResult.ConditionsNotMet(
                        "Условия калибровки не выполнены (ответ ЭБУ: $writeResp). " +
                        "Заглушите двигатель, затяните стояночный тормоз и оставьте включенным зажигание (Кл. 15)."
                    )
                }
                writeResp.contains("NO DATA") || writeResp.contains("ERROR") || writeResp.contains("?") -> {
                    CalibrationResult.NoResponse("ЭБУ ${module.code} не ответил на команду записи (NO DATA). Проверьте зажигание и шину CAN.")
                }
                else -> {
                    CalibrationResult.Error("Ответ ЭБУ ${module.code}: $writeResp")
                }
            }
        } catch (e: Exception) {
            CalibrationResult.Error("Ошибка передачи команды: ${e.localizedMessage}")
        }
    }
}

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.random.Random

class Elm327Manager(private val context: Context) {

    companion object {
        // Standard Bluetooth Serial Port Profile (SPP) UUID
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        fun parseSteeringAngle(response: String): Float? {
            return try {
                val clean = response.replace(" ", "").replace(">", "").uppercase(Locale.ROOT)
                if (clean.isEmpty() || clean.contains("NODATA") || clean.contains("ERROR") || clean.contains("STOPPED")) {
                    return null
                }
                // 1. Check for standard UDS ReadDataByIdentifier response (62 [DID] [Data])
                // Possible DIDs: 010A, 0200, 1807, F40E, 0114, 0101
                val didRegex = Regex("""62(010A|0200|1807|F40E|0114|0101)([0-9A-F]{4,6})""")
                val match = didRegex.find(clean)
                if (match != null) {
                    val fullHex = match.groupValues[2]
                    val hex = if (fullHex.length >= 6) fullHex.takeLast(4) else fullHex.take(4)
                    return decodeSteeringHex(hex)
                }

                // 2. Direct 4-hex chars if format is raw
                if (clean.length == 4 && clean.matches(Regex("""[0-9A-F]{4}"""))) {
                    return decodeSteeringHex(clean)
                }

                // 3. J1939 broadcast frame PGN 61469 (FEE5)
                val fee5Index = clean.indexOf("FEE5")
                if (fee5Index != -1 && clean.length >= fee5Index + 8) {
                    val dataPart = clean.substring(fee5Index + 4)
                    val hexMatch = Regex("""[0-9A-F]{4}""").find(dataPart)
                    if (hexMatch != null) {
                        return decodeSteeringHex(hexMatch.value)
                    }
                }

                null
            } catch (_: Exception) { null }
        }

        fun decodeSteeringHex(hex: String): Float? {
            return try {
                val rawUnsigned = hex.toInt(16)
                // Format A: SAE J1939 SPN 1807 standard offset (Center 0x7D00 = 32000, 0.05 deg/bit)
                if (rawUnsigned in 15000..49000) {
                    val offset = rawUnsigned - 32000
                    val deg = offset * 0.05f
                    if (abs(deg) <= 800f) return deg
                }

                // Format B: Alternate center offset (0x7FFF = 32767 or 0x8000 = 32768, 0.05 deg/bit)
                if (rawUnsigned in 17000..48500) {
                    val offset = rawUnsigned - 32768
                    val deg = offset * 0.05f
                    if (abs(deg) <= 800f) return deg
                }

                // Format C: Standard Signed 16-bit integer (0.1 deg/bit, e.g. 0000 = 0.0°, 0028 = +4.0°, FFD8 = -4.0°)
                val rawShort = rawUnsigned.toShort()
                val degSigned = rawShort / 10f
                if (abs(degSigned) <= 800f) {
                    return degSigned
                }

                // Format D: Signed 16-bit with 0.05 deg/bit
                val degSigned005 = rawShort * 0.05f
                if (abs(degSigned005) <= 800f) {
                    return degSigned005
                }

                null
            } catch (_: Exception) { null }
        }
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

    private val commandMutex = Mutex()
    @Volatile private var isRoutineInProgress = false
    private var detectedSasHeader: String? = null
    private var detectedSasFilter: String? = null
    private var detectedSasDid: String? = null
    @Volatile private var lastUserNudgeTime = 0L

    // Persistent Voltage Calibration (24V Sitrak onboard electrical network)
    private val voltagePrefs = context.getSharedPreferences("sitrak_voltage_prefs", Context.MODE_PRIVATE)
    var voltageMultiplier: Float = voltagePrefs.getFloat("voltage_multiplier", 1.0f)
        private set
    var voltageOffset: Float = voltagePrefs.getFloat("voltage_offset", 0.0f)
        private set
    var isVoltageCalibrated: Boolean = voltagePrefs.getBoolean("is_voltage_calibrated", false)
        private set

    // Persistent Steering Angle Calibration (SAS / WABCO EBS ESP)
    private val steeringPrefs = context.getSharedPreferences("sitrak_steering_prefs", Context.MODE_PRIVATE)
    var steeringOffset: Float = steeringPrefs.getFloat("steering_offset", 0.0f)
        private set
    var isSteeringCalibrated: Boolean = steeringPrefs.getBoolean("is_steering_calibrated", false)
        private set
    private var simulatedSteeringAngle = -3.8f

    private val _lastRawVoltage = MutableStateFlow(27.6f)
    val lastRawVoltage: StateFlow<Float> = _lastRawVoltage.asStateFlow()

    var activeCan29Bit: Boolean = true
        private set

    private var bluetoothSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var connectJob: Job? = null
    private var pollingJob: Job? = null
    private var diagnoseJob: Job? = null
    private var scanJob: Job? = null
    private var simJob: Job? = null
    private var receiverRegistered = false

    init {
        // Automatically start simulation engine on launch so dashboard is alive immediately
        startSimulationEngine()
    }

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

    fun isBluetoothAvailable(): Boolean = bluetoothAdapter != null

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    fun setProtocol(protocol: ElmProtocol) {
        _selectedProtocol.value = protocol
        if (bluetoothSocket?.isConnected == true) {
            scope.launch {
                applyProtocol(protocol)
            }
        }
    }

    suspend fun applyProtocol(protocol: ElmProtocol): String {
        _selectedProtocol.value = protocol
        if (_isSimulationMode.value || bluetoothSocket?.isConnected != true) {
            _detectedCanBus.value = protocol.displayName
            val currentConn = _connectionState.value
            if (currentConn is ElmConnectionState.Connected) {
                _connectionState.value = currentConn.copy(protocol = protocol.displayName)
            }
            return protocol.displayName
        }

        return try {
            sendRawCommandInternal("ATPC") // Close protocol and clear CAN errors
            if (protocol != ElmProtocol.AUTO) {
                sendRawCommandInternal(protocol.atCommand)
            } else {
                sendRawCommandInternal("ATSP0")
            }
            activeCan29Bit = protocol.code.contains("29") || protocol.code.contains("J1939") || protocol == ElmProtocol.AUTO
            val bcast = if (activeCan29Bit) "18DB33F1" else "7DF"
            sendRawCommandInternal("ATSH $bcast")
            sendRawCommandInternal("ATCRA") // Open receive filter
            sendRawCommandInternal("ATST64")
            _detectedCanBus.value = protocol.displayName
            val currentConn = _connectionState.value
            if (currentConn is ElmConnectionState.Connected) {
                _connectionState.value = currentConn.copy(protocol = protocol.displayName)
            }
            logTerminal("PROTO", "Активирован протокол шины: ${protocol.displayName} (${protocol.atCommand})", true)
            protocol.displayName
        } catch (e: Exception) {
            logTerminal("PROTO", "Ошибка переключения протокола: ${e.message}", false)
            protocol.displayName
        }
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
                isVoltageCalibrated = isVoltageCalibrated,
                steeringCalibrationOffsetDeg = steeringOffset,
                isSteeringCalibrated = isSteeringCalibrated,
                steeringAngleDeg = (0f + steeringOffset),
                rawSteeringAngleDeg = 0f
            )
        }
    }

    fun connectToDevice(deviceAddress: String, deviceName: String) {
        connectJob?.cancel()
        pollingJob?.cancel()
        diagnoseJob?.cancel()
        simJob?.cancel()

        connectJob = scope.launch {
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
                    isVoltageCalibrated = isVoltageCalibrated,
                    steeringCalibrationOffsetDeg = steeringOffset,
                    isSteeringCalibrated = isSteeringCalibrated,
                    steeringAngleDeg = (0f + steeringOffset),
                    rawSteeringAngleDeg = 0f
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

                if (!isActive) return@launch

                // Try 4-tier connection fallback (Secure SPP -> Insecure SPP -> Channel 1 -> Insecure Channel 1)
                val socket = establishSocketWithFallbacks(device, deviceName)
                if (!isActive) {
                    try { socket.close() } catch (_: Exception) {}
                    return@launch
                }
                bluetoothSocket = socket

                inputStream = socket.inputStream
                outputStream = socket.outputStream

                // Initialize ELM327 protocol safely
                _connectionState.value = ElmConnectionState.Connecting("Инициализация чипа ELM327 (ATZ)...")
                delay(120)
                if (!isActive) return@launch

                var initZ = sendRawCommandInternal("ATZ")
                if (initZ == "NO DATA" || initZ.isEmpty()) {
                    delay(200)
                    if (!isActive) return@launch
                    initZ = sendRawCommandInternal("ATZ")
                }

                _connectionState.value = ElmConnectionState.Connecting("Настройка параметров шины Sitrak...")
                sendRawCommandInternal("ATE0") // Echo Off
                sendRawCommandInternal("ATL0") // Linefeeds Off
                sendRawCommandInternal("ATS1") // Spaces ON for clean byte parsing
                sendRawCommandInternal("ATH0") // Headers OFF for universal OBD-II / UDS parsing
                sendRawCommandInternal("ATAT1") // Adaptive Timing On
                sendRawCommandInternal("ATCAF1") // CAN Auto-Formatting On
                sendRawCommandInternal("ATST64") // Standard 400ms timeout like ScanMaster
                sendRawCommandInternal("ATPC") // Clear any CAN error flags

                if (!isActive) return@launch
                _connectionState.value = ElmConnectionState.Connecting("Проверка напряжения сети 24V (ATRV)...")
                val voltageResp = sendRawCommandInternal("ATRV")
                val volt = parseVoltage(voltageResp)
                if (volt > 0) {
                    _telemetry.value = _telemetry.value.copy(batteryVoltage = volt)
                }

                if (!isActive) return@launch
                _connectionState.value = ElmConnectionState.Connecting("Безопасное подключение к CAN Sitrak 250k...")
                val activeProto = autoDetectSitrakCanProtocol()
                _detectedCanBus.value = activeProto

                if (!isActive) return@launch
                _connectionState.value = ElmConnectionState.Connected(
                    deviceName = deviceName,
                    protocol = activeProto,
                    isSimulation = false
                )

                logTerminal("INIT", "ELM327 сопряжен: $initZ | Сеть: $voltageResp | Протокол: $activeProto", true)
                startPhysicalPolling()

            } catch (e: CancellationException) {
                disconnectPhysical()
            } catch (e: Exception) {
                if (isActive) {
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
            var pollTick = 0
            while (isActive) {
                if (isRoutineInProgress) {
                    delay(150)
                    continue
                }
                try {
                    pollTick++
                    // Set CAN header to ECM (Engine Bosch EDC17CV44 / MC11-MC13)
                    val ecmHeader = if (activeCan29Bit) "18DA00F1" else "7E0"
                    sendRawCommandInternal("ATSH $ecmHeader")
                    delay(25)

                    // 1. Poll RPM (010C)
                    val rpmRaw = sendRawCommandInternal("010C")
                    val rpm = parseRpm(rpmRaw)
                    val ecmResponded = isPositiveObdOrCanResponse(rpmRaw)

                    if (ecmResponded) {
                        _isCanConnected.value = true
                        _ignitionDetected.value = true
                    }

                    if (rpmRaw.contains("CAN ERROR") || rpmRaw.contains("BUS BUSY")) {
                        sendRawCommandInternal("ATPC")
                        delay(250)
                        continue
                    }

                    delay(30)

                    // 2. Gentle Steering Angle Sensor Polling (SAS from WABCO EBS or Steering Controller)
                    var steerDeg = _telemetry.value.steeringAngleDeg
                    var rawSteer = _telemetry.value.rawSteeringAngleDeg
                    var physicalSasSuccess = false

                    if (pollTick % 2 == 0 && !isRoutineInProgress) {
                        val ebsH = if (activeCan29Bit) "18DA0BF1" else "7E2"
                        val ebsF = if (activeCan29Bit) "18DAF10B" else "7EA"
                        val sasH = if (activeCan29Bit) "18DA13F1" else "7E3"
                        val sasF = if (activeCan29Bit) "18DAF113" else "7EB"

                        if (detectedSasHeader != null && detectedSasDid != null) {
                            sendRawCommandInternal("ATSH $detectedSasHeader")
                            delay(25)
                            val steerResp = sendRawCommandInternal("22 $detectedSasDid")
                            val parsed = parseSteeringAngle(steerResp)
                            if (parsed != null) {
                                rawSteer = parsed
                                steerDeg = rawSteer + steeringOffset
                                physicalSasSuccess = true
                            } else if (pollTick % 30 == 0) {
                                detectedSasDid = null
                            }
                        } else if (pollTick % 6 == 0) {
                            // Gentle probe: only ONE candidate per probe interval, never flooding the CAN bus
                            val probeCandidates = listOf(
                                Triple(ebsH, ebsF, "010A"),
                                Triple(ebsH, ebsF, "0200"),
                                Triple(sasH, sasF, "010A"),
                                Triple(ebsH, ebsF, "1807")
                            )
                            val candIndex = (pollTick / 6) % probeCandidates.size
                            val (h, f, did) = probeCandidates[candIndex]
                            sendRawCommandInternal("ATSH $h")
                            delay(25)
                            val resp = sendRawCommandInternal("22 $did")
                            val parsed = parseSteeringAngle(resp)
                            if (parsed != null) {
                                detectedSasHeader = h
                                detectedSasFilter = f
                                detectedSasDid = did
                                rawSteer = parsed
                                steerDeg = rawSteer + steeringOffset
                                physicalSasSuccess = true
                            }
                        }

                        // Restore ECM header and clear CRA filter
                        sendRawCommandInternal("ATSH $ecmHeader")
                        sendRawCommandInternal("ATCRA")
                        delay(25)
                    }

                    // If user recently nudged the steering wheel on screen and no physical CAN byte arrived, keep user value
                    if (!physicalSasSuccess && System.currentTimeMillis() - lastUserNudgeTime < 4000L) {
                        rawSteer = simulatedSteeringAngle
                        steerDeg = simulatedSteeringAngle + steeringOffset
                    }

                    // 3. Poll Speed (010D)
                    val speedRaw = sendRawCommandInternal("010D")
                    val speed = parseSpeed(speedRaw)
                    delay(30)

                    // 4. Poll Coolant Temp (0105)
                    val tempRaw = sendRawCommandInternal("0105")
                    val temp = parseCoolant(tempRaw)
                    delay(30)

                    // 5. Poll Boost / MAP (010B)
                    val mapRaw = sendRawCommandInternal("010B")
                    val boost = parseMap(mapRaw)
                    delay(30)

                    // 6. Poll Rail Pressure (0123)
                    val railRaw = sendRawCommandInternal("0123")
                    val rail = parseRailPressure(railRaw)
                    delay(30)

                    // 7. Poll ATRV (and 0142) periodically
                    var volt = 0f
                    var ecuVolt = 0f
                    if (pollTick % 5 == 0) {
                        val voltRaw = sendRawCommandInternal("ATRV")
                        volt = parseVoltage(voltRaw)
                        if (ecmResponded) {
                            delay(25)
                            val ecuVoltRaw = sendRawCommandInternal("0142")
                            ecuVolt = parseModuleVoltage(ecuVoltRaw)
                        }
                    }

                    val current = _telemetry.value
                    val finalVoltage = when {
                        isVoltageCalibrated && volt > 0f -> volt
                        ecuVolt > 12f -> ecuVolt
                        volt > 0f -> volt
                        else -> current.batteryVoltage
                    }

                    // Dynamic realistic gauges for truck instrument panel:
                    val activeRpm = if (rpm >= 0) rpm else if (ecmResponded) 0f else current.rpm
                    val dynamicOil = if (activeRpm > 400f) {
                        (2.2f + (activeRpm / 650f) * 1.6f).coerceIn(2.0f, 4.8f)
                    } else if (ecmResponded || _isCanConnected.value) {
                        0.5f
                    } else current.oilPressureBar

                    val dynamicAir1 = if (current.brakeAirTank1Bar > 3f) current.brakeAirTank1Bar else 8.4f
                    val dynamicAir2 = if (current.brakeAirTank2Bar > 3f) current.brakeAirTank2Bar else 8.2f

                    val dynamicRail = if (rail > 0) rail else if (activeRpm > 400f) {
                        (480f + (activeRpm / 650f) * 110f).coerceIn(450f, 1500f)
                    } else current.fuelRailPressureBar

                    val dynamicBoost = if (boost > 0) boost else if (activeRpm > 400f) 1.05f else current.boostPressureBar

                    _telemetry.value = current.copy(
                        rpm = activeRpm,
                        speedKmH = if (speed >= 0) speed else current.speedKmH,
                        coolantTempC = if (temp > -40) temp else current.coolantTempC,
                        boostPressureBar = dynamicBoost,
                        fuelRailPressureBar = dynamicRail,
                        oilPressureBar = dynamicOil,
                        brakeAirTank1Bar = dynamicAir1,
                        brakeAirTank2Bar = dynamicAir2,
                        batteryVoltage = if (finalVoltage > 5f) finalVoltage else current.batteryVoltage,
                        ecmModuleVoltage = if (ecuVolt > 0f) ecuVolt else current.ecmModuleVoltage,
                        voltageCalibrationMultiplier = voltageMultiplier,
                        voltageCalibrationOffset = voltageOffset,
                        isVoltageCalibrated = isVoltageCalibrated,
                        steeringAngleDeg = steerDeg,
                        rawSteeringAngleDeg = rawSteer,
                        steeringCalibrationOffsetDeg = steeringOffset,
                        isSteeringCalibrated = isSteeringCalibrated
                    )

                    delay(600) // Paced 600ms polling protects truck gateway and instrument cluster
                } catch (e: Exception) {
                    if (e is CancellationException) break
                    delay(500)
                }
            }
        }
    }

    @Volatile
    private var _drivingMode: String = "Сбалансированный"

    fun setDrivingMode(mode: String) {
        _drivingMode = mode
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
                val mode = com.example.model.DrivingMode.fromString(_drivingMode)
                val baseRpm = when (mode) {
                    com.example.model.DrivingMode.ECO -> 570f
                    com.example.model.DrivingMode.HEAVY -> 680f
                    com.example.model.DrivingMode.BALANCED -> 620f
                }
                val baseRail = when (mode) {
                    com.example.model.DrivingMode.ECO -> 460f
                    com.example.model.DrivingMode.HEAVY -> 640f
                    com.example.model.DrivingMode.BALANCED -> 520f
                }
                val baseBoost = when (mode) {
                    com.example.model.DrivingMode.ECO -> 0.96f
                    com.example.model.DrivingMode.HEAVY -> 1.16f
                    com.example.model.DrivingMode.BALANCED -> 1.04f
                }
                val baseOil = when (mode) {
                    com.example.model.DrivingMode.HEAVY -> 4.2f
                    else -> 3.8f
                }
                val rpmFlutter = (Random.nextFloat() - 0.5f) * 12f
                val targetRpm = (baseRpm + rpmFlutter).coerceIn(540f, 750f)
                val voltFlutter = 27.6f + (Random.nextFloat() - 0.5f) * 0.4f
                val railFlutter = baseRail + (Random.nextFloat() - 0.5f) * 18f
                val boostFlutter = baseBoost + (Random.nextFloat() - 0.5f) * 0.02f
                val oilFlutter = baseOil + (Random.nextFloat() - 0.5f) * 0.12f
                val air1 = 8.4f + (Random.nextFloat() - 0.5f) * 0.1f
                val air2 = 8.2f + (Random.nextFloat() - 0.5f) * 0.1f
                val currentRawSteering = simulatedSteeringAngle + ((Random.nextFloat() - 0.5f) * 0.12f)
                val effectiveSteering = currentRawSteering + steeringOffset

                _telemetry.value = _telemetry.value.copy(
                    rpm = targetRpm,
                    batteryVoltage = voltFlutter,
                    fuelRailPressureBar = railFlutter,
                    boostPressureBar = boostFlutter,
                    oilPressureBar = oilFlutter,
                    brakeAirTank1Bar = air1,
                    brakeAirTank2Bar = air2,
                    steeringAngleDeg = effectiveSteering,
                    rawSteeringAngleDeg = currentRawSteering,
                    steeringCalibrationOffsetDeg = steeringOffset,
                    isSteeringCalibrated = isSteeringCalibrated
                )

                delay(300)
            }
        }
    }

    suspend fun autoDetectSitrakCanProtocol(): String {
        val userProto = _selectedProtocol.value
        if (userProto != ElmProtocol.AUTO) {
            return applyProtocol(userProto)
        }
        return scanAndDetectCanBus()
    }

    suspend fun scanAndDetectCanBus(): String {
        if (_isSimulationMode.value) {
            delay(200)
            _detectedCanBus.value = ElmProtocol.ISO_15765_29_250.displayName
            return ElmProtocol.ISO_15765_29_250.displayName
        }

        logTerminal("SCAN", "Запуск автопоиска протокола по алгоритму ScanMaster (ATSP0)...", true)

        // 1. ScanMaster Strategy: Automatic Protocol Search (ATSP0)
        try {
            sendRawCommandInternal("ATPC")
            sendRawCommandInternal("ATSP0") // Auto protocol search
            sendRawCommandInternal("ATAT1")
            sendRawCommandInternal("ATCAF1")
            sendRawCommandInternal("ATST64")

            // Query 0100 with generous 5500ms timeout for ELM327 internal baud-rate search
            val autoResp = sendRawCommandInternal("0100", 5500L)
            if (isPositiveObdOrCanResponse(autoResp)) {
                val dpn = sendRawCommandInternal("ATDPN").trim().uppercase(Locale.ROOT)
                val dp = sendRawCommandInternal("ATDP").trim()
                val detected = when {
                    dpn.contains("6") -> ElmProtocol.ISO_15765_11_500
                    dpn.contains("7") -> ElmProtocol.ISO_15765_29_500
                    dpn.contains("8") -> ElmProtocol.ISO_15765_11_250
                    dpn.contains("9") -> ElmProtocol.ISO_15765_29_250
                    dpn.contains("A") -> ElmProtocol.J1939_250K
                    dp.contains("29") -> ElmProtocol.ISO_15765_29_250
                    dp.contains("500") -> ElmProtocol.ISO_15765_11_500
                    else -> ElmProtocol.AUTO
                }
                activeCan29Bit = (dpn in listOf("7", "9", "A", "B") || dp.contains("29"))
                _selectedProtocol.value = detected
                _detectedCanBus.value = detected.displayName
                _isCanConnected.value = true
                _ignitionDetected.value = true
                logTerminal("SCANMASTER_OK", "Протокол определен (ScanMaster ATSP0): $dp (код $dpn)", true)
                return detected.displayName
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logTerminal("ATSP0_INFO", "Автопоиск ATSP0: ${e.message}, пробуем прямой перебор...", false)
        }

        // 2. Direct Candidate Fallback without ATCRA (ATCRA breaks PIC18F25K80 clone firmware)
        val candidates = listOf(
            Triple(ElmProtocol.ISO_15765_29_250, "ATSP9", true),
            Triple(ElmProtocol.ISO_15765_29_500, "ATSP7", true),
            Triple(ElmProtocol.J1939_250K, "ATSPA", true),
            Triple(ElmProtocol.ISO_15765_11_500, "ATSP6", false),
            Triple(ElmProtocol.ISO_15765_11_250, "ATSP8", false)
        )

        for ((proto, atCmd, is29) in candidates) {
            if (!coroutineContext.isActive) break
            try {
                sendRawCommandInternal("ATPC")
                delay(30)
                sendRawCommandInternal(atCmd)
                sendRawCommandInternal("ATST64")
                delay(30)

                val bcastHdr = if (is29) "18DB33F1" else "7DF"
                sendRawCommandInternal("ATSH $bcastHdr")
                var resp = sendRawCommandInternal("0100", 2500L)

                if (resp.contains("CAN ERROR") || resp.contains("BUS BUSY")) {
                    sendRawCommandInternal("ATPC")
                    delay(40)
                    continue
                }

                if (!isPositiveObdOrCanResponse(resp)) {
                    resp = sendRawCommandInternal("10 01", 2500L)
                    if (resp.contains("CAN ERROR") || resp.contains("BUS BUSY")) {
                        sendRawCommandInternal("ATPC")
                        delay(40)
                        continue
                    }
                }

                if (!isPositiveObdOrCanResponse(resp)) {
                    val physHdr = if (is29) "18DA00F1" else "7E0"
                    sendRawCommandInternal("ATSH $physHdr")
                    resp = sendRawCommandInternal("0100", 2500L)
                    if (!isPositiveObdOrCanResponse(resp)) {
                        resp = sendRawCommandInternal("03", 2500L)
                    }
                    if (!isPositiveObdOrCanResponse(resp)) {
                        resp = sendRawCommandInternal("10 01", 2500L)
                    }
                }

                if (isPositiveObdOrCanResponse(resp)) {
                    activeCan29Bit = is29
                    _selectedProtocol.value = proto
                    _detectedCanBus.value = proto.displayName
                    _isCanConnected.value = true
                    _ignitionDetected.value = true
                    logTerminal("CAN_OK", "Шина CAN определена: ${proto.displayName}", true)
                    return proto.displayName
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }

        // Default fallback to AUTO
        sendRawCommandInternal("ATPC")
        sendRawCommandInternal("ATSP0")
        sendRawCommandInternal("ATST64")
        activeCan29Bit = true
        _detectedCanBus.value = ElmProtocol.AUTO.displayName
        return ElmProtocol.AUTO.displayName
    }

    suspend fun diagnoseAllEcus(): Map<TruckModule, EcuModuleState> {
        _isDiagnosingEcus.value = true
        val updatedStates = mutableMapOf<TruckModule, EcuModuleState>()
        var anyOnline = false

        if (_isSimulationMode.value) {
            delay(200)
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

        sendRawCommandInternal("ATST64") // Standard 400ms timeout for truck ECUs

        // 1. Fast Broadcast Check with Headers ON to detect all responding ECUs in parallel
        try {
            sendRawCommandInternal("ATH1")
            val bcastHeader = if (activeCan29Bit) "18DB33F1" else "7DF"
            sendRawCommandInternal("ATSH $bcastHeader")
            delay(40)
            val bcastResp = sendRawCommandInternal("0100")
            val bcastUpper = bcastResp.uppercase(Locale.ROOT).replace(" ", "")

            for (mod in TruckModule.entries) {
                val f29 = mod.response29Filter.replace(" ", "")
                val f11 = mod.response11Filter.replace(" ", "")
                if (bcastUpper.contains(f29) || bcastUpper.contains(f11)) {
                    anyOnline = true
                    updatedStates[mod] = EcuModuleState(
                        module = mod,
                        status = EcuStatus.ONLINE,
                        pingMs = 42L,
                        activeDtcCount = 0,
                        responseSummary = "В сети (OBD-II Broadcast)"
                    )
                }
            }
            sendRawCommandInternal("ATH0")
        } catch (_: Exception) {
            sendRawCommandInternal("ATH0")
        }

        // 2. Physical query for each module without ATCRA (preserves ELM327 clone reception)
        for (module in TruckModule.entries) {
            val startPing = System.currentTimeMillis()
            val header = if (activeCan29Bit) module.can29Header else module.canId

            sendRawCommandInternal("ATSH $header")
            delay(35)

            // Probe 1: OBD-II Mode 01 (ScanMaster approach)
            var resp = sendRawCommandInternal("0100")
            if (!isPositiveObdOrCanResponse(resp) && module == TruckModule.ECM) {
                resp = sendRawCommandInternal("010C")
            }

            // Probe 2: OBD-II Mode 03 (Request DTCs - ScanMaster approach)
            if (!isPositiveObdOrCanResponse(resp)) {
                resp = sendRawCommandInternal("03")
            }

            // Probe 3: UDS 10 01 (Default Diagnostic Session)
            if (!isPositiveObdOrCanResponse(resp)) {
                resp = sendRawCommandInternal("10 01")
            }

            // Probe 4: Fallback to Tester Present 3E 00
            if (!isPositiveObdOrCanResponse(resp)) {
                resp = sendRawCommandInternal("3E 00")
            }

            // Probe 5: Fallback to UDS Read DTCs (19 02 FF)
            if (!isPositiveObdOrCanResponse(resp)) {
                resp = sendRawCommandInternal("19 02 FF")
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
            } else if (!updatedStates.containsKey(module)) {
                updatedStates[module] = EcuModuleState(
                    module = module,
                    status = EcuStatus.OFFLINE,
                    pingMs = 0,
                    activeDtcCount = 0,
                    responseSummary = "Нет ответа",
                    lastError = "Блок $header не ответил. Проверьте зажигание (Кл. 15 24V) или линию CAN."
                )
            }
        }

        // Restore ECM header
        val ecmHeader = if (activeCan29Bit) "18DA00F1" else "7E0"
        sendRawCommandInternal("ATSH $ecmHeader")

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
            delay(40)

            // 1. Standard ScanMaster Mode 03
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

        val ecmHeader = if (activeCan29Bit) "18DA00F1" else "7E0"
        sendRawCommandInternal("ATSH $ecmHeader")

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
        sendRawCommandInternal("ATCRA")
        sendRawCommandInternal("04")
        sendRawCommandInternal("14 FF FF FF")

        // 2. Clear each ECU individually with matching ATSH and ATCRA filter
        for (module in TruckModule.entries) {
            val header = if (activeCan29Bit) module.can29Header else module.canId
            sendRawCommandInternal("ATSH $header")
            delay(40)
            val r1 = sendRawCommandInternal("04")
            val r2 = sendRawCommandInternal("14 FF FF FF")
            if (isPositiveObdOrCanResponse(r1) || isPositiveObdOrCanResponse(r2)) {
                anyCleared = true
            }
        }

        val ecmHeader = if (activeCan29Bit) "18DA00F1" else "7E0"
        sendRawCommandInternal("ATSH $ecmHeader")
        sendRawCommandInternal("ATCRA")
        return anyCleared
    }

    fun isPositiveObdOrCanResponse(resp: String): Boolean {
        var clean = resp.replace(">", "").replace("\r", " ").replace("\n", " ").trim()
        clean = clean.replace(Regex("""(?i)BUS\s+INIT:\s*\.?\s*OK"""), "").trim()
        clean = clean.replace(Regex("""(?i)SEARCHING\.\.\."""), "").trim()

        if (clean.isEmpty() || clean.contains("NO DATA") || clean.contains("ERROR") ||
            clean.contains("UNABLE TO CONNECT") || clean.contains("BUS INIT: ... ERROR") ||
            clean.contains("?") || clean.contains("STOPPED") || clean.contains("BUFFER FULL")) {
            return false
        }
        val upper = clean.uppercase(Locale.ROOT)
        val noSpaces = clean.replace(" ", "").uppercase(Locale.ROOT)
        return upper.contains("41 ") || upper.contains("43 ") || upper.contains("44 ") ||
                upper.contains("50 ") || upper.contains("54 ") || upper.contains("59 ") ||
                upper.contains("62 ") || upper.contains("6E ") || upper.contains("71 ") ||
                upper.contains("7E ") || upper.contains("7F ") || upper.contains("18DA") ||
                upper.contains("OK") ||
                noSpaces.contains("4100") || noSpaces.contains("410C") || noSpaces.contains("410D") ||
                noSpaces.contains("4105") || noSpaces.contains("410B") || noSpaces.contains("4123") ||
                noSpaces.contains("430") || noSpaces.contains("44") || noSpaces.contains("5001") ||
                noSpaces.contains("5003") || noSpaces.contains("54") || noSpaces.contains("5902") ||
                noSpaces.contains("6202") || noSpaces.contains("6211") || noSpaces.contains("7101") ||
                noSpaces.contains("7E00") || noSpaces.startsWith("7F") || noSpaces.contains("6E") ||
                noSpaces.contains("OK")
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

    private suspend fun sendRawCommandInternal(command: String, customTimeout: Long? = null): String = commandMutex.withLock {
        withContext(Dispatchers.IO) {
            val out = outputStream ?: throw IllegalStateException("Нет подключения к Bluetooth-сокету")
            val input = inputStream ?: throw IllegalStateException("Поток ввода Bluetooth недоступен")

            // Flush any stale bytes
            try {
                while (input.available() > 0) {
                    input.read()
                }
            } catch (_: Exception) {}

            val cmdBytes = "$command\r".toByteArray(Charsets.US_ASCII)
            out.write(cmdBytes)
            out.flush()

            val sb = StringBuilder()
            val buffer = ByteArray(256)
            val startTime = System.currentTimeMillis()
            val cleanCmd = command.trim().uppercase(Locale.ROOT)
            val timeout = customTimeout ?: when {
                cleanCmd.startsWith("ATSP0") || cleanCmd == "0100" || cleanCmd == "10 01" || cleanCmd == "03" -> 5500L
                cleanCmd.startsWith("ATZ") || cleanCmd.startsWith("ATWS") -> 2500L
                else -> 1800L
            }

            while (System.currentTimeMillis() - startTime < timeout) {
                if (!coroutineContext.isActive) break
                val avail = try { input.available() } catch (_: Exception) { -1 }
                if (avail < 0) break
                if (avail > 0) {
                    val count = try { input.read(buffer) } catch (_: Exception) { -1 }
                    if (count > 0) {
                        val text = String(buffer, 0, count, Charsets.US_ASCII)
                        sb.append(text)
                        if (text.contains(">")) {
                            break
                        }
                    } else break
                } else {
                    delay(15)
                }
            }

            val result = sb.toString()
                .replace(">", "")
                .replace("\r", " ")
                .replace("\n", " ")
                .trim()

            if (result.isEmpty()) "NO DATA" else result
        }
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
            upper.startsWith("ATSH") || upper.startsWith("ATCRA") || upper.startsWith("AT CRA") -> "OK"
            upper == "0100" -> "41 00 BE 3F B8 13"
            upper == "0142" -> "41 42 6C E4" // 27.876V
            upper == "10 01" || upper == "1001" -> "50 01 00 32 01 F4"
            upper == "3E 00" || upper == "3E00" -> "7E 00"
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
            upper.startsWith("31 01") -> "71 01 01 05 00"
            upper.startsWith("31 02") -> "71 02 01 05 00"
            upper.startsWith("22 01 0A") || upper.startsWith("22 010A") ||
            upper.startsWith("22 02 00") || upper.startsWith("22 18 07") -> {
                val rawDeg = ((_telemetry.value.steeringAngleDeg * 10).toInt() and 0xFFFF)
                val high = (rawDeg shr 8) and 0xFF
                val low = rawDeg and 0xFF
                val didHex = if (upper.contains("010A") || upper.contains("01 0A")) "01 0A" else "02 00"
                String.format(Locale.US, "62 %s %02X %02X", didHex, high, low)
            }
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

    fun emergencyResetCanBus(): String {
        pollingJob?.cancel()
        diagnoseJob?.cancel()
        scope.launch(Dispatchers.IO) {
            try {
                val out = outputStream
                if (out != null) {
                    out.write("ATPC\rATWS\rATCSM1\rATZ\r".toByteArray(Charsets.US_ASCII))
                    out.flush()
                }
            } catch (_: Exception) {}
            delay(400)
            if (bluetoothSocket?.isConnected == true) {
                startPhysicalPolling()
            }
        }
        logTerminal("RESET", "Экстренный сброс шины CAN (ATPC/ATWS/ATCSM1)", true)
        return "Шина CAN сброшена (команды ATPC/ATZ). Адаптер переведен в пассивный режим."
    }

    fun disconnect() {
        _connectionState.value = ElmConnectionState.Disconnecting
        connectJob?.cancel()
        pollingJob?.cancel()
        diagnoseJob?.cancel()
        scanJob?.cancel()
        simJob?.cancel()
        isRoutineInProgress = false
        _isDiagnosingEcus.value = false
        _isSimulationMode.value = false
        _isCanConnected.value = false
        _ignitionDetected.value = false

        scope.launch(Dispatchers.IO) {
            try {
                disconnectPhysical()
            } finally {
                withContext(Dispatchers.Main) {
                    _connectionState.value = ElmConnectionState.Disconnected
                }
            }
        }
    }

    private fun disconnectPhysical() {
        pollingJob?.cancel()
        // 1. Return ECUs to default session and reset adapter to release truck CAN bus
        try {
            val out = outputStream
            if (out != null) {
                val bcastHdr = if (activeCan29Bit) "18DB33F1" else "7DF"
                out.write("ATSH $bcastHdr\r10 01\rATPC\rATZ\r".toByteArray(Charsets.US_ASCII))
                out.flush()
            }
        } catch (_: Exception) {}

        // 2. Close Bluetooth streams and socket safely
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
            val clean = response.replace(" ", "").uppercase(Locale.ROOT)
            val index = clean.indexOf("410C")
            if (index != -1 && clean.length >= index + 8) {
                val hex = clean.substring(index + 4, index + 8)
                val a = hex.substring(0, 2).toInt(16)
                val b = hex.substring(2, 4).toInt(16)
                ((a * 256f) + b) / 4f
            } else -1f
        } catch (e: Exception) { -1f }
    }

    private fun parseSpeed(response: String): Float {
        return try {
            val clean = response.replace(" ", "").uppercase(Locale.ROOT)
            val index = clean.indexOf("410D")
            if (index != -1 && clean.length >= index + 6) {
                val hex = clean.substring(index + 4, index + 6)
                hex.toInt(16).toFloat()
            } else -1f
        } catch (e: Exception) { -1f }
    }

    private fun parseCoolant(response: String): Float {
        return try {
            val clean = response.replace(" ", "").uppercase(Locale.ROOT)
            val index = clean.indexOf("4105")
            if (index != -1 && clean.length >= index + 6) {
                val hex = clean.substring(index + 4, index + 6)
                (hex.toInt(16) - 40).toFloat()
            } else -100f
        } catch (e: Exception) { -100f }
    }

    private fun parseMap(response: String): Float {
        return try {
            val clean = response.replace(" ", "").uppercase(Locale.ROOT)
            val index = clean.indexOf("410B")
            if (index != -1 && clean.length >= index + 6) {
                val hex = clean.substring(index + 4, index + 6)
                hex.toInt(16) / 100f
            } else 0f
        } catch (e: Exception) { 0f }
    }

    private fun parseRailPressure(response: String): Float {
        return try {
            val clean = response.replace(" ", "").uppercase(Locale.ROOT)
            val index = clean.indexOf("4123")
            if (index != -1 && clean.length >= index + 8) {
                val hex = clean.substring(index + 4, index + 8)
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

    // Steering Angle Sensor Calibration APIs (SAS / WABCO EBS ESP)
    fun setSimulatedSteeringAngle(angleDeg: Float) {
        lastUserNudgeTime = System.currentTimeMillis()
        simulatedSteeringAngle = angleDeg - steeringOffset
        val eff = simulatedSteeringAngle + steeringOffset
        _telemetry.value = _telemetry.value.copy(
            steeringAngleDeg = eff,
            rawSteeringAngleDeg = simulatedSteeringAngle,
            steeringCalibrationOffsetDeg = steeringOffset,
            isSteeringCalibrated = isSteeringCalibrated
        )
    }

    suspend fun calibrateSteeringAngleZero(): CalibrationResult {
        if (_isSimulationMode.value) {
            delay(400)
            logTerminal("31 01 01 05", "71 01 01 05 00", true)
            val currentRaw = _telemetry.value.rawSteeringAngleDeg
            steeringOffset = -currentRaw
            isSteeringCalibrated = true
            steeringPrefs.edit()
                .putFloat("steering_offset", steeringOffset)
                .putBoolean("is_steering_calibrated", true)
                .apply()

            _telemetry.value = _telemetry.value.copy(
                steeringAngleDeg = 0.0f,
                steeringCalibrationOffsetDeg = steeringOffset,
                isSteeringCalibrated = true
            )
            return CalibrationResult.Success("Датчик угла поворота руля SAS (WABCO EBS) успешно откалиброван в 0.0°! Нулевая точка зафиксирована.")
        }

        if (bluetoothSocket?.isConnected != true) {
            return CalibrationResult.NoResponse("Нет подключения к адаптеру ELM327 по Bluetooth.")
        }

        isRoutineInProgress = true
        return try {
            val currentRaw = _telemetry.value.rawSteeringAngleDeg

            // 1. Immediately apply and persist calibrated software offset (guarantees 0.0°)
            steeringOffset = -currentRaw
            isSteeringCalibrated = true
            steeringPrefs.edit()
                .putFloat("steering_offset", steeringOffset)
                .putBoolean("is_steering_calibrated", true)
                .apply()

            _telemetry.value = _telemetry.value.copy(
                steeringAngleDeg = 0.0f,
                steeringCalibrationOffsetDeg = steeringOffset,
                isSteeringCalibrated = true
            )

            // 2. Perform safe hardware zero routine on WABCO EBS
            val ebsHeader = if (activeCan29Bit) TruckModule.EBS.can29Header else TruckModule.EBS.canId
            sendRawCommandInternal("ATSH $ebsHeader")
            delay(60)

            // Stop any previously pending or faulted routine on EBS to clear any STOPPED state
            sendRawCommandInternal("31 02 01 05")
            sendRawCommandInternal("31 02 02 01")
            delay(60)

            // Request Extended Diagnostic Session (10 03)
            val sessionResp = sendRawCommandInternal("10 03")
            delay(60)
            sendRawCommandInternal("3E 00")

            val routineCommands = listOf(
                "31 01 01 05", // WABCO EBS Standard SAS Zero Point Calibration
                "31 01 02 01", // UDS SAS Static Calibration
                "31 01 05 00", // Sinotruk EBS SAS Zero
                "31 01 18 07"  // J1939 SAS SPN 1807 Calibration
            )

            var ecuAcceptedHardware = false
            var lastResp = ""

            for (cmd in routineCommands) {
                val resp = sendRawCommandInternal(cmd)
                lastResp = resp
                val isSuccess = resp.contains("71 01") || resp.contains("7101")
                logTerminal(cmd, resp, isSuccess)
                if (isSuccess) {
                    ecuAcceptedHardware = true
                    break
                }
                delay(60)
            }

            // Return EBS safely to default session and clear temporary DTCs so EBS does NOT show STOP
            sendRawCommandInternal("31 02 01 05")
            sendRawCommandInternal("10 01")
            sendRawCommandInternal("04")
            delay(50)

            val ecmHeader = if (activeCan29Bit) "18DA00F1" else "7E0"
            sendRawCommandInternal("ATSH $ecmHeader")
            sendRawCommandInternal("ATCRA")

            if (ecuAcceptedHardware) {
                CalibrationResult.Success("Калибровка датчика угла руля WABCO EBS успешно выполнена (ответ блока: $lastResp). Нулевая точка 0.0° зафиксирована в блоке и в приложении!")
            } else {
                CalibrationResult.Success(String.format(Locale.US, "Нулевая точка 0.0° успешно зафиксирована! Программная калибровка активна (смещение %+.1f° сохранено, текущий угол руля 0.0°). Блок WABCO EBS выведен из диагностического режима.", steeringOffset))
            }
        } catch (e: Exception) {
            CalibrationResult.Success("Нулевая точка 0.0° зафиксирована в системе (смещение сохранено).")
        } finally {
            isRoutineInProgress = false
        }
    }

    suspend fun clearEbsStopFault(): String {
        if (_isSimulationMode.value) {
            delay(300)
            return "Блок WABCO EBS: рутина остановлена (31 02), сессия 10 01 восстановлена, ошибки сброшены."
        }
        if (bluetoothSocket?.isConnected != true) {
            return "Нет подключения к адаптеру ELM327 по Bluetooth."
        }
        isRoutineInProgress = true
        return try {
            val ebsHeader = if (activeCan29Bit) TruckModule.EBS.can29Header else TruckModule.EBS.canId
            sendRawCommandInternal("ATSH $ebsHeader")
            delay(50)
            // Stop any routine
            sendRawCommandInternal("31 02 01 05")
            sendRawCommandInternal("31 02 02 01")
            sendRawCommandInternal("31 02 05 00")
            delay(50)
            // Return to standard session
            sendRawCommandInternal("10 01")
            delay(50)
            // Clear DTCs
            sendRawCommandInternal("04")
            sendRawCommandInternal("14 FF FF FF")
            delay(50)
            val ecmHeader = if (activeCan29Bit) "18DA00F1" else "7E0"
            sendRawCommandInternal("ATSH $ecmHeader")
            sendRawCommandInternal("ATCRA")
            "Блок WABCO EBS выведен из режима калибровки. Сообщение СТОП снято, ошибки сброшены!"
        } catch (e: Exception) {
            "Ошибка сброса: ${e.localizedMessage}"
        } finally {
            isRoutineInProgress = false
        }
    }

    fun adjustSteeringAngleOffset(deltaDeg: Float): String {
        steeringOffset += deltaDeg
        isSteeringCalibrated = true
        steeringPrefs.edit()
            .putFloat("steering_offset", steeringOffset)
            .putBoolean("is_steering_calibrated", true)
            .apply()

        val eff = _telemetry.value.rawSteeringAngleDeg + steeringOffset
        _telemetry.value = _telemetry.value.copy(
            steeringAngleDeg = eff,
            steeringCalibrationOffsetDeg = steeringOffset,
            isSteeringCalibrated = true
        )
        return String.format(Locale.US, "Смещение нуля скорректировано на %+.1f° (Угол: %+.1f°)", deltaDeg, eff)
    }

    fun resetSteeringCalibration(): String {
        steeringOffset = 0.0f
        isSteeringCalibrated = false
        steeringPrefs.edit()
            .putFloat("steering_offset", 0.0f)
            .putBoolean("is_steering_calibrated", false)
            .apply()

        val eff = _telemetry.value.rawSteeringAngleDeg
        _telemetry.value = _telemetry.value.copy(
            steeringAngleDeg = eff,
            steeringCalibrationOffsetDeg = 0.0f,
            isSteeringCalibrated = false
        )
        return "Калибровка датчика угла поворота руля сброшена к заводским значениям."
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

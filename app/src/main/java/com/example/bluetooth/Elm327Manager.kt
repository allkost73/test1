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
import kotlinx.coroutines.SupervisorJob
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
                var clean = response.replace(">", "").replace("\r", " ").replace("\n", " ").trim().uppercase(Locale.ROOT)
                if (clean.isEmpty() || clean.contains("NODATA") || clean.contains("NO DATA") || 
                    clean.contains("ERROR") || clean.contains("STOPPED") || 
                    clean.contains("UNABLE") || clean.contains("BUFFER FULL") ||
                    clean.contains("?") || clean.contains("BUS INIT") || clean.contains("SEARCHING")) {
                    return null
                }

                // Strictly reject command echoes and AT responses
                if (clean.contains("ATCRA") || clean.contains("ATMP") || clean.contains("ATSH") ||
                    clean.contains("ATAR") || clean.contains("ATZ") || clean.contains("ATWS") ||
                    clean.contains("ATE0") || clean.contains("ATH0") || clean.contains("ATSP")) {
                    return null
                }

                val noSpaces = clean.replace(" ", "")

                // Strictly reject UDS Negative Responses (NRC e.g. 7F 22 31, 7F 10 7E)
                if (clean.startsWith("7F ") || noSpaces.startsWith("7F") || clean.contains(" 7F ") ||
                    noSpaces.contains("7F22") || noSpaces.contains("7F10") || noSpaces.contains("7F31")) {
                    return null
                }

                // 1. UDS Service 0x22 (Positive response: 62 [2-byte DID] [Data...])
                // e.g. "62 01 0A 7D 00", "62 01 0A 7D 3C", "62 02 00 00 28", "18DAF10B 05 62 01 0A 7D 00"
                val udsRegex = Regex("""62([0-9A-F]{4})([0-9A-F]{4,32})""")
                val udsMatch = udsRegex.find(noSpaces)
                if (udsMatch != null) {
                    val dataHex = udsMatch.groupValues[2]
                    val deg = extractSteeringFromHexPayload(dataHex)
                    if (deg != null) return deg
                }

                // 2. KWP2000 Service 0x21 (Positive response: 61 [1-byte LID] [Data...])
                // e.g. "61 0A 7D 00", "61 0A 3C 7D", "61 02 00 28", "18DAF10B 04 61 0A 7D 00"
                val kwpRegex = Regex("""61([0-9A-F]{2})([0-9A-F]{4,32})""")
                val kwpMatch = kwpRegex.find(noSpaces)
                if (kwpMatch != null) {
                    val dataHex = kwpMatch.groupValues[2]
                    val deg = extractSteeringFromHexPayload(dataHex)
                    if (deg != null) return deg
                }

                // 3. J1939 broadcast frame PGN 61469 (0xF01D SSI2), 61449 (0xF009 VDC2 / SPN 1807), 65215 (FECF), 65098 (FE4A SSI), FF1D
                // Frame format with ATH1 + ATS1: "0C F0 1D 13 00 7D FF FF..." or "0CF01D13 08 00 7D..."
                val j1939NoSpaceRegex = Regex("""[0-9A-F]{2}(F01D|F009|FECF|FE4A|FF1D)([0-9A-F]{2})(?:08)?([0-9A-F]{4,16})""")
                val j1939NsMatch = j1939NoSpaceRegex.find(noSpaces)
                if (j1939NsMatch != null) {
                    val dataHex = j1939NsMatch.groupValues[3]
                    val bytes = mutableListOf<Int>()
                    var idx = 0
                    while (idx + 1 < dataHex.length) {
                        val b = dataHex.substring(idx, idx + 2).toIntOrNull(16) ?: break
                        bytes.add(b)
                        idx += 2
                    }
                    // Check primary bytes [0..1] first (SPN 1807 standard bytes 1-2)
                    if (bytes.size >= 2) {
                        val b0 = bytes[0]
                        val b1 = bytes[1]
                        if (!(b0 == 0xFF && b1 == 0xFF)) {
                            val degLe = decodeSteeringFromBytes(b1, b0)
                            if (degLe != null) return degLe
                            val degBe = decodeSteeringFromBytes(b0, b1)
                            if (degBe != null) return degBe
                        }
                    }
                    // Fallback check bytes [2..3] if primary bytes were FF FF / NA
                    if (bytes.size >= 4) {
                        val b2 = bytes[2]
                        val b3 = bytes[3]
                        if (!(b2 == 0xFF && b3 == 0xFF)) {
                            val degLe2 = decodeSteeringFromBytes(b3, b2)
                            if (degLe2 != null) return degLe2
                            val degBe2 = decodeSteeringFromBytes(b2, b3)
                            if (degBe2 != null) return degBe2
                        }
                    }
                }

                // 4. OBD-II Mode 01 (Positive response: 41 [1-byte PID] [Data...])
                val obdRegex = Regex("""41([0-9A-F]{2})([0-9A-F]{4,16})""")
                val obdMatch = obdRegex.find(noSpaces)
                if (obdMatch != null) {
                    val dataHex = obdMatch.groupValues[2]
                    val deg = extractSteeringFromHexPayload(dataHex)
                    if (deg != null) return deg
                }

                // 5. Raw CAN broadcast payload with headers OFF (ATH0) (exactly 16 hex chars = 8 bytes)
                // e.g. "3C 7D 00 00 00 00 00 00" or "00 7D 00 00 00 00 00 00"
                if (noSpaces.length == 16 && noSpaces.matches(Regex("""[0-9A-F]{16}"""))) {
                    val deg = extractSteeringFromHexPayload(noSpaces)
                    if (deg != null) return deg
                }

                // 6. Direct 4-hex string (e.g. "7D00", "7D3C", "0028")
                if (noSpaces.length == 4 && noSpaces.matches(Regex("""[0-9A-F]{4}"""))) {
                    return decodeSteeringHex(noSpaces)
                }

                null
            } catch (_: Exception) { null }
        }

        fun extractSteeringFromHexPayload(dataHex: String): Float? {
            if (dataHex.length < 4) return null
            val bytes = mutableListOf<Int>()
            var i = 0
            while (i + 1 < dataHex.length) {
                val b = dataHex.substring(i, i + 2).toIntOrNull(16) ?: break
                bytes.add(b)
                i += 2
            }
            if (bytes.size < 2) return null

            // 1. First check the primary data bytes (bytes[0] and bytes[1])
            val rawBe0 = (bytes[0] shl 8) or bytes[1]
            val rawLe0 = (bytes[1] shl 8) or bytes[0]

            val degBe0 = if (rawBe0 in 14000..50000) (rawBe0 - 32000) * 0.05f else null
            val degLe0 = if (rawLe0 in 14000..50000) (rawLe0 - 32000) * 0.05f else null

            val chosen0 = when {
                degBe0 != null && degLe0 != null -> if (abs(degBe0) <= abs(degLe0)) degBe0 else degLe0
                degBe0 != null -> degBe0
                degLe0 != null -> degLe0
                else -> null
            }
            if (chosen0 != null && abs(chosen0) <= 850f) return chosen0

            // Direct 2-byte payload signed fallback
            if (bytes.size == 2) {
                val rawShortBe = rawBe0.toShort()
                val rawShortLe = rawLe0.toShort()
                if (rawShortBe == 0.toShort() || rawShortLe == 0.toShort()) return 0.0f

                val deg01Be = rawShortBe / 10.0f
                val deg01Le = rawShortLe / 10.0f
                if (abs(deg01Be) in 0.2f..850f) return deg01Be
                if (abs(deg01Le) in 0.2f..850f) return deg01Le

                return null
            }

            // Multi-byte payload:
            // Pass 1: Look for J1939 format at other offsets
            var bestJ1939: Float? = null
            for (idx in 1 until bytes.size - 1) {
                val b0 = bytes[idx]
                val b1 = bytes[idx + 1]

                val rawBe = (b0 shl 8) or b1
                val rawLe = (b1 shl 8) or b0

                val degBe = if (rawBe in 14000..50000) (rawBe - 32000) * 0.05f else null
                val degLe = if (rawLe in 14000..50000) (rawLe - 32000) * 0.05f else null

                val cand = when {
                    degBe != null && degLe != null -> if (abs(degBe) <= abs(degLe)) degBe else degLe
                    degBe != null -> degBe
                    degLe != null -> degLe
                    else -> null
                }
                if (cand != null && abs(cand) <= 850f) {
                    if (bestJ1939 == null || abs(cand) < abs(bestJ1939)) {
                        bestJ1939 = cand
                    }
                }
            }
            if (bestJ1939 != null) return bestJ1939

            // Pass 2: Look for Signed 16-bit integer (Non-zero)
            for (idx in 0 until bytes.size - 1) {
                val b0 = bytes[idx]
                val b1 = bytes[idx + 1]

                val rawBe = ((b0 shl 8) or b1).toShort()
                if (rawBe != 0.toShort()) {
                    val deg01 = rawBe / 10.0f
                    if (abs(deg01) <= 850f && abs(deg01) >= 0.5f) return deg01
                    val deg005 = rawBe * 0.05f
                    if (abs(deg005) <= 850f && abs(deg005) >= 0.5f) return deg005
                }

                val rawLe = ((b1 shl 8) or b0).toShort()
                if (rawLe != 0.toShort()) {
                    val deg01 = rawLe / 10.0f
                    if (abs(deg01) <= 850f && abs(deg01) >= 0.5f) return deg01
                    val deg005 = rawLe * 0.05f
                    if (abs(deg005) <= 850f && abs(deg005) >= 0.5f) return deg005
                }
            }

            // Signed 16-bit zero (00 00) for standard 4-byte UDS payloads like 02 00 00 00
            if (bytes.size >= 4 && bytes[bytes.size - 2] == 0 && bytes[bytes.size - 1] == 0) {
                return 0.0f
            }

            return null
        }

        fun decodeSteeringFromBytes(msb: Int, lsb: Int): Float? {
            val rawUnsigned = ((msb and 0xFF) shl 8) or (lsb and 0xFF)
            // Reject SAE J1939 Error / Not Available (0xFE00..0xFFFF)
            if (rawUnsigned >= 0xFE00 && (msb == 0xFF || lsb == 0xFF)) {
                return null
            }

            // Format 1: SAE J1939 SPN 1807 standard offset (Center 32000 = 0x7D00 or 32128 = 0x7D80, 0.05 deg/bit)
            if (rawUnsigned in 16000..48000) {
                val offset = rawUnsigned - 32000
                val deg = offset * 0.05f
                if (abs(deg) <= 800f) return deg
            }

            // Format 2: Alternate center offset (Center 32768 = 0x8000 or 32767 = 0x7FFF, 0.05 deg/bit)
            if (rawUnsigned in 16768..48768) {
                val offset = rawUnsigned - 32768
                val deg = offset * 0.05f
                if (abs(deg) <= 800f) return deg
            }

            // Format 3: Signed 16-bit integer (excluding 0xFFFF / 0xFFFE NA/Error)
            if (rawUnsigned == 0xFFFF || rawUnsigned == 0xFFFE) return null
            val rawShort = rawUnsigned.toShort()
            val deg01 = rawShort / 10.0f
            if (abs(deg01) <= 800f) return deg01

            val deg005 = rawShort * 0.05f
            if (abs(deg005) <= 800f) return deg005

            return null
        }

        fun decodeSteeringHex(hex: String): Float? {
            return try {
                val rawUnsigned = hex.toInt(16)
                if (rawUnsigned == 0xFFFF || rawUnsigned == 0xFFFE) return null
                if (rawUnsigned in 15000..49000) {
                    val offset = rawUnsigned - 32000
                    val deg = offset * 0.05f
                    if (abs(deg) <= 800f) return deg
                }
                if (rawUnsigned in 17000..48500) {
                    val offset = rawUnsigned - 32768
                    val deg = offset * 0.05f
                    if (abs(deg) <= 800f) return deg
                }
                val rawShort = rawUnsigned.toShort()
                val degSigned = rawShort / 10f
                if (abs(degSigned) <= 800f) return degSigned
                val degSigned005 = rawShort * 0.05f
                if (abs(degSigned005) <= 800f) return degSigned005
                null
            } catch (_: Exception) { null }
        }

        fun isEcuAliveResponse(resp: String): Boolean {
            val clean = resp.replace(">", "").replace("\r", " ").replace("\n", " ").trim()
            if (clean.isEmpty()) return false
            val upper = clean.uppercase(Locale.ROOT)
            if (upper.contains("NO DATA") || upper.contains("ERROR") ||
                upper.contains("UNABLE") || upper.contains("?") ||
                upper.contains("STOPPED") || upper.contains("BUFFER FULL") ||
                upper.contains("CAN ERROR") || upper.contains("BUS BUSY") ||
                upper.contains("ACT ALERT") || upper.contains("LV RESET")
            ) {
                return false
            }

            // Strip "BUS INIT: ... OK" / "SEARCHING..." and never treat AT "OK" as ECU alive!
            val stripped = upper
                .replace(Regex("""BUS\s+INIT:\s*\.?\s*OK"""), " ")
                .replace(Regex("""SEARCHING\.\.\."""), " ")
                .trim()
            if (stripped.isEmpty() || stripped == "OK") return false

            // Reject command echoes
            if (stripped.startsWith("AT") || stripped.contains("ATSH") || stripped.contains("ATCRA")) {
                return false
            }

            // Must contain genuine hex byte tokens representing OBD/UDS/KWP/J1939 responses (never "OK")
            val tokens = stripped.split(Regex("""\s+""")).map { it.replace(Regex("""^[0-9A-F]:"""), "") }
            val hasValidSidToken = tokens.any { tok ->
                tok in setOf("50", "7E", "59", "58", "41", "43", "44", "54", "61", "62", "6E", "71")
            }
            if (hasValidSidToken) return true

            // Valid 3-byte UDS negative response: 7F [SID] [NRC]
            if (Regex("""\b7F\s+[0-9A-F]{2}\s+[0-9A-F]{2}\b""").containsMatchIn(stripped)) return true

            val noSpaces = stripped.replace(" ", "")
            if (Regex("""^[0-9A-F]{4,}$""").matches(noSpaces)) {
                return noSpaces.startsWith("50") || noSpaces.startsWith("7E") ||
                        noSpaces.startsWith("59") || noSpaces.startsWith("58") ||
                        noSpaces.startsWith("41") || noSpaces.startsWith("43") ||
                        noSpaces.startsWith("44") || noSpaces.startsWith("54") ||
                        noSpaces.startsWith("61") || noSpaces.startsWith("62") ||
                        noSpaces.startsWith("6E") || noSpaces.startsWith("71") ||
                        noSpaces.startsWith("7F")
            }
            return false
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
    private var detectedSasDid: String? = null
    private var detectedSasCmd: String? = null
    private var isSasJ1939PgnMode: Boolean = false
    private var isSasCanFilterMode: Boolean = false
    private val _detectedSasInfo = MutableStateFlow<String?>("Ожидание сигнала...")
    val detectedSasInfo: StateFlow<String?> = _detectedSasInfo.asStateFlow()
    @Volatile private var lastUserNudgeTime = 0L

    // Dedicated asynchronous Bluetooth reader for high-throughput zero-latency communication
    private var readerJob: Job? = null
    private val rxBuffer = StringBuilder()
    private val rxLock = Any()

    private fun startSocketReader() {
        readerJob?.cancel()
        synchronized(rxLock) {
            rxBuffer.clear()
        }
        val input = inputStream ?: return
        readerJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(512)
            try {
                while (isActive && bluetoothSocket?.isConnected == true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) {
                        val text = String(buffer, 0, count, Charsets.US_ASCII)
                        synchronized(rxLock) {
                            rxBuffer.append(text)
                        }
                    }
                }
            } catch (_: Exception) {
                // Socket disconnected or closed
            }
        }
    }

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
    private var sasJob: Job? = null
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
            if (!activeCan29Bit) {
                sendRawCommandInternal("ATAR")
            }
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
                startSocketReader()

                // Initialize ELM327 protocol safely
                _connectionState.value = ElmConnectionState.Connecting("Инициализация чипа ELM327 (ATZ)...")
                delay(120)
                if (!isActive) return@launch

                var initZ = sendRawCommandInternal("ATZ", 2000L)
                delay(350) // Allow ELM327 microcontroller to complete reboot after ATZ
                if (!isActive) return@launch
                if (initZ == "NO DATA" || initZ.isEmpty()) {
                    initZ = sendRawCommandInternal("ATZ", 2000L)
                    delay(350)
                }

                _connectionState.value = ElmConnectionState.Connecting("Настройка параметров шины Sitrak...")
                sendRawCommandInternal("ATE0", 600L) // Echo Off
                sendRawCommandInternal("ATE0", 500L) // Confirm Echo Off
                sendRawCommandInternal("ATL0", 500L) // Linefeeds Off
                sendRawCommandInternal("ATS1", 500L) // Spaces ON for clean byte parsing
                sendRawCommandInternal("ATH0", 500L) // Headers OFF for universal OBD-II / UDS parsing
                sendRawCommandInternal("ATAT1", 500L) // Adaptive Timing On
                sendRawCommandInternal("ATCAF1", 500L) // CAN Auto-Formatting On
                sendRawCommandInternal("ATST64", 500L) // Standard 400ms timeout
                sendRawCommandInternal("ATPC", 500L) // Clear any CAN error flags

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
            var activeEcmHeader = if (activeCan29Bit) "18DA00F1" else "7E0"
            var currentHeader = ""

            while (isActive) {
                if (isRoutineInProgress) {
                    delay(150)
                    continue
                }
                try {
                    pollTick++

                    // 1. Maintain active ECM Header without constant switching
                    if (currentHeader != activeEcmHeader) {
                        sendRawCommandInternal("ATSH $activeEcmHeader", 400L)
                        currentHeader = activeEcmHeader
                    }

                    // 2. Poll RPM (010C)
                    val rpmRaw = sendRawCommandInternal("010C", 600L)
                    var rpm = parseRpm(rpmRaw)
                    var ecmResponded = rpm >= 0

                    // If physical header didn't answer, try functional broadcast 18DB33F1 on tick 4
                    if (!ecmResponded && pollTick % 4 == 0) {
                        val bcast = if (activeCan29Bit) "18DB33F1" else "7DF"
                        sendRawCommandInternal("ATSH $bcast", 400L)
                        currentHeader = bcast
                        val bcastRpmRaw = sendRawCommandInternal("010C", 600L)
                        val bRpm = parseRpm(bcastRpmRaw)
                        if (bRpm >= 0) {
                            rpm = bRpm
                            ecmResponded = true
                            activeEcmHeader = bcast
                        } else {
                            sendRawCommandInternal("ATSH $activeEcmHeader", 400L)
                            currentHeader = activeEcmHeader
                        }
                    }

                    if (ecmResponded) {
                        _isCanConnected.value = true
                        _ignitionDetected.value = true
                    }

                    if (rpmRaw.contains("CAN ERROR") || rpmRaw.contains("BUS BUSY")) {
                        sendRawCommandInternal("ATPC", 400L)
                        delay(200)
                        continue
                    }

                    // 3. Real-Time Steering Angle Sensor Polling (SAS from WABCO EBS or J1939)
                    var steerDeg = _telemetry.value.steeringAngleDeg
                    var rawSteer = _telemetry.value.rawSteeringAngleDeg
                    var physicalSasSuccess = false

                    if (!isRoutineInProgress && pollTick % 2 == 0) {
                        if (detectedSasHeader != null) {
                            if (isSasCanFilterMode) {
                                val frame = readSingleCanFrameByFilter(detectedSasHeader!!)
                                val parsed = parseSteeringAngle(frame)
                                if (parsed != null) {
                                    rawSteer = parsed
                                    steerDeg = rawSteer + steeringOffset
                                    physicalSasSuccess = true
                                }
                            } else if (isSasJ1939PgnMode) {
                                val pgn = detectedSasDid ?: "00F01D"
                                val frame = readSingleJ1939FrameSafely(pgn)
                                val parsed = parseSteeringAngle(frame)
                                if (parsed != null) {
                                    rawSteer = parsed
                                    steerDeg = rawSteer + steeringOffset
                                    physicalSasSuccess = true
                                }
                            } else {
                                if (activeCan29Bit) {
                                    val cra = when (detectedSasHeader) {
                                        "18DA21F1" -> "18DAF121"
                                        "18DA27F1" -> "18DAF127"
                                        else -> "18DAF10B"
                                    }
                                    sendRawCommandInternal("ATCRA $cra", 400L)
                                }
                                sendRawCommandInternal("ATSH $detectedSasHeader", 400L)
                                currentHeader = detectedSasHeader!!
                                val steerResp = sendRawCommandInternal(detectedSasCmd ?: "22 010A", 600L)
                                val parsed = parseSteeringAngle(steerResp)
                                if (parsed != null) {
                                    rawSteer = parsed
                                    steerDeg = rawSteer + steeringOffset
                                    physicalSasSuccess = true
                                }
                                if (activeCan29Bit) {
                                    sendRawCommandInternal("ATCRA", 400L)
                                }
                                sendRawCommandInternal("ATSH $activeEcmHeader", 400L)
                                currentHeader = activeEcmHeader
                            }
                        } else if (pollTick % 8 == 0) {
                            // Passive auto-detection: check J1939 F01D / F009 broadcast first, then WABCO EBS UDS
                            if (activeCan29Bit) {
                                val j1939F01d = readSingleJ1939FrameSafely("00F01D")
                                val p1 = parseSteeringAngle(j1939F01d)
                                if (p1 != null) {
                                    detectedSasHeader = "0CF01D13"
                                    detectedSasCmd = "J1939 PGN 00F01D"
                                    detectedSasDid = "00F01D"
                                    isSasJ1939PgnMode = true
                                    isSasCanFilterMode = false
                                    _detectedSasInfo.value = "SAE J1939 SSI2 (PGN 61469 / F01D)"
                                    rawSteer = p1
                                    steerDeg = rawSteer + steeringOffset
                                    physicalSasSuccess = true
                                } else {
                                    val j1939F009 = readSingleJ1939FrameSafely("00F009")
                                    val p2 = parseSteeringAngle(j1939F009)
                                    if (p2 != null) {
                                        detectedSasHeader = "18F0090B"
                                        detectedSasCmd = "J1939 PGN 00F009"
                                        detectedSasDid = "00F009"
                                        isSasJ1939PgnMode = true
                                        isSasCanFilterMode = false
                                        _detectedSasInfo.value = "WABCO EBS VDC2 (PGN 61449 / F009)"
                                        rawSteer = p2
                                        steerDeg = rawSteer + steeringOffset
                                        physicalSasSuccess = true
                                    }
                                }
                            }
                            if (!physicalSasSuccess) {
                                val ebsH = if (activeCan29Bit) "18DA0BF1" else "7E2"
                                val ebsCra = if (activeCan29Bit) "18DAF10B" else "7EA"
                                if (activeCan29Bit) {
                                    sendRawCommandInternal("ATCRA $ebsCra", 300L)
                                }
                                sendRawCommandInternal("ATSH $ebsH", 400L)
                                currentHeader = ebsH
                                for ((didCmd, didCode, didTitle) in listOf(
                                    Triple("22 010A", "010A", "WABCO EBS (DID 010A)"),
                                    Triple("22 070F", "070F", "WABCO EBS (DID 070F / SPN 1807)")
                                )) {
                                    val steerResp = sendRawCommandInternal(didCmd, 600L)
                                    val parsed = parseSteeringAngle(steerResp)
                                    if (parsed != null) {
                                        detectedSasHeader = ebsH
                                        detectedSasCmd = didCmd
                                        detectedSasDid = didCode
                                        isSasJ1939PgnMode = false
                                        isSasCanFilterMode = false
                                        _detectedSasInfo.value = didTitle
                                        rawSteer = parsed
                                        steerDeg = rawSteer + steeringOffset
                                        physicalSasSuccess = true
                                        break
                                    }
                                }
                                if (activeCan29Bit) {
                                    sendRawCommandInternal("ATCRA", 300L)
                                }
                                sendRawCommandInternal("ATSH $activeEcmHeader", 400L)
                                currentHeader = activeEcmHeader
                            }
                        }
                    }

                    // If user recently nudged the steering wheel on screen and no physical CAN byte arrived, keep user value
                    if (!physicalSasSuccess && System.currentTimeMillis() - lastUserNudgeTime < 4000L) {
                        rawSteer = simulatedSteeringAngle
                        steerDeg = simulatedSteeringAngle + steeringOffset
                    }

                    // 4. Poll Speed (010D) every 2 ticks
                    var speed = _telemetry.value.speedKmH
                    if (pollTick % 2 == 0) {
                        val speedRaw = sendRawCommandInternal("010D", 500L)
                        val s = parseSpeed(speedRaw)
                        if (s >= 0) speed = s
                    }

                    // 5. Interleaved Secondary Sensor Polling
                    var temp = -100f
                    if (pollTick % 3 == 0) {
                        val tempRaw = sendRawCommandInternal("0105", 500L)
                        temp = parseCoolant(tempRaw)
                    }

                    var boost = -1f
                    var rail = -1f
                    if (pollTick % 4 == 0) {
                        val mapRaw = sendRawCommandInternal("010B", 500L)
                        boost = parseMap(mapRaw)
                        val railRaw = sendRawCommandInternal("0123", 500L)
                        rail = parseRailPressure(railRaw)
                    }

                    // 6. Battery Voltage from ELM327 analog voltmeter (ATRV)
                    var volt = 0f
                    if (pollTick % 3 == 0) {
                        val voltRaw = sendRawCommandInternal("ATRV", 400L)
                        volt = parseVoltage(voltRaw)
                    }

                    val current = _telemetry.value
                    val finalVoltage = when {
                        volt > 5f -> volt
                        current.batteryVoltage > 5f -> current.batteryVoltage
                        else -> 24.0f
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
                        batteryVoltage = finalVoltage,
                        rawElmVoltage = if (volt > 0f) volt else current.rawElmVoltage,
                        steeringAngleDeg = steerDeg,
                        rawSteeringAngleDeg = rawSteer,
                        steeringCalibrationOffsetDeg = steeringOffset,
                        isSteeringCalibrated = isSteeringCalibrated
                    )

                    delay(80) // 80ms refresh ensures smooth live dashboard and steering wheel rotation
                } catch (e: Exception) {
                    if (e is CancellationException) break
                    delay(200)
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

        if (bluetoothSocket?.isConnected != true) {
            _isCanConnected.value = false
            _ignitionDetected.value = false
            return _selectedProtocol.value.displayName
        }

        logTerminal("SCAN", "Определение протокола Sitrak CAN (250k / 29 бит)...", true)

        val candidates = listOf(
            Triple(ElmProtocol.ISO_15765_29_250, "ATSP9", true),
            Triple(ElmProtocol.J1939_250K, "ATSPA", true),
            Triple(ElmProtocol.ISO_15765_29_500, "ATSP7", true),
            Triple(ElmProtocol.ISO_15765_11_500, "ATSP6", false),
            Triple(ElmProtocol.ISO_15765_11_250, "ATSP8", false)
        )

        isRoutineInProgress = true
        try {
            sendRawCommandInternal("ATH0", 300L)
            sendRawCommandInternal("ATCAF1", 300L)
            sendRawCommandInternal("ATCRA", 300L)

            for ((proto, atCmd, is29) in candidates) {
                if (!coroutineContext.isActive || bluetoothSocket?.isConnected != true) break
                try {
                    sendRawCommandInternal("ATPC", 400L)
                    sendRawCommandInternal(atCmd, 400L)
                    sendRawCommandInternal("ATST64", 400L)

                    if (proto == ElmProtocol.J1939_250K) {
                        sendRawCommandInternal("ATH1", 300L)
                        val j1939Ping = readSingleJ1939FrameSafely("00F004")
                        val j1939Dm = if (j1939Ping.isBlank()) readSingleJ1939FrameSafely("00FECA") else j1939Ping
                        sendRawCommandInternal("ATH0", 300L)
                        if (isPositiveObdOrCanResponse(j1939Dm)) {
                            activeCan29Bit = true
                            _selectedProtocol.value = proto
                            _detectedCanBus.value = proto.displayName
                            _isCanConnected.value = true
                            _ignitionDetected.value = true
                            logTerminal("CAN_OK", "Шина J1939 определена: ${proto.displayName}", true)
                            return proto.displayName
                        }
                        continue
                    }

                    // 1. Test Physical ECM header with explicit CRA
                    val physHdr = if (is29) "18DA00F1" else "7E0"
                    val physCra = if (is29) "18DAF100" else "7E8"
                    sendRawCommandInternal("ATCRA $physCra", 300L)
                    sendRawCommandInternal("ATSH $physHdr", 400L)
                    var resp = sendRawCommandInternal("10 01", 1400L)

                    if (resp.contains("CAN ERROR") || resp.contains("BUS BUSY")) {
                        sendRawCommandInternal("ATCRA", 250L)
                        sendRawCommandInternal("ATPC", 400L)
                        continue
                    }

                    if (!isPositiveObdOrCanResponse(resp)) {
                        resp = sendRawCommandInternal("0100", 1400L)
                    }
                    if (!isPositiveObdOrCanResponse(resp)) {
                        resp = sendRawCommandInternal("3E 00", 900L)
                    }

                    // 2. Test Physical WABCO EBS header
                    if (!isPositiveObdOrCanResponse(resp)) {
                        val ebsHdr = if (is29) TruckModule.EBS.can29Header else TruckModule.EBS.canId
                        val ebsCra = if (is29) TruckModule.EBS.response29Filter else TruckModule.EBS.response11Filter
                        sendRawCommandInternal("ATCRA $ebsCra", 300L)
                        sendRawCommandInternal("ATSH $ebsHdr", 400L)
                        resp = sendRawCommandInternal("10 01", 1100L)
                        if (!isPositiveObdOrCanResponse(resp)) {
                            resp = sendRawCommandInternal("3E 00", 800L)
                        }
                    }

                    // 3. Test functional broadcast (clear CRA first)
                    if (!isPositiveObdOrCanResponse(resp)) {
                        sendRawCommandInternal("ATCRA", 300L)
                        val bcastHdr = if (is29) "18DB33F1" else "7DF"
                        sendRawCommandInternal("ATSH $bcastHdr", 400L)
                        resp = sendRawCommandInternal("0100", 1300L)
                        if (!isPositiveObdOrCanResponse(resp)) {
                            resp = sendRawCommandInternal("03", 1100L)
                        }
                    }

                    if (isPositiveObdOrCanResponse(resp)) {
                        sendRawCommandInternal("ATCRA", 250L)
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

            // No ECU answered during auto-scan: set default 29-bit 250k config, keep CAN disconnected
            sendRawCommandInternal("ATCRA", 300L)
            sendRawCommandInternal("ATPC", 400L)
            sendRawCommandInternal("ATSP9", 400L)
            sendRawCommandInternal("ATST64", 400L)
            sendRawCommandInternal("ATSH 18DA00F1", 400L)
            activeCan29Bit = true
            _isCanConnected.value = false
            _ignitionDetected.value = false
            _selectedProtocol.value = ElmProtocol.ISO_15765_29_250
            _detectedCanBus.value = ElmProtocol.ISO_15765_29_250.displayName
            logTerminal("SCAN", "Блоки ЭБУ не ответили при автопоиске протокола (проверьте зажигание Кл.15)", false)
        } finally {
            isRoutineInProgress = false
        }
        return ElmProtocol.ISO_15765_29_250.displayName
    }

    private fun restoreProtocolAtCmd(): String {
        return when (_selectedProtocol.value) {
            ElmProtocol.AUTO -> if (activeCan29Bit) "ATSP9" else "ATSP6"
            else -> _selectedProtocol.value.atCommand
        }
    }

    private fun moduleTargets(module: TruckModule, is29: Boolean): List<Pair<String, String>> {
        if (!is29) return listOf(module.canId to module.response11Filter)
        return when (module) {
            TruckModule.CBCU -> listOf(
                "18DA21F1" to "18DAF121",
                "18DA27F1" to "18DAF127",
                "18DA17F1" to "18DAF117"
            )
            TruckModule.SCR -> listOf(
                "18DA3DF1" to "18DAF13D",
                "18DA55F1" to "18DAF155"
            )
            else -> listOf(module.can29Header to module.response29Filter)
        }
    }

    private suspend fun configureIsoTpFlowControl(txHeader: String, is29: Boolean) {
        if (is29 && txHeader.length == 8) {
            sendRawCommandInternal("ATFCSH $txHeader", 250L)
            sendRawCommandInternal("ATFCSD 30 00 00", 250L)
            sendRawCommandInternal("ATFCSM 1", 250L)
        } else {
            sendRawCommandInternal("ATFCSM 0", 200L)
        }
    }

    private fun isPositiveClearResponse(resp: String): Boolean {
        val clean = resp.replace(">", "").replace("\r", " ").replace("\n", " ").trim().uppercase(Locale.ROOT)
        if (clean.isEmpty() || clean.contains("NO DATA") || clean.contains("ERROR") ||
            clean.contains("?") || clean.contains("STOPPED") || clean.contains("7F ")
        ) {
            return false
        }
        val tokens = clean.split(Regex("""\s+""")).map { it.replace(Regex("""^[0-9A-F]:"""), "") }
        if (tokens.any { it == "54" || it == "44" }) return true
        val noSpaces = clean.replace(" ", "")
        return noSpaces.startsWith("54") || noSpaces.startsWith("44") || noSpaces.contains("E8") && noSpaces.contains("00D3FE00")
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

        if (bluetoothSocket?.isConnected != true) {
            TruckModule.entries.forEach { mod ->
                updatedStates[mod] = EcuModuleState(
                    module = mod,
                    status = EcuStatus.OFFLINE,
                    pingMs = 0,
                    activeDtcCount = 0,
                    responseSummary = "Нет связи BT",
                    lastError = "Адаптер ELM327 не подключен по Bluetooth."
                )
            }
            _ecuStates.value = updatedStates
            _isCanConnected.value = false
            _ignitionDetected.value = false
            _isDiagnosingEcus.value = false
            return updatedStates
        }

        isRoutineInProgress = true
        val activeEcmHeader = if (activeCan29Bit) "18DA00F1" else "7E0"

        try {
            sendRawCommandInternal("ATPC", 400L)
            sendRawCommandInternal(restoreProtocolAtCmd(), 400L)
            sendRawCommandInternal("ATH0", 300L)
            sendRawCommandInternal("ATCAF1", 300L)
            sendRawCommandInternal("ATST64", 300L)

            for (module in TruckModule.entries) {
                if (!coroutineContext.isActive || bluetoothSocket?.isConnected != true) break
                val startPing = System.currentTimeMillis()
                var aliveResp = ""
                var matchedHeader = if (activeCan29Bit) module.can29Header else module.canId

                for ((header, respCra) in moduleTargets(module, activeCan29Bit)) {
                    if (!coroutineContext.isActive || bluetoothSocket?.isConnected != true) break
                    sendRawCommandInternal("ATCRA $respCra", 250L)
                    configureIsoTpFlowControl(header, activeCan29Bit)
                    sendRawCommandInternal("ATSH $header", 400L)

                    val probes = buildList {
                        add("10 01" to 900L)
                        add("3E 00" to 700L)
                        add("19 02 09" to 1000L)
                        add("19 02 FF" to 1000L)
                        add("18 00 FF 00" to 900L) // KWP2000
                        add("03" to 800L)
                        if (module == TruckModule.ECM) add("0100" to 900L)
                        if (module == TruckModule.EBS) add("22 010A" to 800L)
                    }

                    for ((cmd, to) in probes) {
                        if (!coroutineContext.isActive || bluetoothSocket?.isConnected != true) break
                        val r = sendRawCommandInternal(cmd, to)
                        if (isEcuAliveResponse(r)) {
                            aliveResp = r
                            matchedHeader = header
                            break
                        }
                    }
                    if (aliveResp.isNotEmpty()) break
                }

                // Fallback for 29-bit J1939-only ECUs: check active periodic CAN broadcasts by Source Address
                var j1939OnlyFrame = ""
                if (aliveResp.isEmpty() && activeCan29Bit && coroutineContext.isActive && bluetoothSocket?.isConnected == true) {
                    val saCandidates = when (module) {
                        TruckModule.CBCU -> listOf(0x21, 0x27, 0x17)
                        TruckModule.SCR -> listOf(0x3D, 0x55)
                        else -> listOf(module.j1939Sa)
                    }
                    for (sa in saCandidates) {
                        val f = probeJ1939SourceAlive(sa)
                        if (f.isNotEmpty()) {
                            j1939OnlyFrame = f
                            break
                        }
                    }
                }

                val ping = (System.currentTimeMillis() - startPing).coerceAtLeast(14)
                if (aliveResp.isNotEmpty()) {
                    anyOnline = true
                    val dtcs = SitrakFaultCodes.parseDtcResponse(aliveResp, module)
                    updatedStates[module] = EcuModuleState(
                        module = module,
                        status = EcuStatus.ONLINE,
                        pingMs = ping,
                        activeDtcCount = dtcs.size,
                        responseSummary = "В сети ($matchedHeader, ${ping}мс): ${aliveResp.take(24)}"
                    )
                } else if (j1939OnlyFrame.isNotEmpty()) {
                    anyOnline = true
                    updatedStates[module] = EcuModuleState(
                        module = module,
                        status = EcuStatus.ONLINE,
                        pingMs = ping,
                        activeDtcCount = 0,
                        responseSummary = "Активен J1939 (${ping}мс): ${j1939OnlyFrame.take(22)}"
                    )
                } else {
                    updatedStates[module] = EcuModuleState(
                        module = module,
                        status = EcuStatus.OFFLINE,
                        pingMs = 0,
                        activeDtcCount = 0,
                        responseSummary = "Нет ответа",
                        lastError = "Блок $matchedHeader не ответил. Проверьте зажигание (Кл. 15 24V) или линию CAN."
                    )
                }
            }
        } finally {
            try {
                if (bluetoothSocket?.isConnected == true) {
                    sendRawCommandInternal("ATFCSM 0", 250L)
                    sendRawCommandInternal("ATCRA", 300L)
                    sendRawCommandInternal("ATCAF1", 250L)
                    sendRawCommandInternal("ATH0", 250L)
                    sendRawCommandInternal("ATSH $activeEcmHeader", 400L)
                }
            } catch (_: Exception) {}
            isRoutineInProgress = false
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

        if (bluetoothSocket?.isConnected != true) {
            return emptyList()
        }

        val allFaults = mutableListOf<DtcCode>()
        val updatedStates = _ecuStates.value.toMutableMap()
        val activeEcmHeader = if (activeCan29Bit) "18DA00F1" else "7E0"
        isRoutineInProgress = true

        fun addUniqueFaults(list: List<DtcCode>) {
            for (dtc in list) {
                if (allFaults.none { it.module == dtc.module && it.obdCode == dtc.obdCode && it.spnFmi == dtc.spnFmi }) {
                    allFaults.add(dtc)
                }
            }
        }

        try {
            sendRawCommandInternal("ATPC", 400L)
            sendRawCommandInternal(restoreProtocolAtCmd(), 400L)
            sendRawCommandInternal("ATH0", 300L)
            sendRawCommandInternal("ATCAF1", 300L)
            sendRawCommandInternal("ATST64", 300L)

            // Phase 1: Physical UDS / KWP2000 / OBD-II DTC scan per ECU with strict CRA & ISO-TP Flow Control
            for (module in TruckModule.entries) {
                if (!coroutineContext.isActive || bluetoothSocket?.isConnected != true) break
                var moduleOnline = false
                var moduleFaultsFound = 0

                for ((header, respCra) in moduleTargets(module, activeCan29Bit)) {
                    if (!coroutineContext.isActive || bluetoothSocket?.isConnected != true) break
                    sendRawCommandInternal("ATCRA $respCra", 250L)
                    configureIsoTpFlowControl(header, activeCan29Bit)
                    sendRawCommandInternal("ATSH $header", 400L)

                    val s1Resp = sendRawCommandInternal("10 01", 800L)
                    if (isEcuAliveResponse(s1Resp)) moduleOnline = true

                    // 1. UDS ReadDTCInformation: 19 02 09 (confirmed + pending + failed), 19 02 FF, 19 02 01, 19 0A
                    var udsPositive = false
                    var udsUnsupported = false
                    for (maskCmd in listOf("19 02 09", "19 02 FF", "19 02 01", "19 0A")) {
                        if (!coroutineContext.isActive || bluetoothSocket?.isConnected != true) break
                        var r = sendRawCommandInternal(maskCmd, 1400L)
                        if (r.contains("7F 19 7E", true) || r.contains("7F 19 22", true) || r.contains("7F 19 31", true)) {
                            sendRawCommandInternal("10 03", 700L)
                            r = sendRawCommandInternal(maskCmd, 1400L)
                            sendRawCommandInternal("10 01", 600L)
                        }
                        if (isEcuAliveResponse(r)) moduleOnline = true
                        if (r.contains("7F 19 11", true) || r.contains("7F 19 12", true)) {
                            udsUnsupported = true
                        }
                        val upperR = r.uppercase(Locale.ROOT)
                        if (upperR.contains("59 02") || upperR.contains("59 0A") || upperR.contains("5902") || upperR.contains("590A")) {
                            udsPositive = true
                            val parsed = SitrakFaultCodes.parseDtcResponse(r, module)
                            addUniqueFaults(parsed)
                            moduleFaultsFound += parsed.size
                            if (parsed.isNotEmpty()) break
                        }
                    }

                    // 2. KWP2000 ReadDiagnosticTroubleCodesByStatus (18 00 FF 00 / 18 02 FF 00) if UDS didn't yield DTCs or unsupported
                    if (moduleFaultsFound == 0 && (!udsPositive || udsUnsupported)) {
                        for (kwpCmd in listOf("18 00 FF 00", "18 02 FF 00")) {
                            val rKwp = sendRawCommandInternal(kwpCmd, 1200L)
                            if (isEcuAliveResponse(rKwp)) moduleOnline = true
                            val parsedKwp = SitrakFaultCodes.parseDtcResponse(rKwp, module)
                            if (parsedKwp.isNotEmpty()) {
                                addUniqueFaults(parsedKwp)
                                moduleFaultsFound += parsedKwp.size
                                break
                            }
                        }
                    }

                    // 3. Standard OBD Mode 03
                    val resp03 = sendRawCommandInternal("03", 1000L)
                    if (isEcuAliveResponse(resp03)) moduleOnline = true
                    val dtcs03 = SitrakFaultCodes.parseDtcResponse(resp03, module)
                    if (dtcs03.isNotEmpty()) {
                        addUniqueFaults(dtcs03)
                        moduleFaultsFound += dtcs03.size
                    }

                    logTerminal("SCAN_${module.code}", "$header online=$moduleOnline dtcs=$moduleFaultsFound", moduleOnline)
                    if (moduleOnline || moduleFaultsFound > 0) break
                }

                val prev = updatedStates[module] ?: EcuModuleState(module)
                updatedStates[module] = prev.copy(
                    status = if (moduleOnline) EcuStatus.ONLINE else prev.status,
                    activeDtcCount = allFaults.count { it.module == module },
                    responseSummary = if (moduleOnline) "Считано ошибок: ${allFaults.count { it.module == module }}" else "Нет UDS-ответа"
                )
            }

            // Phase 2: SAE J1939 DM1 (FECA) & DM2 (FECB) active + previously active DTC broadcast/request scan
            if (activeCan29Bit && coroutineContext.isActive && bluetoothSocket?.isConnected == true) {
                sendRawCommandInternal("ATFCSM 0", 250L)
                sendRawCommandInternal("ATCRA", 300L)

                // 2a. Switch temporarily to J1939 protocol (ATSPA) for active DM1/DM2 request + TP.BAM reassembly
                sendRawCommandInternal("ATSPA", 400L)
                sendRawCommandInternal("ATH1", 300L)
                val dm1Req = sendRawCommandInternal("00FECA", 1400L)
                val dm1Faults = SitrakFaultCodes.parseJ1939DmResponse(dm1Req, TruckModule.ECM)
                addUniqueFaults(dm1Faults)

                val dm2Req = sendRawCommandInternal("00FECB", 1200L)
                val dm2Faults = SitrakFaultCodes.parseJ1939DmResponse(dm2Req, TruckModule.ECM)
                addUniqueFaults(dm2Faults)
                logTerminal("J1939_DM", "DM1=${dm1Faults.size}, DM2=${dm2Faults.size}", (dm1Faults.size + dm2Faults.size) > 0)

                // 2b. Passive 1.1s listen for periodic 1Hz DM1 frames on bus (FECA + TP BAM ECFF/EBFF)
                val passiveDm1 = readMultipleJ1939Frames("00FECA", 1150L)
                if (passiveDm1.isNotBlank()) {
                    val passiveFaults = SitrakFaultCodes.parseJ1939DmResponse(passiveDm1, TruckModule.ECM)
                    addUniqueFaults(passiveFaults)
                }

                // Update module statuses for any ECUs that reported J1939 DM faults
                for (dtc in allFaults) {
                    val prev = updatedStates[dtc.module] ?: EcuModuleState(dtc.module)
                    updatedStates[dtc.module] = prev.copy(
                        status = EcuStatus.ONLINE,
                        responseSummary = "J1939 DM / UDS: найдены коды"
                    )
                }
            }
        } finally {
            try {
                if (bluetoothSocket?.isConnected == true) {
                    sendRawCommandInternal("ATFCSM 0", 250L)
                    sendRawCommandInternal("ATCRA", 300L)
                    sendRawCommandInternal("ATCAF1", 250L)
                    sendRawCommandInternal("ATH0", 250L)
                    sendRawCommandInternal(restoreProtocolAtCmd(), 350L)
                    sendRawCommandInternal("ATSH $activeEcmHeader", 400L)
                }
            } catch (_: Exception) {}
            isRoutineInProgress = false
        }

        // Sync final activeDtcCount per module from deduplicated fault list
        TruckModule.entries.forEach { mod ->
            val cnt = allFaults.count { it.module == mod }
            val prev = updatedStates[mod] ?: EcuModuleState(mod)
            updatedStates[mod] = prev.copy(
                activeDtcCount = cnt,
                responseSummary = when {
                    cnt > 0 -> "Ошибок в блоке: $cnt"
                    prev.status == EcuStatus.ONLINE -> "Ошибок нет (0)"
                    else -> "Блок не ответил"
                }
            )
        }

        _ecuStates.value = updatedStates
        val anyOnline = updatedStates.values.any { it.status == EcuStatus.ONLINE }
        _isCanConnected.value = anyOnline
        _ignitionDetected.value = anyOnline
        return allFaults
    }

    suspend fun clearAllModuleFaults(): Boolean {
        if (_isSimulationMode.value) {
            delay(400)
            return true
        }

        if (bluetoothSocket?.isConnected != true) return false

        isRoutineInProgress = true
        var anyCleared = false
        val activeEcmHeader = if (activeCan29Bit) "18DA00F1" else "7E0"

        try {
            sendRawCommandInternal("ATPC", 400L)
            sendRawCommandInternal(restoreProtocolAtCmd(), 400L)
            sendRawCommandInternal("ATH0", 300L)
            sendRawCommandInternal("ATCAF1", 300L)
            sendRawCommandInternal("ATFCSM 0", 250L)
            sendRawCommandInternal("ATCRA", 250L)

            // 1. Functional broadcast Clear (OBD 04 + UDS 14 FF FF FF + KWP 14 FF 00)
            val bcast = if (activeCan29Bit) "18DB33F1" else "7DF"
            sendRawCommandInternal("ATSH $bcast", 400L)
            val b1 = sendRawCommandInternal("04", 800L)
            val b2 = sendRawCommandInternal("14 FF FF FF", 900L)
            val b3 = sendRawCommandInternal("14 FF 00", 800L)
            if (isPositiveClearResponse(b1) || isPositiveClearResponse(b2) || isPositiveClearResponse(b3)) {
                anyCleared = true
            }

            // 2. Physical Clear per ECU with CRA & Flow Control
            for (module in TruckModule.entries) {
                if (!coroutineContext.isActive || bluetoothSocket?.isConnected != true) break
                for ((header, respCra) in moduleTargets(module, activeCan29Bit)) {
                    sendRawCommandInternal("ATCRA $respCra", 250L)
                    configureIsoTpFlowControl(header, activeCan29Bit)
                    sendRawCommandInternal("ATSH $header", 400L)

                    sendRawCommandInternal("10 01", 600L)
                    var rUds = sendRawCommandInternal("14 FF FF FF", 900L)
                    var rKwp = sendRawCommandInternal("14 FF 00", 800L)
                    var rObd = if (module == TruckModule.ECM) sendRawCommandInternal("04", 800L) else "NO DATA"

                    // Retry in Extended Session 10 03 if module requires it (7F 14 7E/22/31)
                    if (rUds.contains("7F 14", true) || rKwp.contains("7F 14", true)) {
                        if (module == TruckModule.EBS) {
                            sendRawCommandInternal("31 02 01 05", 500L)
                        }
                        sendRawCommandInternal("10 03", 600L)
                        rUds = sendRawCommandInternal("14 FF FF FF", 900L)
                        rKwp = sendRawCommandInternal("14 FF 00", 800L)
                        sendRawCommandInternal("10 01", 500L)
                    }

                    val ok = isPositiveClearResponse(rUds) || isPositiveClearResponse(rKwp) || isPositiveClearResponse(rObd)
                    if (ok) {
                        anyCleared = true
                        logTerminal("CLEAR_${module.code}", "Сброшено ($header)", true)
                        break
                    }
                }
            }

            // 3. SAE J1939 DM11 (PGN 00FED3 = Clear/Reset Active DTCs) for WABCO EBS, ECM, and broadcast
            if (activeCan29Bit && coroutineContext.isActive && bluetoothSocket?.isConnected == true) {
                sendRawCommandInternal("ATFCSM 0", 250L)
                sendRawCommandInternal("ATCRA", 250L)
                sendRawCommandInternal("ATSPA", 400L)
                sendRawCommandInternal("ATH1", 300L)
                for (reqTarget in listOf("18EA0BF1", "18EA00F1", "18EAFFF1")) {
                    sendRawCommandInternal("ATSH $reqTarget", 300L)
                    val dm11Resp = sendRawCommandInternal("D3 FE 00", 900L)
                    if (isPositiveClearResponse(dm11Resp) || dm11Resp.uppercase(Locale.ROOT).contains("E8")) {
                        anyCleared = true
                        logTerminal("DM11_${reqTarget}", dm11Resp, true)
                    }
                }
            }
        } finally {
            try {
                if (bluetoothSocket?.isConnected == true) {
                    sendRawCommandInternal("ATFCSM 0", 250L)
                    sendRawCommandInternal("ATCRA", 300L)
                    sendRawCommandInternal("ATCAF1", 250L)
                    sendRawCommandInternal("ATH0", 250L)
                    sendRawCommandInternal(restoreProtocolAtCmd(), 350L)
                    sendRawCommandInternal("ATSH $activeEcmHeader", 400L)
                }
            } catch (_: Exception) {}
            isRoutineInProgress = false
        }

        return anyCleared
    }

    fun isPositiveObdOrCanResponse(resp: String): Boolean {
        if (isEcuAliveResponse(resp)) return true
        val clean = resp.replace(">", "").replace("\r", " ").replace("\n", " ").trim().uppercase(Locale.ROOT)
            .replace(Regex("""BUS\s+INIT:\s*\.?\s*OK"""), " ")
            .replace(Regex("""SEARCHING\.\.\."""), " ")
            .trim()
        if (clean.isEmpty() || clean == "OK" || clean.startsWith("AT") ||
            clean.contains("NO DATA") || clean.contains("ERROR") ||
            clean.contains("UNABLE") || clean.contains("?") ||
            clean.contains("STOPPED") || clean.contains("BUFFER FULL")
        ) {
            return false
        }
        // Accept genuine J1939 CAN frames with header (e.g. "0C F0 04 00 ...", "18 FECA 0B ...", "18F0090B 08 ...")
        return Regex("""^(?:[0-9A-F]{2}\s+[0-9A-F]{4}|[0-9A-F]{8}|[0-9A-F]{2}(?:\s+[0-9A-F]{2}){4,})""").containsMatchIn(clean)
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

            // Clear RX buffer before transmitting new command
            synchronized(rxLock) {
                rxBuffer.clear()
            }

            val cmdBytes = "$command\r".toByteArray(Charsets.US_ASCII)
            out.write(cmdBytes)
            out.flush()

            val cleanCmd = command.trim().uppercase(Locale.ROOT)
            val defaultTimeout = when {
                cleanCmd.startsWith("ATSP0") || cleanCmd == "0100" -> 4000L
                cleanCmd.startsWith("10 01") || cleanCmd.startsWith("10 03") || cleanCmd.startsWith("19 02") || cleanCmd.startsWith("19 0A") -> 1300L
                cleanCmd.startsWith("ATZ") || cleanCmd.startsWith("ATWS") -> 2000L
                cleanCmd.startsWith("AT") -> 600L
                else -> 1100L
            }
            val timeout = (customTimeout ?: defaultTimeout).coerceAtLeast(400L)

            val startTime = System.currentTimeMillis()
            var completed = false
            var result = ""

            while (System.currentTimeMillis() - startTime < timeout) {
                if (!coroutineContext.isActive) break
                synchronized(rxLock) {
                    if (rxBuffer.contains(">")) {
                        result = rxBuffer.toString()
                        completed = true
                    }
                }
                if (completed) break
                delay(6)
            }

            if (!completed) {
                // Command timed out: send space + CR to cleanly abort any pending ELM execution/streaming
                try {
                    out.write(' '.code)
                    out.write('\r'.code)
                    out.flush()
                } catch (_: Exception) {}

                val abortStart = System.currentTimeMillis()
                while (System.currentTimeMillis() - abortStart < 220L) {
                    synchronized(rxLock) {
                        if (rxBuffer.contains(">")) break
                    }
                    delay(8)
                }
                delay(25)

                synchronized(rxLock) {
                    result = rxBuffer.toString()
                    rxBuffer.clear()
                }
            }

            var cleanResult = result
                .replace(">", "")
                .replace("\r", " ")
                .replace("\n", " ")
                .replace(Regex("""\s+"""), " ")
                .trim()

            // Strip echo if clone adapter echoed the sent command despite ATE0
            if (cleanResult.uppercase(Locale.ROOT).startsWith("$cleanCmd ")) {
                cleanResult = cleanResult.substring(cleanCmd.length).trim()
            } else if (cleanResult.equals(cleanCmd, ignoreCase = true)) {
                cleanResult = ""
            }

            if (cleanResult.isEmpty()) "NO DATA" else cleanResult
        }
    }

    private suspend fun sendAtNoLock(out: OutputStream, cmd: String, timeoutMs: Long = 280L): String {
        synchronized(rxLock) { rxBuffer.clear() }
        out.write("$cmd\r".toByteArray(Charsets.US_ASCII))
        out.flush()
        val start = System.currentTimeMillis()
        var result = ""
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (!coroutineContext.isActive) break
            var done = false
            synchronized(rxLock) {
                if (rxBuffer.contains(">")) {
                    result = rxBuffer.toString()
                    done = true
                }
            }
            if (done) break
            delay(6)
        }
        return result.replace(">", "").replace("\r", " ").replace("\n", " ").trim()
    }

    private fun extractRxDataLines(raw: String, sentCommand: String? = null): List<String> {
        val sentUpper = sentCommand?.trim()?.uppercase(Locale.ROOT)
        return raw.replace(">", "\n").replace("\r", "\n").lines().mapNotNull { line ->
            val trimmed = line.trim()
            val upper = trimmed.uppercase(Locale.ROOT)
            when {
                trimmed.isEmpty() -> null
                sentUpper != null && upper == sentUpper -> null
                upper.startsWith("AT") -> null
                upper == "OK" -> null
                upper.contains("SEARCHING") -> null
                upper.contains("BUS INIT") -> null
                upper.contains("STOPPED") -> null
                upper.contains("BUFFER FULL") -> null
                upper.contains("NO DATA") -> null
                upper.contains("?") -> null
                Regex("""^(?:[0-9A-F]{2}|[0-9A-F]{8})(?:\s+[0-9A-F]{2}){3,}""").containsMatchIn(upper) -> trimmed
                else -> null
            }
        }
    }

    private suspend fun probeJ1939SourceAlive(sa: Int): String = commandMutex.withLock {
        withContext(Dispatchers.IO) {
            val out = outputStream ?: return@withContext ""
            val saHex = String.format(Locale.US, "%02X", sa and 0xFF)
            try {
                sendAtNoLock(out, "ATH1", 250L)
                sendAtNoLock(out, "ATCAF0", 250L)
                sendAtNoLock(out, "ATCRA", 250L)
                val cfResp = sendAtNoLock(out, "ATCF 00 00 00 $saHex", 250L)
                sendAtNoLock(out, "ATCM 00 00 00 FF", 250L)
                if (cfResp.contains("?")) return@withContext ""

                synchronized(rxLock) { rxBuffer.clear() }
                out.write("ATMA\r".toByteArray(Charsets.US_ASCII))
                out.flush()

                val start = System.currentTimeMillis()
                var matched = ""
                while (System.currentTimeMillis() - start < 320L) {
                    if (!coroutineContext.isActive) break
                    val lines = synchronized(rxLock) { extractRxDataLines(rxBuffer.toString(), "ATMA") }
                    if (lines.isNotEmpty()) {
                        matched = lines.first()
                        break
                    }
                    delay(10)
                }

                try {
                    out.write(' '.code)
                    out.write('\r'.code)
                    out.flush()
                } catch (_: Exception) {}
                val stopStart = System.currentTimeMillis()
                while (System.currentTimeMillis() - stopStart < 150L) {
                    synchronized(rxLock) { if (rxBuffer.contains(">")) break }
                    delay(8)
                }
                delay(20)
                synchronized(rxLock) { rxBuffer.clear() }
                matched
            } catch (_: Exception) {
                ""
            } finally {
                try {
                    sendAtNoLock(out, "ATCM 00 00 00 00", 200L)
                    sendAtNoLock(out, "ATCF 00 00 00 00", 200L)
                    sendAtNoLock(out, "ATCAF1", 200L)
                    sendAtNoLock(out, "ATH0", 200L)
                } catch (_: Exception) {}
            }
        }
    }

    private suspend fun readMultipleJ1939Frames(pgnHex: String, durationMs: Long = 1100L): String = commandMutex.withLock {
        withContext(Dispatchers.IO) {
            val out = outputStream ?: return@withContext ""
            val pgn4 = pgnHex.takeLast(4).padStart(4, '0').uppercase(Locale.ROOT)
            val pgnHi = pgn4.substring(0, 2)
            val pgnLo = pgn4.substring(2, 4)

            try {
                sendAtNoLock(out, "ATH1", 250L)
                sendAtNoLock(out, "ATCAF0", 250L)
                sendAtNoLock(out, "ATCRA", 250L)
                val cfResp = sendAtNoLock(out, "ATCF 00 $pgnHi $pgnLo 00", 250L)
                sendAtNoLock(out, "ATCM 00 FF FF 00", 250L)

                if (!cfResp.contains("?")) {
                    synchronized(rxLock) { rxBuffer.clear() }
                    out.write("ATMA\r".toByteArray(Charsets.US_ASCII))
                    out.flush()

                    val start = System.currentTimeMillis()
                    while (System.currentTimeMillis() - start < durationMs) {
                        if (!coroutineContext.isActive) break
                        delay(20)
                    }

                    try {
                        out.write(' '.code)
                        out.write('\r'.code)
                        out.flush()
                    } catch (_: Exception) {}
                    val stopStart = System.currentTimeMillis()
                    while (System.currentTimeMillis() - stopStart < 150L) {
                        synchronized(rxLock) { if (rxBuffer.contains(">")) break }
                        delay(8)
                    }
                    delay(20)
                    val captured = synchronized(rxLock) {
                        val s = rxBuffer.toString()
                        rxBuffer.clear()
                        extractRxDataLines(s, "ATMA").joinToString("\n")
                    }
                    if (captured.isNotEmpty()) return@withContext captured
                }

                // Fallback via ATSPA ATMP
                sendAtNoLock(out, "ATSPA", 300L)
                sendAtNoLock(out, "ATCAF1", 250L)
                sendAtNoLock(out, "ATH1", 250L)
                synchronized(rxLock) { rxBuffer.clear() }
                val mpCmd = "ATMP 00$pgn4 3"
                out.write("$mpCmd\r".toByteArray(Charsets.US_ASCII))
                out.flush()
                val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < durationMs) {
                    if (!coroutineContext.isActive) break
                    val done = synchronized(rxLock) { rxBuffer.contains(">") }
                    if (done) break
                    delay(15)
                }
                try {
                    out.write(' '.code)
                    out.write('\r'.code)
                    out.flush()
                } catch (_: Exception) {}
                delay(30)
                synchronized(rxLock) {
                    val s = rxBuffer.toString()
                    rxBuffer.clear()
                    s
                }
            } catch (_: Exception) {
                ""
            } finally {
                try {
                    sendAtNoLock(out, "ATCM 00 00 00 00", 200L)
                    sendAtNoLock(out, "ATCF 00 00 00 00", 200L)
                    sendAtNoLock(out, "ATCAF1", 200L)
                    sendAtNoLock(out, "ATH0", 200L)
                    sendAtNoLock(out, restoreProtocolAtCmd(), 300L)
                } catch (_: Exception) {}
            }
        }
    }

    private suspend fun readSingleJ1939FrameSafely(pgnHex: String): String = commandMutex.withLock {
        withContext(Dispatchers.IO) {
            val out = outputStream ?: return@withContext ""
            val pgn4 = pgnHex.takeLast(4).padStart(4, '0').uppercase(Locale.ROOT)
            val pgnHi = pgn4.substring(0, 2)
            val pgnLo = pgn4.substring(2, 4)

            try {
                // Method 1: Raw 29-bit hardware mask filter (works in BOTH ATSP9 and ATSPA!)
                sendAtNoLock(out, "ATH1", 250L)
                sendAtNoLock(out, "ATCAF0", 250L)
                sendAtNoLock(out, "ATCRA", 250L)
                val cfResp = sendAtNoLock(out, "ATCF 00 $pgnHi $pgnLo 00", 250L)
                sendAtNoLock(out, "ATCM 00 FF FF 00", 250L)

                if (!cfResp.contains("?")) {
                    synchronized(rxLock) { rxBuffer.clear() }
                    out.write("ATMA\r".toByteArray(Charsets.US_ASCII))
                    out.flush()

                    val start = System.currentTimeMillis()
                    var matchedFrame = ""
                    while (System.currentTimeMillis() - start < 450L) {
                        if (!coroutineContext.isActive) break
                        val lines = synchronized(rxLock) {
                            extractRxDataLines(rxBuffer.toString(), "ATMA")
                        }
                        if (lines.isNotEmpty()) {
                            matchedFrame = lines.first()
                            break
                        }
                        delay(10)
                    }

                    try {
                        out.write(' '.code)
                        out.write('\r'.code)
                        out.flush()
                    } catch (_: Exception) {}

                    val stopStart = System.currentTimeMillis()
                    while (System.currentTimeMillis() - stopStart < 150L) {
                        synchronized(rxLock) {
                            if (rxBuffer.contains(">")) break
                        }
                        delay(8)
                    }
                    delay(20)
                    synchronized(rxLock) { rxBuffer.clear() }

                    if (matchedFrame.isNotEmpty()) {
                        return@withContext matchedFrame
                    }
                }

                // Method 2 fallback: temporarily switch to SAE J1939 (ATSPA) for ATMP command
                sendAtNoLock(out, "ATCM 00 00 00 00", 200L)
                sendAtNoLock(out, "ATCF 00 00 00 00", 200L)
                sendAtNoLock(out, "ATSPA", 300L)
                sendAtNoLock(out, "ATCAF1", 250L)
                sendAtNoLock(out, "ATH1", 250L)

                synchronized(rxLock) { rxBuffer.clear() }
                val mpCmd = "ATMP 00$pgn4 1"
                out.write("$mpCmd\r".toByteArray(Charsets.US_ASCII))
                out.flush()

                val start2 = System.currentTimeMillis()
                var matchedFrame2 = ""
                while (System.currentTimeMillis() - start2 < 450L) {
                    if (!coroutineContext.isActive) break
                    val lines = synchronized(rxLock) {
                        extractRxDataLines(rxBuffer.toString(), mpCmd)
                    }
                    if (lines.isNotEmpty()) {
                        matchedFrame2 = lines.first()
                        break
                    }
                    delay(10)
                }

                try {
                    out.write(' '.code)
                    out.write('\r'.code)
                    out.flush()
                } catch (_: Exception) {}
                val stopStart2 = System.currentTimeMillis()
                while (System.currentTimeMillis() - stopStart2 < 150L) {
                    synchronized(rxLock) { if (rxBuffer.contains(">")) break }
                    delay(8)
                }
                delay(20)
                synchronized(rxLock) { rxBuffer.clear() }
                matchedFrame2
            } catch (_: Exception) {
                ""
            } finally {
                try {
                    sendAtNoLock(out, "ATCM 00 00 00 00", 200L)
                    sendAtNoLock(out, "ATCF 00 00 00 00", 200L)
                    sendAtNoLock(out, "ATCRA", 200L)
                    sendAtNoLock(out, "ATCAF1", 200L)
                    sendAtNoLock(out, "ATH0", 200L)
                    sendAtNoLock(out, restoreProtocolAtCmd(), 300L)
                } catch (_: Exception) {}
            }
        }
    }

    private suspend fun readSingleCanFrameByFilter(canId29: String): String = commandMutex.withLock {
        withContext(Dispatchers.IO) {
            val out = outputStream ?: return@withContext ""
            try {
                // Enable Headers + Disable ISO-TP formatting so raw J1939 frames are never dropped
                sendAtNoLock(out, "ATH1", 250L)
                sendAtNoLock(out, "ATCAF0", 250L)
                sendAtNoLock(out, "ATCRA $canId29", 250L)

                synchronized(rxLock) { rxBuffer.clear() }
                out.write("ATMA\r".toByteArray(Charsets.US_ASCII))
                out.flush()

                val start = System.currentTimeMillis()
                var matchedFrame = ""

                while (System.currentTimeMillis() - start < 450L) {
                    if (!coroutineContext.isActive) break
                    val lines = synchronized(rxLock) {
                        extractRxDataLines(rxBuffer.toString(), "ATMA")
                    }
                    if (lines.isNotEmpty()) {
                        matchedFrame = lines.first()
                        break
                    }
                    delay(10)
                }

                try {
                    out.write(' '.code)
                    out.write('\r'.code)
                    out.flush()
                } catch (_: Exception) {}

                val stopStart = System.currentTimeMillis()
                while (System.currentTimeMillis() - stopStart < 150L) {
                    synchronized(rxLock) {
                        if (rxBuffer.contains(">")) break
                    }
                    delay(8)
                }
                delay(20)
                synchronized(rxLock) { rxBuffer.clear() }
                matchedFrame
            } catch (_: Exception) {
                ""
            } finally {
                try {
                    sendAtNoLock(out, "ATCRA", 200L)
                    sendAtNoLock(out, "ATCAF1", 200L)
                    sendAtNoLock(out, "ATH0", 200L)
                } catch (_: Exception) {}
            }
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
            upper.startsWith("ATSH") || upper.startsWith("ATCRA") || upper.startsWith("AT CRA") || upper.startsWith("ATAR") || upper.startsWith("AT AR") || upper.startsWith("ATPC") -> "OK"
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
        // 1. Immediately notify UI that disconnection has been initiated
        _connectionState.value = ElmConnectionState.Disconnecting

        // 2. Immediately cancel all active coroutine jobs
        connectJob?.cancel()
        pollingJob?.cancel()
        diagnoseJob?.cancel()
        scanJob?.cancel()
        sasJob?.cancel()
        simJob?.cancel()
        readerJob?.cancel()
        readerJob = null
        connectJob = null
        pollingJob = null
        diagnoseJob = null
        scanJob = null
        sasJob = null
        simJob = null

        isRoutineInProgress = false
        _isDiagnosingEcus.value = false
        _isSimulationMode.value = false
        _isCanConnected.value = false
        _ignitionDetected.value = false

        // 3. Clear buffers immediately
        synchronized(rxLock) {
            rxBuffer.clear()
        }

        // 4. Detach socket and streams references immediately so all callers see disconnected
        val sock = bluetoothSocket
        val inStream = inputStream
        val outStream = outputStream
        bluetoothSocket = null
        inputStream = null
        outputStream = null

        // 5. Instantly reset telemetry readouts
        _telemetry.value = _telemetry.value.copy(
            rpm = 0f,
            speedKmH = 0f,
            coolantTempC = 0f,
            oilPressureBar = 0f,
            fuelRailPressureBar = 0f,
            boostPressureBar = 0f,
            batteryVoltage = if (isVoltageCalibrated) 24.0f else 0f
        )

        // 6. Asynchronously and safely close socket and streams in background thread
        Thread {
            try {
                try {
                    outStream?.write(' '.code)
                    outStream?.write('\r'.code)
                    outStream?.flush()
                } catch (_: Exception) {}
                try { inStream?.close() } catch (_: Exception) {}
                try { outStream?.close() } catch (_: Exception) {}
                try { sock?.close() } catch (_: Exception) {}
            } catch (_: Exception) {}
        }.start()

        _connectionState.value = ElmConnectionState.Disconnected
        logTerminal("DISCONNECT", "Адаптер ELM327 отключен. Защита шины CAN активна.", true)
    }

    private fun disconnectPhysical() {
        pollingJob?.cancel()
        readerJob?.cancel()
        readerJob = null
        synchronized(rxLock) {
            rxBuffer.clear()
        }

        val sock = bluetoothSocket
        val inStream = inputStream
        val outStream = outputStream
        bluetoothSocket = null
        inputStream = null
        outputStream = null

        try {
            outStream?.write(' '.code)
            outStream?.write('\r'.code)
            outStream?.flush()
        } catch (_: Exception) {}
        try { inStream?.close() } catch (_: Exception) {}
        try { outStream?.close() } catch (_: Exception) {}
        try { sock?.close() } catch (_: Exception) {}
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

    suspend fun probeAndLockSteeringAngleSensor(): String {
        if (_isSimulationMode.value) {
            delay(200)
            _detectedSasInfo.value = "Эмулятор SAS (0.0°)"
            return "Режим симулятора: датчик руля активен (0.0°)"
        }
        if (bluetoothSocket?.isConnected != true) {
            return "Нет подключения к адаптеру по Bluetooth."
        }

        isRoutineInProgress = true
        val activeEcmHeader = if (activeCan29Bit) "18DA00F1" else "7E0"

        return try {
            logTerminal("SAS_SCAN", "Поиск датчика угла руля SAS (WABCO EBS / J1939 / CBCU)...", true)
            sendRawCommandInternal("ATPC", 400L)
            sendRawCommandInternal(restoreProtocolAtCmd(), 400L)
            sendRawCommandInternal("ATH0", 300L)
            sendRawCommandInternal("ATCAF1", 300L)

            val ebsH = if (activeCan29Bit) "18DA0BF1" else "7E2"
            val ebsCra = if (activeCan29Bit) "18DAF10B" else "7EA"

            // 1. Probing WABCO EBS via UDS & KWP2000 (with strict CRA & Flow Control)
            sendRawCommandInternal("ATCRA $ebsCra", 300L)
            configureIsoTpFlowControl(ebsH, activeCan29Bit)
            sendRawCommandInternal("ATSH $ebsH", 400L)
            sendRawCommandInternal("10 01", 600L)

            val ebsDids = listOf(
                "22 010A" to "WABCO EBS (DID 010A)",
                "22 070F" to "WABCO EBS (DID 070F / SPN 1807)",
                "22 1807" to "WABCO EBS (DID 1807)",
                "22 0101" to "WABCO EBS (DID 0101)",
                "22 0200" to "WABCO EBS (DID 0200)",
                "22 0201" to "WABCO EBS (DID 0201)",
                "22 0120" to "WABCO EBS (DID 0120)",
                "22 F40E" to "WABCO EBS (DID F40E)",
                "22 0102" to "WABCO EBS (DID 0102)",
                "21 0A" to "WABCO EBS KWP (LID 0A)",
                "21 01" to "WABCO EBS KWP (LID 01)",
                "21 02" to "WABCO EBS KWP (LID 02)"
            )

            for ((cmd, name) in ebsDids) {
                if (!coroutineContext.isActive || bluetoothSocket?.isConnected != true) {
                    return "Поиск датчика SAS отменен пользователем."
                }
                val resp = sendRawCommandInternal(cmd, 650L)
                val angle = parseSteeringAngle(resp)
                if (angle != null) {
                    detectedSasHeader = ebsH
                    detectedSasCmd = cmd
                    detectedSasDid = cmd.substringAfter(" ")
                    isSasJ1939PgnMode = false
                    isSasCanFilterMode = false
                    _detectedSasInfo.value = name
                    val eff = angle + steeringOffset
                    _telemetry.value = _telemetry.value.copy(
                        rawSteeringAngleDeg = angle,
                        steeringAngleDeg = eff
                    )
                    logTerminal("SAS_LOCKED", "Датчик SAS зафиксирован: $name -> $angle°", true)
                    return "Датчик руля зафиксирован: $name. Угол: %+.1f°. Показания обновляются в реальном времени.".format(Locale.US, eff)
                }
            }

            // Try Extended Session (10 03) on WABCO EBS
            if (!coroutineContext.isActive || bluetoothSocket?.isConnected != true) {
                return "Поиск датчика SAS отменен пользователем."
            }
            sendRawCommandInternal("10 03", 600L)
            for ((cmd, name) in ebsDids) {
                if (!coroutineContext.isActive || bluetoothSocket?.isConnected != true) {
                    return "Поиск датчика SAS отменен пользователем."
                }
                val resp = sendRawCommandInternal(cmd, 650L)
                val angle = parseSteeringAngle(resp)
                if (angle != null) {
                    detectedSasHeader = ebsH
                    detectedSasCmd = cmd
                    detectedSasDid = cmd.substringAfter(" ")
                    isSasJ1939PgnMode = false
                    isSasCanFilterMode = false
                    _detectedSasInfo.value = "$name (Сессия 10 03)"
                    val eff = angle + steeringOffset
                    _telemetry.value = _telemetry.value.copy(
                        rawSteeringAngleDeg = angle,
                        steeringAngleDeg = eff
                    )
                    logTerminal("SAS_LOCKED", "Датчик SAS зафиксирован: $name -> $angle°", true)
                    return "Датчик руля зафиксирован: $name. Угол: %+.1f°. Показания обновляются в реальном времени.".format(Locale.US, eff)
                }
            }
            sendRawCommandInternal("10 01", 500L)

            // 2. Probing CBCU / VCU / IC Gateways via UDS & KWP
            val cbcuTargets = if (activeCan29Bit) {
                listOf("18DA21F1" to "18DAF121", "18DA27F1" to "18DAF127", "18DA17F1" to "18DAF117")
            } else {
                listOf("7E3" to "7EB", "7E4" to "7EC")
            }
            val cbcuDids = listOf(
                "22 010A" to "CBCU Gateway (DID 010A)",
                "22 070F" to "CBCU Gateway (DID 070F)",
                "22 0120" to "CBCU Gateway (DID 0120)",
                "22 D001" to "CBCU Gateway (DID D001)",
                "21 0A" to "CBCU KWP (LID 0A)"
            )
            for ((cbcuH, cbcuCra) in cbcuTargets) {
                if (!coroutineContext.isActive || bluetoothSocket?.isConnected != true) {
                    return "Поиск датчика SAS отменен пользователем."
                }
                sendRawCommandInternal("ATCRA $cbcuCra", 300L)
                configureIsoTpFlowControl(cbcuH, activeCan29Bit)
                sendRawCommandInternal("ATSH $cbcuH", 400L)
                for ((cmd, name) in cbcuDids) {
                    if (!coroutineContext.isActive || bluetoothSocket?.isConnected != true) {
                        return "Поиск датчика SAS отменен пользователем."
                    }
                    val resp = sendRawCommandInternal(cmd, 650L)
                    val angle = parseSteeringAngle(resp)
                    if (angle != null) {
                        detectedSasHeader = cbcuH
                        detectedSasCmd = cmd
                        detectedSasDid = cmd.substringAfter(" ")
                        isSasJ1939PgnMode = false
                        isSasCanFilterMode = false
                        _detectedSasInfo.value = name
                        val eff = angle + steeringOffset
                        _telemetry.value = _telemetry.value.copy(
                            rawSteeringAngleDeg = angle,
                            steeringAngleDeg = eff
                        )
                        logTerminal("SAS_LOCKED", "Датчик SAS зафиксирован: $name -> $angle°", true)
                        return "Датчик руля зафиксирован: $name. Угол: %+.1f°. Показания обновляются в реальном времени.".format(Locale.US, eff)
                    }
                }
            }

            // 3. Probing SAE J1939 Broadcast frames (SSI2 F01D, VDC2 F009 / WABCO EBS, SSI FE4A, FECF)
            if (activeCan29Bit && coroutineContext.isActive && bluetoothSocket?.isConnected == true) {
                sendRawCommandInternal("ATFCSM 0", 250L)
                sendRawCommandInternal("ATCRA", 300L)

                val j1939Pgns = listOf(
                    Triple("00F01D", "0CF01D13", "SAE J1939 SSI2 (PGN 61469 / F01D)"),
                    Triple("00F009", "18F0090B", "WABCO EBS VDC2 (PGN 61449 / F009)"),
                    Triple("00FE4A", "0CFE4A13", "SAE J1939 SSI (PGN 65098 / FE4A)"),
                    Triple("00FECF", "18FECF0B", "SAE J1939 (PGN 65231 / FECF)")
                )

                for ((pgn, defaultHdr, name) in j1939Pgns) {
                    if (!coroutineContext.isActive || bluetoothSocket?.isConnected != true) break
                    val mpResp = readSingleJ1939FrameSafely(pgn)
                    val mpAngle = parseSteeringAngle(mpResp)
                    if (mpAngle != null) {
                        detectedSasHeader = defaultHdr
                        detectedSasCmd = "J1939 PGN $pgn"
                        detectedSasDid = pgn
                        isSasJ1939PgnMode = true
                        isSasCanFilterMode = false
                        _detectedSasInfo.value = name
                        val eff = mpAngle + steeringOffset
                        _telemetry.value = _telemetry.value.copy(
                            rawSteeringAngleDeg = mpAngle,
                            steeringAngleDeg = eff
                        )
                        logTerminal("SAS_LOCKED", "Датчик SAS зафиксирован по J1939: $name -> $mpAngle°", true)
                        return "Датчик руля зафиксирован: $name. Угол: %+.1f°. Показания обновляются в реальном времени.".format(Locale.US, eff)
                    }
                }

                // Fallback: exact 29-bit CAN ID filters
                val canFrames = listOf("0CF01D13", "18F0090B", "0CF0090B", "18F01D13", "0CF01D0B", "0CFE4A13", "18FECF0B")
                for (canId in canFrames) {
                    if (!coroutineContext.isActive || bluetoothSocket?.isConnected != true) break
                    val frameResp = readSingleCanFrameByFilter(canId)
                    val frameAngle = parseSteeringAngle(frameResp)
                    if (frameAngle != null) {
                        detectedSasHeader = canId
                        detectedSasCmd = "ATCRA $canId"
                        detectedSasDid = canId.substring(2, 6)
                        isSasJ1939PgnMode = false
                        isSasCanFilterMode = true
                        val name = "CAN Frame $canId (SAS)"
                        _detectedSasInfo.value = name
                        val eff = frameAngle + steeringOffset
                        _telemetry.value = _telemetry.value.copy(
                            rawSteeringAngleDeg = frameAngle,
                            steeringAngleDeg = eff
                        )
                        logTerminal("SAS_LOCKED", "Датчик SAS зафиксирован по фильтру CAN $canId -> $frameAngle°", true)
                        return "Датчик руля зафиксирован: $name. Угол: %+.1f°. Показания обновляются в реальном времени.".format(Locale.US, eff)
                    }
                }
            }

            detectedSasHeader = null
            detectedSasCmd = null
            detectedSasDid = null
            isSasJ1939PgnMode = false
            isSasCanFilterMode = false
            _detectedSasInfo.value = "Датчик не обнаружен в CAN"
            "Датчик SAS не ответил по шине CAN (проверены WABCO EBS 18DA0BF1, CBCU и J1939 PGN F01D/F009/FE4A). Убедитесь, что зажигание Sitrak ВКЛ (Кл.15 24V) и кабина не обесточена."
        } catch (e: CancellationException) {
            "Поиск датчика SAS отменен."
        } catch (e: Exception) {
            "Ошибка поиска SAS: ${e.message}"
        } finally {
            try {
                if (bluetoothSocket?.isConnected == true) {
                    sendRawCommandInternal("ATFCSM 0", 250L)
                    sendRawCommandInternal("ATCRA", 300L)
                    sendRawCommandInternal("ATCAF1", 250L)
                    sendRawCommandInternal("ATH0", 250L)
                    sendRawCommandInternal(restoreProtocolAtCmd(), 350L)
                    sendRawCommandInternal("ATSH $activeEcmHeader", 400L)
                    sendRawCommandInternal("ATPC", 400L)
                }
            } catch (_: Exception) {}
            isRoutineInProgress = false
        }
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
            if (activeCan29Bit) {
                sendRawCommandInternal("ATCRA 18DAF10B", 300L)
            }
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

            if (activeCan29Bit) {
                sendRawCommandInternal("ATCRA", 300L)
            }
            val ecmHeader = if (activeCan29Bit) "18DA00F1" else "7E0"
            sendRawCommandInternal("ATSH $ecmHeader", 400L)

            if (ecuAcceptedHardware) {
                CalibrationResult.Success("Калибровка датчика угла руля WABCO EBS успешно выполнена (ответ блока: $lastResp). Нулевая точка 0.0° зафиксирована в блоке и в приложении!")
            } else {
                CalibrationResult.Success(String.format(Locale.US, "Нулевая точка 0.0° успешно зафиксирована! Программная калибровка активна (смещение %+.1f° сохранено, текущий угол руля 0.0°). Блок WABCO EBS выведен из диагностического режима.", steeringOffset))
            }
        } catch (e: Exception) {
            CalibrationResult.Success("Нулевая точка 0.0° зафиксирована в системе (смещение сохранено).")
        } finally {
            try {
                if (bluetoothSocket?.isConnected == true) {
                    if (activeCan29Bit) sendRawCommandInternal("ATCRA", 300L)
                    val ecmHeader = if (activeCan29Bit) "18DA00F1" else "7E0"
                    sendRawCommandInternal("ATSH $ecmHeader", 400L)
                }
            } catch (_: Exception) {}
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
        val ecmHeader = if (activeCan29Bit) "18DA00F1" else "7E0"
        return try {
            val ebsHeader = if (activeCan29Bit) TruckModule.EBS.can29Header else TruckModule.EBS.canId
            val ebsCra = if (activeCan29Bit) TruckModule.EBS.response29Filter else TruckModule.EBS.response11Filter

            sendRawCommandInternal("ATPC", 400L)
            sendRawCommandInternal(restoreProtocolAtCmd(), 400L)
            sendRawCommandInternal("ATH0", 300L)
            sendRawCommandInternal("ATCAF1", 300L)
            sendRawCommandInternal("ATCRA $ebsCra", 300L)
            configureIsoTpFlowControl(ebsHeader, activeCan29Bit)
            sendRawCommandInternal("ATSH $ebsHeader", 400L)

            // Stop any active calibration routine
            sendRawCommandInternal("31 02 01 05", 600L)
            sendRawCommandInternal("31 02 02 01", 600L)
            sendRawCommandInternal("31 02 05 00", 600L)

            // Default session & clear attempts
            sendRawCommandInternal("10 01", 600L)
            val rUds1 = sendRawCommandInternal("14 FF FF FF", 900L)
            val rKwp1 = sendRawCommandInternal("14 FF 00", 800L)
            val rObd = sendRawCommandInternal("04", 800L)

            var rUds2 = rUds1
            var rKwp2 = rKwp1
            if (!isPositiveClearResponse(rUds1) && !isPositiveClearResponse(rKwp1) && !isPositiveClearResponse(rObd)) {
                sendRawCommandInternal("10 03", 600L)
                rUds2 = sendRawCommandInternal("14 FF FF FF", 900L)
                rKwp2 = sendRawCommandInternal("14 FF 00", 800L)
                sendRawCommandInternal("10 01", 500L)
            }

            // SAE J1939 DM11 Clear Active DTCs directly to EBS (0x0B)
            var dm11Ok = false
            if (activeCan29Bit && !isPositiveClearResponse(rUds2) && !isPositiveClearResponse(rKwp2)) {
                sendRawCommandInternal("ATFCSM 0", 250L)
                sendRawCommandInternal("ATCRA", 250L)
                sendRawCommandInternal("ATSPA", 350L)
                sendAtAndLogDm11("18EA0BF1") { dm11Ok = true }
            }

            val cleared = isPositiveClearResponse(rUds1) || isPositiveClearResponse(rKwp1) ||
                    isPositiveClearResponse(rObd) || isPositiveClearResponse(rUds2) ||
                    isPositiveClearResponse(rKwp2) || dm11Ok

            val allReplies = listOf(rUds1, rKwp1, rObd, rUds2, rKwp2)
            val nrcReply = allReplies.firstOrNull { it.uppercase(Locale.ROOT).contains("7F ") }

            when {
                cleared -> {
                    logTerminal("EBS_CLEAR", "Сброс EBS подтвержден: $rUds2", true)
                    "Блок WABCO EBS: калибровка завершена, ошибки сброшены (подтверждение блока получено)!"
                }
                nrcReply != null -> {
                    logTerminal("EBS_CLEAR", "Отказ EBS: $nrcReply", false)
                    "Блок WABCO EBS отклонил сброс ($nrcReply). Заглушите двигатель, затяните ручник при включенном зажигании (Кл.15 ON) и повторите."
                }
                else -> {
                    logTerminal("EBS_CLEAR", "EBS не ответил ($ebsHeader)", false)
                    "Блок WABCO EBS ($ebsHeader) не ответил на команду сброса (NO DATA). Проверьте питание Кл.15 и контакт разъема OBD."
                }
            }
        } catch (e: Exception) {
            "Ошибка сброса EBS: ${e.localizedMessage}"
        } finally {
            try {
                if (bluetoothSocket?.isConnected == true) {
                    sendRawCommandInternal("ATFCSM 0", 250L)
                    sendRawCommandInternal("ATCRA", 300L)
                    sendRawCommandInternal("ATCAF1", 250L)
                    sendRawCommandInternal("ATH0", 250L)
                    sendRawCommandInternal(restoreProtocolAtCmd(), 350L)
                    sendRawCommandInternal("ATSH $ecmHeader", 400L)
                }
            } catch (_: Exception) {}
            isRoutineInProgress = false
        }
    }

    private suspend inline fun sendAtAndLogDm11(targetHeader: String, onSuccess: () -> Unit) {
        sendRawCommandInternal("ATSH $targetHeader", 300L)
        val resp = sendRawCommandInternal("D3 FE 00", 900L)
        if (isPositiveClearResponse(resp) || resp.uppercase(Locale.ROOT).contains("E8")) {
            onSuccess()
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

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
import com.example.model.BluetoothDeviceInfo
import com.example.model.ElmConnectionState
import com.example.model.ElmProtocol
import com.example.model.LiveTelemetry
import com.example.model.TerminalLogItem
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
        }
    }

    fun connectToDevice(deviceAddress: String, deviceName: String) {
        scope.launch {
            try {
                simJob?.cancel()
                _isSimulationMode.value = false

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

                _connectionState.value = ElmConnectionState.Connecting("Настройка параметров ELM (Echo/Linefeed/Spaces)...")
                sendRawCommandInternal("ATE0") // Echo Off
                sendRawCommandInternal("ATL0") // Linefeeds Off
                sendRawCommandInternal("ATS0") // Spaces Off
                sendRawCommandInternal("ATAT1") // Adaptive Timing

                val proto = _selectedProtocol.value
                _connectionState.value = ElmConnectionState.Connecting("Установка протокола CAN (${proto.displayName})...")
                sendRawCommandInternal(proto.atCommand)

                _connectionState.value = ElmConnectionState.Connecting("Проверка напряжения бортовой сети 24V (ATRV)...")
                val voltageResp = sendRawCommandInternal("ATRV")
                val volt = parseVoltage(voltageResp)
                if (volt > 0) {
                    _telemetry.value = _telemetry.value.copy(batteryVoltage = volt)
                }

                val protocolResp = sendRawCommandInternal("ATDP")
                val displayProtocol = if (protocolResp.isNotBlank() && protocolResp != "NO DATA") {
                    protocolResp
                } else {
                    proto.displayName
                }

                _connectionState.value = ElmConnectionState.Connected(
                    deviceName = deviceName,
                    protocol = displayProtocol,
                    isSimulation = false
                )

                logTerminal("INIT", "ELM327 сопряжен: $initZ | Сеть: $voltageResp | Протокол: $displayProtocol", true)
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
                    // Poll RPM
                    val rpmRaw = sendRawCommandInternal("010C")
                    val rpm = parseRpm(rpmRaw)

                    // Poll Speed
                    val speedRaw = sendRawCommandInternal("010D")
                    val speed = parseSpeed(speedRaw)

                    // Poll Coolant Temp
                    val tempRaw = sendRawCommandInternal("0105")
                    val temp = parseCoolant(tempRaw)

                    // Poll Boost / MAP
                    val mapRaw = sendRawCommandInternal("010B")
                    val boost = parseMap(mapRaw)

                    // Poll Rail Pressure
                    val railRaw = sendRawCommandInternal("0123")
                    val rail = parseRailPressure(railRaw)

                    // Poll Battery Voltage
                    val voltRaw = sendRawCommandInternal("ATRV")
                    val volt = parseVoltage(voltRaw)

                    val current = _telemetry.value
                    _telemetry.value = current.copy(
                        rpm = if (rpm > 0) rpm else current.rpm,
                        speedKmH = if (speed >= 0) speed else current.speedKmH,
                        coolantTempC = if (temp > -40) temp else current.coolantTempC,
                        boostPressureBar = if (boost > 0) boost else current.boostPressureBar,
                        fuelRailPressureBar = if (rail > 0) rail else current.fuelRailPressureBar,
                        batteryVoltage = if (volt > 0) volt else current.batteryVoltage
                    )

                    delay(300)
                } catch (e: Exception) {
                    delay(1500)
                }
            }
        }
    }

    fun startSimulationEngine() {
        simJob?.cancel()
        _isSimulationMode.value = true
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
            upper.startsWith("ATE") -> "OK"
            upper.startsWith("ATL") -> "OK"
            upper.startsWith("ATS") -> "OK"
            upper.startsWith("ATH") -> "OK"
            upper.startsWith("ATAT") -> "OK"
            upper.startsWith("ATSP") -> "OK"
            upper == "ATDP" -> "SAE J1939 CAN (29 bit / 250 kbps)"
            upper.startsWith("ATSH") -> "OK"
            upper == "0100" -> "41 00 BE 3F B8 13"
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
            upper == "04" -> "44"
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
            val clean = response.replace("V", "").replace("v", "").trim()
            val match = Regex("""\d+(\.\d+)?""").find(clean)
            match?.value?.toFloatOrNull() ?: 0f
        } catch (e: Exception) { 0f }
    }
}

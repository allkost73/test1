package com.example.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.example.model.BluetoothDeviceInfo
import com.example.model.ElmConnectionState
import com.example.model.LiveTelemetry
import com.example.model.TerminalLogItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private val _connectionState = MutableStateFlow<ElmConnectionState>(ElmConnectionState.Disconnected)
    val connectionState: StateFlow<ElmConnectionState> = _connectionState.asStateFlow()

    private val _telemetry = MutableStateFlow(LiveTelemetry())
    val telemetry: StateFlow<LiveTelemetry> = _telemetry.asStateFlow()

    private val _terminalLogs = MutableStateFlow<List<TerminalLogItem>>(emptyList())
    val terminalLogs: StateFlow<List<TerminalLogItem>> = _terminalLogs.asStateFlow()

    private val _isSimulationMode = MutableStateFlow(true)
    val isSimulationMode: StateFlow<Boolean> = _isSimulationMode.asStateFlow()

    private var bluetoothSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var pollingJob: Job? = null
    private var simJob: Job? = null

    init {
        // Start simulation by default so user can immediately test and explore
        startSimulationEngine()
    }

    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDeviceInfo> {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
        return try {
            adapter.bondedDevices?.map { device ->
                BluetoothDeviceInfo(
                    name = device.name ?: "Неизвестное устройство",
                    address = device.address,
                    isPaired = true
                )
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
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
                _connectionState.value = ElmConnectionState.Connecting("Подключение к $deviceName...")

                val adapter = BluetoothAdapter.getDefaultAdapter()
                    ?: throw IllegalStateException("Bluetooth адаптер недоступен")

                @SuppressLint("MissingPermission")
                val device: BluetoothDevice = adapter.getRemoteDevice(deviceAddress)

                _connectionState.value = ElmConnectionState.Connecting("Создание SPP сокета...")
                @SuppressLint("MissingPermission")
                val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                bluetoothSocket = socket

                withContext(Dispatchers.IO) {
                    socket.connect()
                }

                inputStream = socket.inputStream
                outputStream = socket.outputStream

                // Initialize ELM327 protocol
                _connectionState.value = ElmConnectionState.Connecting("Инициализация ELM327 (ATZ)...")
                val initZ = sendRawCommandInternal("ATZ")
                delay(300)

                _connectionState.value = ElmConnectionState.Connecting("Настройка протокола CAN (ISO 15765-4)...")
                sendRawCommandInternal("ATE0") // Echo Off
                sendRawCommandInternal("ATL0") // Linefeeds Off
                sendRawCommandInternal("ATS0") // Spaces Off
                sendRawCommandInternal("ATH1") // Headers On
                sendRawCommandInternal("ATSP6") // ISO 15765-4 CAN 11/500 (Sitrak EDC17 OBD2)

                val voltageResp = sendRawCommandInternal("ATRV") // Check 24V bus
                val protocolResp = sendRawCommandInternal("ATDP") // Display protocol

                _connectionState.value = ElmConnectionState.Connected(
                    deviceName = deviceName,
                    protocol = if (protocolResp.isNotBlank()) protocolResp else "ISO 15765-4 CAN (Sitrak)",
                    isSimulation = false
                )

                logTerminal("INIT", "ELM327 инициализирован: $initZ, Напряжение: $voltageResp", true)
                startPhysicalPolling()

            } catch (e: Exception) {
                disconnectPhysical()
                _connectionState.value = ElmConnectionState.Error("Ошибка подключения: ${e.localizedMessage ?: "Сбой соединения"}. Запустите симулятор для проверки.")
                logTerminal("CONNECT", "Сбой: ${e.message}", false)
            }
        }
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

                    delay(250)
                } catch (e: Exception) {
                    delay(1000)
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
            var tick = 0
            var simSpeed = 0f
            var driveSimActive = false

            while (isActive) {
                tick++
                val baseRpm = _telemetry.value.rpm
                // Small realistic engine vibration / flutter on Common Rail
                val rpmFlutter = (Random.nextFloat() - 0.5f) * 18f
                val targetRpm = (620f + rpmFlutter).coerceIn(580f, 750f)

                // Battery 24V alternator charge fluctuation
                val voltFlutter = 27.6f + (Random.nextFloat() - 0.5f) * 0.4f

                // Common rail pressure idle ~500-550 bar
                val railFlutter = 520f + (Random.nextFloat() - 0.5f) * 25f

                // Boost pressure at idle ~1.02 - 1.06 bar
                val boostFlutter = 1.04f + (Random.nextFloat() - 0.5f) * 0.04f

                // Oil pressure ~3.7 - 3.9 bar
                val oilFlutter = 3.8f + (Random.nextFloat() - 0.5f) * 0.15f

                // Brake air tanks ~8.3 - 8.5 bar
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
        val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())

        if (_isSimulationMode.value) {
            delay(120) // Realistic ELM response latency
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
        val out = outputStream ?: throw IllegalStateException("Нет подключения к сокету")
        val input = inputStream ?: throw IllegalStateException("Поток ввода недоступен")

        // Clear input buffer
        while (input.available() > 0) {
            input.read()
        }

        val cmdBytes = "$command\r".toByteArray(Charsets.US_ASCII)
        out.write(cmdBytes)
        out.flush()

        val sb = StringBuilder()
        val startTime = System.currentTimeMillis()
        val timeout = 2500L

        while (System.currentTimeMillis() - startTime < timeout) {
            if (input.available() > 0) {
                val b = input.read()
                if (b == -1) break
                val c = b.toChar()
                if (c == '>') { // ELM327 prompt prompt ends response
                    break
                }
                sb.append(c)
            } else {
                Thread.sleep(15)
            }
        }

        val result = sb.toString().replace("\r", " ").replace("\n", " ").trim()
        if (result.isEmpty()) "NO DATA" else result
    }

    private fun simulateCommandResponse(cmd: String): String {
        val upper = cmd.uppercase(Locale.ROOT)
        return when {
            upper == "ATZ" -> "ELM327 v2.1 (SITRAK CAN)"
            upper == "ATRV" -> String.format(Locale.US, "%.1fV", _telemetry.value.batteryVoltage)
            upper.startsWith("ATE") -> "OK"
            upper.startsWith("ATL") -> "OK"
            upper.startsWith("ATS") -> "OK"
            upper.startsWith("ATH") -> "OK"
            upper.startsWith("ATSP") -> "OK"
            upper == "ATDP" -> "ISO 15765-4 (CAN 29/250 SAE J1939)"
            upper.startsWith("ATSH") -> "OK"
            upper == "0100" -> "41 00 BE 3F B8 13" // Supported PIDs
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
            upper == "03" -> "43 04 02 38 20 4F" // Active DTCs (P0238, P204F)
            upper == "04" -> "44" // Clear DTC response
            upper.startsWith("22") -> "62 11 A0 03 F8 12" // UDS ReadDataByIdentifier
            upper.startsWith("2E") -> "6E" // UDS WriteDataByIdentifier Success
            upper.startsWith("31") -> "71 01" // RoutineControl Success
            else -> "41 00 OK"
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
            val num = response.replace("V", "").replace("v", "").trim()
            num.toFloatOrNull() ?: 0f
        } catch (e: Exception) { 0f }
    }
}

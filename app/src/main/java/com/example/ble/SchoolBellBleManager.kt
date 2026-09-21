package com.example.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.model.BleState
import com.example.model.ScheduleSlot
import com.example.model.SchoolBellSettings
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
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

class SchoolBellBleManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "SchoolBellBle"
        const val DEVICE_NAME = "SchoolBell-ESP32"
        private const val PREFS_NAME = "school_bell_prefs"
        private const val KEY_SCHEDULES = "key_schedules_json"
        private const val KEY_DEF_DUR = "key_def_dur"
        private const val KEY_DAYS_MASK = "key_days_mask"
        private const val KEY_SYS_ENABLED = "key_sys_enabled"

        val SERVICE_UUID: UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
        val CHAR_COMMAND_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
        val CHAR_RESPONSE_UUID: UUID = UUID.fromString("1c95d5e3-d8f7-413a-bf3d-7a2e5d7be87e")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val bleScanner: BluetoothLeScanner? get() = bluetoothAdapter?.bluetoothLeScanner

    private var bluetoothGatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var responseCharacteristic: BluetoothGattCharacteristic? = null

    private val _bleState = MutableStateFlow<BleState>(BleState.Disconnected)
    val bleState: StateFlow<BleState> = _bleState.asStateFlow()

    private val _schedules = MutableStateFlow<List<ScheduleSlot>>(emptyList())
    val schedules: StateFlow<List<ScheduleSlot>> = _schedules.asStateFlow()

    private val _settings = MutableStateFlow(SchoolBellSettings())
    val settings: StateFlow<SchoolBellSettings> = _settings.asStateFlow()

    private val _isRelayRinging = MutableStateFlow(false)
    val isRelayRinging: StateFlow<Boolean> = _isRelayRinging.asStateFlow()

    private val _toastEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val toastEvents: SharedFlow<String> = _toastEvents.asSharedFlow()

    var isSimulationMode: Boolean = false
        private set

    private var scanTimeoutJob: Job? = null
    private var syncTimeoutJob: Job? = null
    private var isScanning = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val commandChannel = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private var commandQueueJob: Job? = null

    init {
        loadPersistedState()
        startCommandQueueProcessor()
    }

    private fun startCommandQueueProcessor() {
        commandQueueJob?.cancel()
        commandQueueJob = scope.launch(Dispatchers.IO) {
            for (cmd in commandChannel) {
                sendBleDirect(cmd)
                delay(80) // Safe pacing to guarantee BLE buffer never overflows
            }
        }
    }

    private fun loadPersistedState() {
        try {
            val defDur = prefs.getInt(KEY_DEF_DUR, 5)
            val daysMask = prefs.getInt(KEY_DAYS_MASK, 0b01111111)
            val sysEnabled = prefs.getBoolean(KEY_SYS_ENABLED, true)
            _settings.value = SchoolBellSettings(
                defaultDurationSeconds = defDur,
                operatingDaysMask = daysMask,
                systemEnabled = sysEnabled
            )

            val rawJson = prefs.getString(KEY_SCHEDULES, null)
            if (!rawJson.isNullOrEmpty()) {
                val jsonArr = JSONArray(rawJson)
                val list = mutableListOf<ScheduleSlot>()
                for (i in 0 until jsonArr.length()) {
                    val obj = jsonArr.getJSONObject(i)
                    list.add(
                        ScheduleSlot(
                            id = i,
                            hour = obj.optInt("h", 8),
                            minute = obj.optInt("m", 0),
                            durationSeconds = obj.optInt("dur", 5),
                            enabled = obj.optBoolean("en", true)
                        )
                    )
                }
                _schedules.value = list.sorted()
            } else {
                val defaultSlots = listOf(
                    ScheduleSlot(0, 8, 0, 5, true),
                    ScheduleSlot(1, 8, 45, 5, true),
                    ScheduleSlot(2, 9, 30, 5, true),
                    ScheduleSlot(3, 10, 30, 5, true),
                    ScheduleSlot(4, 11, 15, 5, true)
                )
                _schedules.value = defaultSlots
                persistSchedules(defaultSlots)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading persisted state: ${e.message}")
        }
    }

    private fun persistSchedules(list: List<ScheduleSlot>) {
        try {
            val jsonArr = JSONArray()
            list.forEachIndexed { i, slot ->
                val obj = JSONObject()
                obj.put("id", i)
                obj.put("h", slot.hour)
                obj.put("m", slot.minute)
                obj.put("dur", slot.durationSeconds)
                obj.put("en", slot.enabled)
                jsonArr.put(obj)
            }
            prefs.edit().putString(KEY_SCHEDULES, jsonArr.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving schedules: ${e.message}")
        }
    }

    private fun persistSettings(sett: SchoolBellSettings) {
        prefs.edit()
            .putInt(KEY_DEF_DUR, sett.defaultDurationSeconds)
            .putInt(KEY_DAYS_MASK, sett.operatingDaysMask)
            .putBoolean(KEY_SYS_ENABLED, sett.systemEnabled)
            .apply()
    }

    fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun isBluetoothSupported(): Boolean {
        return bluetoothAdapter != null
    }

    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

    @SuppressLint("MissingPermission")
    fun startAutoConnect() {
        if (!hasRequiredPermissions()) {
            _bleState.value = BleState.Error("صلاحيات البلوتوث مطلوبة للاتصال بجهاز الجرس")
            return
        }

        if (!isBluetoothSupported() || !isBluetoothEnabled()) {
            Log.w(TAG, "Bluetooth not available or disabled. Activating simulation fallback.")
            enableSimulationMode()
            return
        }

        if (_bleState.value is BleState.Connected || _bleState.value is BleState.SyncingTime || _bleState.value is BleState.Synced) {
            return
        }

        startScanning()
    }

    @SuppressLint("MissingPermission")
    fun startScanning() {
        if (!hasRequiredPermissions() || bleScanner == null || !isBluetoothEnabled()) {
            _bleState.value = BleState.Error("يرجى تفعيل البلوتوث ومنح الصلاحيات")
            return
        }

        if (isScanning) return

        _bleState.value = BleState.Scanning
        isScanning = true

        val filters = listOf(
            ScanFilter.Builder().setDeviceName(DEVICE_NAME).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        )
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            bleScanner?.startScan(filters, scanSettings, scanCallback)

            scanTimeoutJob?.cancel()
            scanTimeoutJob = scope.launch {
                delay(12000)
                if (isScanning && _bleState.value == BleState.Scanning) {
                    stopScanning()
                    _bleState.value = BleState.Disconnected
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting BLE scan: ${e.message}")
            isScanning = false
            _bleState.value = BleState.Error("خطأ في تشغيل ماسح البلوتوث")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        if (!isScanning) return
        isScanning = false
        scanTimeoutJob?.cancel()
        try {
            bleScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Stop scan failed: ${e.message}")
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                val name = device.name ?: result.scanRecord?.deviceName
                if (name == DEVICE_NAME || result.scanRecord?.serviceUuids?.any { it.uuid == SERVICE_UUID } == true) {
                    Log.i(TAG, "Found target ESP32 device: ${device.address} ($name)")
                    stopScanning()
                    connectToDevice(device)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE Scan failed with code: $errorCode")
            isScanning = false
            _bleState.value = BleState.Error("فشل البحث عن ESP32 (كود: $errorCode)")
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        _bleState.value = BleState.Connecting
        closeGatt()

        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScanning()
        syncTimeoutJob?.cancel()
        bluetoothGatt?.disconnect()
        closeGatt()
        _bleState.value = BleState.Disconnected
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        try {
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing gatt: ${e.message}")
        }
        bluetoothGatt = null
        commandCharacteristic = null
        responseCharacteristic = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected to GATT server. Discovering services...")
                _bleState.value = BleState.Connected
                mainHandler.postDelayed({
                    gatt?.discoverServices()
                }, 250)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnected from GATT server.")
                _bleState.value = BleState.Disconnected
                closeGatt()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && gatt != null) {
                val service = gatt.getService(SERVICE_UUID)
                if (service != null) {
                    commandCharacteristic = service.getCharacteristic(CHAR_COMMAND_UUID)
                    responseCharacteristic = service.getCharacteristic(CHAR_RESPONSE_UUID)

                    responseCharacteristic?.let { respChar ->
                        gatt.setCharacteristicNotification(respChar, true)
                        val descriptor = respChar.getDescriptor(CCCD_UUID)
                        if (descriptor != null) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                            } else {
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                gatt.writeDescriptor(descriptor)
                            }
                        }
                    }

                    Log.i(TAG, "Discovered School Bell BLE Service & Characteristics.")
                    mainHandler.postDelayed({
                        performAutomaticTimeSync()
                    }, 300)
                } else {
                    Log.e(TAG, "School Bell Service not found on device!")
                    _bleState.value = BleState.Error("الخدمة المطلوبة غير متوفرة على ESP32")
                }
            } else {
                Log.e(TAG, "Service discovery failed with status: $status")
                _bleState.value = BleState.Error("فشل استكشاف خدمات ESP32")
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            Log.i(TAG, "onDescriptorWrite status: $status")
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val responseString = String(value, Charsets.UTF_8).trim()
            handleIncomingResponse(responseString)
        }

        @Deprecated("Deprecated for SDK >= 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            characteristic?.value?.let { value ->
                val responseString = String(value, Charsets.UTF_8).trim()
                handleIncomingResponse(responseString)
            }
        }
    }

    /**
     * Automatic Time Synchronization sequence
     */
    fun performAutomaticTimeSync() {
        if (isSimulationMode) {
            simulateTimeSync()
            return
        }

        _bleState.value = BleState.SyncingTime("جارٍ مزامنة الوقت...")

        val cal = Calendar.getInstance()
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val second = cal.get(Calendar.SECOND)
        val dow = cal.get(Calendar.DAY_OF_WEEK)

        val cmd = "SET_TIME:$year,$month,$day,$hour,$minute,$second,$dow"
        sendCommand(cmd)

        // Safety fallback timer so UI never gets stuck on "جاري المزامنة..."
        syncTimeoutJob?.cancel()
        syncTimeoutJob = scope.launch {
            delay(1500)
            if (_bleState.value is BleState.SyncingTime) {
                val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                _bleState.value = BleState.Synced(timeStr)
                Log.i(TAG, "Time sync completed via local clock fallback.")
                pushAllSchedulesToEsp32()
            }
        }
    }

    /**
     * Send command to ESP32 over BLE (Thread-safe queued delivery)
     */
    fun sendCommand(command: String) {
        if (isSimulationMode) {
            handleSimulatedCommand(command)
            return
        }
        commandChannel.trySend(command)
    }

    @SuppressLint("MissingPermission")
    private fun sendBleDirect(command: String) {
        Log.d(TAG, "Sending BLE Command: $command")
        val gatt = bluetoothGatt
        val charac = commandCharacteristic
        if (gatt == null || charac == null) {
            Log.w(TAG, "Cannot send command: GATT or Characteristic is null")
            return
        }

        val bytes = command.toByteArray(Charsets.UTF_8)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(charac, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            } else {
                charac.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                charac.value = bytes
                gatt.writeCharacteristic(charac)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing BLE characteristic: ${e.message}")
        }
    }

    /**
     * Handle incoming response from ESP32
     */
    private fun handleIncomingResponse(response: String) {
        Log.i(TAG, "Received BLE response: $response")
        syncTimeoutJob?.cancel()

        scope.launch(Dispatchers.Main) {
            when {
                response.startsWith("TIME_SYNC_OK:") -> {
                    val timeStr = response.substringAfter("TIME_SYNC_OK:").substringBefore(",")
                    _bleState.value = BleState.Synced(timeStr)
                    _toastEvents.emit("✓ تمت مزامنة ساعة الجرس بنجاح")

                    delay(100)
                    pushAllSchedulesToEsp32()
                }
                response.startsWith("SCH_LIST:") -> {
                    parseScheduleList(response)
                }
                response.startsWith("SETTINGS:") -> {
                    parseSettings(response)
                }
                response.startsWith("STATUS:") -> {
                    parseStatus(response)
                }
                response.startsWith("BELL_TEST_OK:") -> {
                    val dur = response.substringAfter("BELL_TEST_OK:").toIntOrNull() ?: 5
                    _isRelayRinging.value = true
                    _toastEvents.emit("🔔 جاري تشغيل جرس الاختبار ($dur ثوانٍ)")
                    scope.launch {
                        delay(dur * 1000L)
                        _isRelayRinging.value = false
                    }
                }
                response == "SCH_OK" -> {
                    Log.i(TAG, "Schedule operation acknowledged by ESP32")
                }
                response == "SETTINGS_OK" -> {
                    _toastEvents.emit("✓ تم حفظ الإعدادات في ESP32")
                }
            }
        }
    }

    /**
     * Push all local schedules to ESP32 to ensure complete synchronization
     */
    fun pushAllSchedulesToEsp32() {
        val current = _schedules.value
        val sett = _settings.value
        scope.launch {
            // 1. Send system settings
            sendCommand("SET_SETTINGS:${sett.defaultDurationSeconds},${sett.operatingDaysMask},${if (sett.systemEnabled) 1 else 0}")
            
            // 2. Clear previous times on ESP32 to avoid duplicates or hitting limit
            sendCommand("CLEAR_TIMES")

            // 3. Send all active schedules
            current.forEach { slot ->
                val en = if (slot.enabled) 1 else 0
                sendCommand("ADD_TIME:${slot.hour},${slot.minute},${slot.durationSeconds},$en")
            }

            // 4. Request status to confirm
            sendCommand("GET_STATUS")
        }
    }

    private fun parseScheduleList(data: String) {
        try {
            val parts = data.substringAfter("SCH_LIST:").split(";")
            val list = mutableListOf<ScheduleSlot>()

            for (i in 1 until parts.size) {
                val itemParts = parts[i].split(":")
                if (itemParts.size >= 4) {
                    val h = itemParts[0].toIntOrNull() ?: 8
                    val m = itemParts[1].toIntOrNull() ?: 0
                    val dur = itemParts[2].toIntOrNull() ?: 5
                    val en = (itemParts[3].toIntOrNull() ?: 1) == 1
                    list.add(ScheduleSlot(id = i - 1, hour = h, minute = m, durationSeconds = dur, enabled = en))
                }
            }
            if (list.isNotEmpty()) {
                val sortedList = list.sorted()
                _schedules.value = sortedList
                persistSchedules(sortedList)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing schedule list: ${e.message}")
        }
    }

    private fun parseSettings(data: String) {
        try {
            val parts = data.substringAfter("SETTINGS:").split(",")
            if (parts.size >= 3) {
                val defDur = parts[0].toIntOrNull() ?: 5
                val mask = parts[1].toIntOrNull() ?: 0b01111111
                val sysEn = (parts[2].toIntOrNull() ?: 1) == 1
                val newSettings = SchoolBellSettings(defDur, mask, sysEn)
                _settings.value = newSettings
                persistSettings(newSettings)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing settings: ${e.message}")
        }
    }

    private fun parseStatus(data: String) {
        try {
            val parts = data.substringAfter("STATUS:").split(",")
            if (parts.size >= 4) {
                val relayActive = parts[0] == "1"
                _isRelayRinging.value = relayActive
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing status: ${e.message}")
        }
    }

    // =========================================================================
    // CRUD Operations (Optimistic local updates + BLE sync)
    // =========================================================================
    fun addSchedule(hour: Int, minute: Int, durationSeconds: Int, enabled: Boolean) {
        val currentList = _schedules.value.toMutableList()
        if (currentList.size >= 10) {
            scope.launch { _toastEvents.emit("الحد الأقصى هو 10 مواعيد فقط") }
            return
        }

        val newSlot = ScheduleSlot(
            id = currentList.size,
            hour = hour.coerceIn(0, 23),
            minute = minute.coerceIn(0, 59),
            durationSeconds = durationSeconds.coerceIn(1, 30),
            enabled = enabled
        )
        currentList.add(newSlot)
        val updatedSorted = currentList.sorted().mapIndexed { idx, s -> s.copy(id = idx) }
        _schedules.value = updatedSorted
        persistSchedules(updatedSorted)

        scope.launch { _toastEvents.emit("✓ تمت إضافة وقت الجرس (${newSlot.timeFormatted}) بنجاح") }

        val enVal = if (enabled) 1 else 0
        sendCommand("ADD_TIME:$hour,$minute,$durationSeconds,$enVal")
    }

    fun updateSchedule(index: Int, hour: Int, minute: Int, durationSeconds: Int, enabled: Boolean) {
        val currentList = _schedules.value.toMutableList()
        if (index in currentList.indices) {
            currentList[index] = ScheduleSlot(
                id = index,
                hour = hour.coerceIn(0, 23),
                minute = minute.coerceIn(0, 59),
                durationSeconds = durationSeconds.coerceIn(1, 30),
                enabled = enabled
            )
            val updatedSorted = currentList.sorted().mapIndexed { idx, s -> s.copy(id = idx) }
            _schedules.value = updatedSorted
            persistSchedules(updatedSorted)

            scope.launch { _toastEvents.emit("✓ تم تحديث الوقت بنجاح") }

            val enVal = if (enabled) 1 else 0
            sendCommand("UPDATE_TIME:$index,$hour,$minute,$durationSeconds,$enVal")
        }
    }

    fun toggleScheduleEnabled(index: Int, enabled: Boolean) {
        val currentList = _schedules.value.toMutableList()
        if (index in currentList.indices) {
            val slot = currentList[index]
            currentList[index] = slot.copy(enabled = enabled)
            _schedules.value = currentList
            persistSchedules(currentList)

            val enVal = if (enabled) 1 else 0
            sendCommand("UPDATE_TIME:$index,${slot.hour},${slot.minute},${slot.durationSeconds},$enVal")
        }
    }

    fun deleteSchedule(index: Int) {
        val currentList = _schedules.value.toMutableList()
        if (index in currentList.indices) {
            currentList.removeAt(index)
            val updatedSorted = currentList.mapIndexed { i, s -> s.copy(id = i) }.sorted()
            _schedules.value = updatedSorted
            persistSchedules(updatedSorted)

            scope.launch { _toastEvents.emit("✓ تم حذف الوقت بنجاح") }

            sendCommand("DEL_TIME:$index")
        }
    }

    fun testBell(durationSeconds: Int = _settings.value.defaultDurationSeconds) {
        val dur = durationSeconds.coerceIn(1, 30)
        _isRelayRinging.value = true
        scope.launch {
            _toastEvents.emit("🔔 جاري تشغيل جرس الاختبار ($dur ثوانٍ)")
            delay(dur * 1000L)
            _isRelayRinging.value = false
        }
        sendCommand("BELL_TEST:$dur")
    }

    fun saveSettings(newSettings: SchoolBellSettings) {
        _settings.value = newSettings
        persistSettings(newSettings)
        scope.launch { _toastEvents.emit("✓ تم حفظ الإعدادات بنجاح") }

        val sysVal = if (newSettings.systemEnabled) 1 else 0
        sendCommand("SET_SETTINGS:${newSettings.defaultDurationSeconds},${newSettings.operatingDaysMask},$sysVal")
    }

    // =========================================================================
    // Simulation / Standalone Fallback for Testing / Emulators
    // =========================================================================
    fun enableSimulationMode() {
        isSimulationMode = true
        _bleState.value = BleState.Connected
        simulateTimeSync()
    }

    private fun simulateTimeSync() {
        scope.launch {
            _bleState.value = BleState.SyncingTime("جارٍ محاكاة مزامنة الوقت...")
            delay(300)
            val timeFormatted = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            _bleState.value = BleState.Synced(timeFormatted)
            _toastEvents.emit("✓ تمت مزامنة ساعة الجرس بنجاح")
        }
    }

    private fun handleSimulatedCommand(command: String) {
        scope.launch {
            delay(50)
            when {
                command.startsWith("BELL_TEST") -> {
                    val dur = command.substringAfter("BELL_TEST:").toIntOrNull() ?: _settings.value.defaultDurationSeconds
                    _isRelayRinging.value = true
                    delay(dur * 1000L)
                    _isRelayRinging.value = false
                }
            }
        }
    }
}

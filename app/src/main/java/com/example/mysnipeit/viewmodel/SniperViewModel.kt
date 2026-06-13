package com.example.mysnipeit.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mysnipeit.data.location.DeviceLocationProvider
import com.example.mysnipeit.data.models.*
import com.example.mysnipeit.data.network.WifiBinder
import com.example.mysnipeit.data.repository.SniperRepository
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.util.Log

class SniperViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SniperRepository()

    private val _uiState = MutableStateFlow(SniperUiState())

    val streamReady: StateFlow<Boolean> = repository.streamReady
    val rtspStreamUrl: StateFlow<String?> = repository.rtspStreamUrl


    val uiState: StateFlow<SniperUiState> = _uiState.asStateFlow()

    // --- Theme mode (dark / light) -----------------------------------------
    // Persisted to SharedPreferences so the choice survives app restarts.
    private val prefs = application.getSharedPreferences("snipeit", android.content.Context.MODE_PRIVATE)
    private val _darkTheme = MutableStateFlow(prefs.getBoolean("dark_theme", true))
    val darkTheme: StateFlow<Boolean> = _darkTheme.asStateFlow()
    fun toggleTheme() {
        val next = !_darkTheme.value
        _darkTheme.value = next
        prefs.edit().putBoolean("dark_theme", next).apply()
    }

    // --- Ballistic loadout (cartridge + rifle profile) ----------------------
    // Persisted to SharedPreferences like dark_theme so the operator's pick
    // survives restarts. Unknown/absent ids fall back to the catalog default
    // via cartridgeById/rifleById. The ballistic solver reads these flows.
    private val _selectedCartridge = MutableStateFlow(
        BallisticProfiles.cartridgeById(prefs.getString("cartridge_id", null))
    )
    val selectedCartridge: StateFlow<CartridgeProfile> = _selectedCartridge.asStateFlow()

    private val _selectedRifle = MutableStateFlow(
        BallisticProfiles.rifleById(prefs.getString("rifle_id", null))
    )
    val selectedRifle: StateFlow<RifleProfile> = _selectedRifle.asStateFlow()

    fun selectCartridge(id: String) {
        _selectedCartridge.value = BallisticProfiles.cartridgeById(id)
        prefs.edit().putString("cartridge_id", id).apply()
    }

    fun selectRifle(id: String) {
        _selectedRifle.value = BallisticProfiles.rifleById(id)
        prefs.edit().putString("rifle_id", id).apply()
    }

    // --- Device GPS --------------------------------------------------------
    // Real device location, populated once the runtime location permission
    // has been granted (see MainActivity). Null until then OR while we wait
    // for the first fix. Used by:
    //  - MapScreen for the "Your Location" marker
    //  - (future) Ballistics calculator as one of its inputs
    private val locationProvider = DeviceLocationProvider(application.applicationContext)
    val userLocation: StateFlow<LatLng?> = locationProvider.location

    /** Called by MainActivity after the user grants ACCESS_FINE_LOCATION. */
    fun onLocationPermissionGranted() {
        locationProvider.start()
        Log.d("SniperViewModel", "Location permission granted — provider started")
    }

    /** Called by MainActivity if the user denies the permission. */
    fun onLocationPermissionDenied() {
        Log.w("SniperViewModel", "Location permission denied — userLocation will stay null")
    }

    // All 4 devices
    private val _availableDevices = MutableStateFlow(
        listOf(
            Device(
                id = "device_1",
                name = "Device 1",
                location = "Sector A",
                longitude = 34.437713,
                latitude = 31.467357,
                status = DeviceStatus.INACTIVE,
                batteryLevel = 89,
                ipAddress = "192.168.1.100"
            ),
            Device(
                id = "device_2",
                name = "Device 2",
                location = "Sector B",
                longitude = 34.484580,
                latitude = 31.527528,
                status = DeviceStatus.INACTIVE,
                batteryLevel = 72,
                ipAddress = "192.168.1.101"
            ),
            Device(
                id = "device_3",
                name = "Device 3",
                location = "Sector C",
                longitude = 34.492314,
                latitude = 31.513963,
                status = DeviceStatus.ACTIVE,
                batteryLevel = 95,
                ipAddress = WifiBinder.FALLBACK_GATEWAY  // RPi5 AP gateway (10.42.1.1)
            ),
            Device(
                id = "device_4",
                name = "Device 4",
                location = "Sector D",
                longitude = 34.452488,
                latitude = 31.514924,
                status = DeviceStatus.INACTIVE,
                batteryLevel = 87,
                ipAddress = "192.168.1.104"
            )
        )
    )
    val availableDevices: StateFlow<List<Device>> = _availableDevices

    private val _selectedDevice = MutableStateFlow<Device?>(null)
    val selectedDevice: StateFlow<Device?> = _selectedDevice

    // Data from repository
    val sensorData: StateFlow<SensorData?> = repository.sensorData
    val detectedTargets: StateFlow<List<DetectedTarget>> = repository.detectedTargets
    val shootingSolution: StateFlow<ShootingSolution?> = repository.shootingSolution
    val systemStatus: StateFlow<SystemStatus> = repository.systemStatus

    // --- Sensor latching + history -----------------------------------------
    // When the Pi reports a sub-frame with valid=false, the dashboard would
    // previously snap to "—". Operators asked for stickier behaviour: keep
    // showing the last GOOD reading for a short grace window, then fall back
    // to "—" only if the sensor stays invalid long enough that the cached
    // value is no longer trustworthy.
    //
    // CHANGE THIS to tune how long a stale reading is held before the UI
    // shows "—". Frames typically arrive at 1.5 s in mock mode (one Pi
    // dispatch per cycle in startMockDataGeneration); the real Pi rate is
    // whatever its firmware emits. 5 s ≈ ~3 missed mock frames.
    val sensorLatchTimeoutMs: Long = 5_000L

    private data class Latched<T>(val value: T, val timestamp: Long)
    private var distanceLatch: Latched<DistanceFrame>? = null
    private var tempHumLatch: Latched<TempHumidityFrame>? = null
    private var servoLatch: Latched<ServoFrame>? = null
    private var gpsLatch: Latched<GpsFrame>? = null
    private var compassLatch: Latched<CompassFrame>? = null
    // Wind speed and direction latch INDEPENDENTLY because the Pi has two
    // separate valid flags (one channel can glitch while the other reads).
    private var windSpeedLatch: Latched<Float>? = null
    private var windDirectionLatch: Latched<Float>? = null

    private val _latchedSensorData = MutableStateFlow<SensorData?>(null)
    /**
     * SensorData where each sub-frame is the most recent VALID reading,
     * held for up to [sensorLatchTimeoutMs]. Consumed by the dashboard so a
     * momentary `valid=false` glitch doesn't blank a cell. Diagnostics
     * reads the raw [sensorData] so the operator can still see actual flag
     * state straight from the Pi.
     */
    val latchedSensorData: StateFlow<SensorData?> = _latchedSensorData.asStateFlow()

    private val _sensorHistory = MutableStateFlow<List<SensorData>>(emptyList())
    /**
     * Rolling window of the last [SENSOR_HISTORY_SIZE] raw sensor frames
     * (oldest first). Powers the Diagnostics → LIVE SENSORS panel. Updated
     * only when a NEW frame arrives — not on the periodic timeout sweep.
     */
    val sensorHistory: StateFlow<List<SensorData>> = _sensorHistory.asStateFlow()

    init {
        // Build the latched stream on every new raw frame so the UI gets
        // an immediate update without waiting for the next 500ms tick.
        viewModelScope.launch {
            repository.sensorData.collect { frame ->
                if (frame != null) {
                    val history = _sensorHistory.value + frame
                    _sensorHistory.value = if (history.size > SENSOR_HISTORY_SIZE)
                        history.takeLast(SENSOR_HISTORY_SIZE) else history
                }
                applyLatchAndEmit()
            }
        }
        // Periodic sweep enforces the timeout even when the Pi has gone
        // silent OR keeps re-sending the same valid=false frame.
        viewModelScope.launch {
            while (isActive) {
                delay(500)
                applyLatchAndEmit()
            }
        }
    }

    private fun applyLatchAndEmit() {
        val raw = repository.sensorData.value
        val ddl = raw?.ddlFrame
        val now = System.currentTimeMillis()

        // Refresh each latch from the current frame ----------------------
        ddl?.distance?.let { if (it.valid) distanceLatch = Latched(it, now) }
        ddl?.temperatureHumidity?.let { if (it.valid) tempHumLatch = Latched(it, now) }
        // Servo has no valid flag — when the sub-frame is present, latch it.
        ddl?.servo?.let { servoLatch = Latched(it, now) }
        ddl?.gps?.let { if (it.valid) gpsLatch = Latched(it, now) }
        // Compass only latches when both `valid` AND headingDeg are non-null
        // (matches the rule in compassHeadingDeg() — a missing heading must
        // never be silently substituted as 0° / true north).
        ddl?.compass?.let { if (it.valid && it.headingDeg != null) compassLatch = Latched(it, now) }
        ddl?.wind?.let {
            if (it.speedValid) windSpeedLatch = Latched(it.speedMps, now)
            if (it.directionValid) windDirectionLatch = Latched(it.directionDeg, now)
        }

        // Expire stale latches ------------------------------------------
        if (distanceLatch?.let { now - it.timestamp > sensorLatchTimeoutMs } == true) distanceLatch = null
        if (tempHumLatch?.let { now - it.timestamp > sensorLatchTimeoutMs } == true) tempHumLatch = null
        if (servoLatch?.let { now - it.timestamp > sensorLatchTimeoutMs } == true) servoLatch = null
        if (gpsLatch?.let { now - it.timestamp > sensorLatchTimeoutMs } == true) gpsLatch = null
        if (compassLatch?.let { now - it.timestamp > sensorLatchTimeoutMs } == true) compassLatch = null
        if (windSpeedLatch?.let { now - it.timestamp > sensorLatchTimeoutMs } == true) windSpeedLatch = null
        if (windDirectionLatch?.let { now - it.timestamp > sensorLatchTimeoutMs } == true) windDirectionLatch = null

        // Compose the latched SensorData. Sub-frames carry valid=true when
        // a latched value exists, so the helper extensions in SensorData.kt
        // don't need to change.
        val latchedWind = if (windSpeedLatch != null || windDirectionLatch != null) {
            WindFrame(
                speedValid = windSpeedLatch != null,
                speedMps = windSpeedLatch?.value ?: 0f,
                directionValid = windDirectionLatch != null,
                directionDeg = windDirectionLatch?.value ?: 0f,
            )
        } else null

        _latchedSensorData.value = SensorData(
            type = raw?.type ?: "sensor_data",
            timestamp = raw?.timestamp ?: now,
            ddlFrame = DdlFrame(
                distance = distanceLatch?.value,
                temperatureHumidity = tempHumLatch?.value,
                servo = servoLatch?.value,
                gps = gpsLatch?.value,
                compass = compassLatch?.value,
                wind = latchedWind,
            ),
        )
    }

    private companion object {
        const val SENSOR_HISTORY_SIZE = 10
    }

    fun navigateToDeviceList() {
        _uiState.value = _uiState.value.copy(currentScreen = AppScreen.DEVICE_SELECTION)
    }

    fun navigateToMap() {
        _uiState.value = _uiState.value.copy(currentScreen = AppScreen.MAP)
    }

    fun navigateToHome() {
        _uiState.value = _uiState.value.copy(currentScreen = AppScreen.HOME)
    }

    fun navigateToDiagnostics() {
        _uiState.value = _uiState.value.copy(currentScreen = AppScreen.DIAGNOSTICS)
    }

    fun selectDevice(device: Device) {
        Log.d("SniperViewModel", "selectDevice called for: ${device.name}")
        _selectedDevice.value = device
        val currentScreen = _uiState.value.currentScreen

        _uiState.value = _uiState.value.copy(
            currentScreen = AppScreen.DASHBOARD,
            selectedDeviceId = device.id,
            previousScreen = currentScreen
        )
        connectToDevice(device)
    }

    private fun connectToDevice(device: Device) {
        Log.d("SniperViewModel", "connectToDevice called for: ${device.name}")
        viewModelScope.launch {
            try {
                val ctx = getApplication<Application>().applicationContext
                // 1. Force traffic over WiFi (RPi AP has no internet → Android may otherwise prefer cellular)
                WifiBinder.bindToWifi(ctx)
                // 2. Auto-detect gateway IP; fall back to the device's stored IP
                val detected = WifiBinder.getGatewayIp(ctx)
                val targetIp = if (detected != WifiBinder.FALLBACK_GATEWAY) detected else device.ipAddress
                Log.d("SniperViewModel", "Connecting to RPi at $targetIp (detected=$detected)")
                // 3. Open WS/HTTP via existing path
                repository.connectToSystem(targetIp)
                Log.d("SniperViewModel", "Connection initiated successfully")
            } catch (e: Exception) {
                Log.e("SniperViewModel", "Connection failed: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    connectionError = "Connection failed: ${e.message}"
                )
            }
        }
    }

    fun goBackFromDashboard() {
        //Smart back: go to where we came from
        val targetScreen = when (_uiState.value.previousScreen) {
            AppScreen.MAP -> AppScreen.MAP
            AppScreen.DEVICE_SELECTION -> AppScreen.DEVICE_SELECTION
            else -> AppScreen.DEVICE_SELECTION  // Default fallback
        }

        Log.d("SniperViewModel", "Going back to: $targetScreen")
        _uiState.value = _uiState.value.copy(
            currentScreen = targetScreen,
            previousScreen = null  // Clear previous screen
        )
    }

    fun goBackToHome() {
        Log.d("SniperViewModel", "goBackToHome called")
        _uiState.value = _uiState.value.copy(currentScreen = AppScreen.HOME)
        _selectedDevice.value = null
        repository.disconnectFromSystem()
        WifiBinder.release(getApplication<Application>().applicationContext)
    }

    fun connectToSystem() {
        _selectedDevice.value?.let { device ->
            connectToDevice(device)
        }
    }

    fun disconnectFromSystem() {
        repository.disconnectFromSystem()
        WifiBinder.release(getApplication<Application>().applicationContext)
    }

    override fun onCleared() {
        super.onCleared()
        // Safety net: release the WiFi binding if the VM dies while still bound.
        WifiBinder.release(getApplication<Application>().applicationContext)
        // Stop GPS updates to spare the battery.
        locationProvider.stop()
    }

    // Command methods

    fun requestCalibration() {
        repository.requestCalibration()
    }

    fun setManualTarget(latitude: Double, longitude: Double) {
        repository.setManualTarget(latitude, longitude)
    }

    fun emergencyStop() {
        repository.emergencyStop()
    }

    fun selectTarget(targetId: String) {
        _uiState.value = _uiState.value.copy(selectedTargetId = targetId)
    }

    fun deselectTarget() {
        _uiState.value = _uiState.value.copy(selectedTargetId = null)
    }

    fun lockTarget(targetId: String) {
        repository.sendLockCommand(targetId, isLocking = true)
    }

    fun unlockTarget(targetId: String) {
        repository.sendLockCommand(targetId, isLocking = false)
    }
}

data class SniperUiState(
    val currentScreen: AppScreen = AppScreen.HOME,
    val isScanning: Boolean = false,
    val selectedDeviceId: String? = null,
    val connectionError: String? = null,
    val isVideoFullscreen: Boolean = false,
    val selectedTargetId: String? = null,
    val previousScreen: AppScreen? = null
)

enum class AppScreen {
    HOME,
    DEVICE_SELECTION,
    MAP,
    DASHBOARD,
    DIAGNOSTICS
}
package com.example.mysnipeit.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mysnipeit.data.models.*
import com.example.mysnipeit.data.network.WifiBinder
import com.example.mysnipeit.data.repository.SniperRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
package com.example.mysnipeit.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mysnipeit.data.models.*
import com.example.mysnipeit.data.repository.SniperRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.util.Log

class SniperViewModel : ViewModel() {

    private val repository = SniperRepository()

    private val _uiState = MutableStateFlow(SniperUiState())
    val uiState: StateFlow<SniperUiState> = _uiState.asStateFlow()

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
                ipAddress = "192.168.7.9"
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
        Log.d("SniperViewModel", "connectToDevice called for IP: ${device.ipAddress}")
        viewModelScope.launch {
            try {
                // FIXED: Remove port parameter, only pass IP address
                repository.connectToSystem(device.ipAddress)
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
    }

    fun connectToSystem() {
        _selectedDevice.value?.let { device ->
            connectToDevice(device)
        }
    }

    fun disconnectFromSystem() {
        repository.disconnectFromSystem()
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
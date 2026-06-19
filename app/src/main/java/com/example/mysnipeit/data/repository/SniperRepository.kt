package com.example.mysnipeit.data.repository

import com.example.mysnipeit.data.ballistics.RigGeometry
import com.example.mysnipeit.data.models.*
import com.example.mysnipeit.data.network.RaspberryPiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Which HTTP command the app uses to ask the Pi to slew the camera to
 * an audio bearing. The Pi-side dev gets the final say on the contract;
 * the app is wired for both and switches with a one-line constant change
 * in [SniperRepository.SLEW_COMMAND_MODE].
 *
 *  - [SLEW_TO_BEARING] — app sends one intent (`{azimuth_deg: <world>}`)
 *    and the Pi handles the bearing→servo conversion + autonomous scan.
 *    RECOMMENDED: keeps geometry math on the Pi side where the
 *    autonomous-scan code already lives, and lets the Pi use the
 *    freshest compass reading at the moment of slew.
 *  - [SET_SERVO_ANGLES] — app computes servo H/V degrees from the
 *    current compass + [RigGeometry] and sends them directly. Trivial
 *    Pi-side (`servo.move(h, v)`) but couples app + Pi on the rig
 *    geometry constants, and angles can drift if the rig moves between
 *    send and receive.
 */
enum class SlewCommandMode { SLEW_TO_BEARING, SET_SERVO_ANGLES }

class SniperRepository {

    private val raspberryPiClient = RaspberryPiClient()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Store current IP address for sending commands
    private var currentIpAddress: String? = null

    // Expose data streams from the network client
    val sensorData: StateFlow<SensorData?> = raspberryPiClient.sensorData
    val detectedTargets: StateFlow<List<DetectedTarget>> = raspberryPiClient.detectedTargets
    val shootingSolution: StateFlow<ShootingSolution?> = raspberryPiClient.shootingSolution
    val systemStatus: StateFlow<SystemStatus> = raspberryPiClient.systemStatus
    val acousticEvent: StateFlow<AcousticEvent?> = raspberryPiClient.acousticEvent

    val streamReady: StateFlow<Boolean> = raspberryPiClient.streamReady
    val rtspStreamUrl: StateFlow<String?> = raspberryPiClient.rtspStreamUrl



    suspend fun connectToSystem(ipAddress: String) {
        currentIpAddress = ipAddress
        raspberryPiClient.connect(ipAddress)
    }

    fun disconnectFromSystem() {
        raspberryPiClient.disconnect()
        currentIpAddress = null
    }

    // Commands to send to RPi5 - FIXED to use suspend functions
    fun selectTarget(targetId: String) {
        scope.launch {
            currentIpAddress?.let { ip ->
                raspberryPiClient.sendCommand(
                    ipAddress = ip,
                    command = "select_target",
                    params = mapOf("target_id" to targetId)
                )
            }
        }
    }

    /**
     * Send lock/unlock command via WebSocket
     */
    fun sendLockCommand(targetId: String, isLocking: Boolean) {
        val action = if (isLocking) "lock" else "unlock"
        raspberryPiClient.sendLockCommand(targetId, action)
    }

    fun requestCalibration() {
        scope.launch {
            currentIpAddress?.let { ip ->
                raspberryPiClient.sendCommand(
                    ipAddress = ip,
                    command = "calibrate_system",
                    params = emptyMap()
                )
            }
        }
    }

    fun setManualTarget(latitude: Double, longitude: Double) {
        scope.launch {
            currentIpAddress?.let { ip ->
                raspberryPiClient.sendCommand(
                    ipAddress = ip,
                    command = "set_manual_target",
                    params = mapOf(
                        "latitude" to latitude,
                        "longitude" to longitude
                    )
                )
            }
        }
    }

    fun emergencyStop() {
        scope.launch {
            currentIpAddress?.let { ip ->
                raspberryPiClient.sendCommand(
                    ipAddress = ip,
                    command = "emergency_stop",
                    params = emptyMap()
                )
            }
        }
    }

    // Additional helper methods

    fun getVideoStreamUrl(): String? {
        return currentIpAddress?.let { ip ->
            raspberryPiClient.getVideoStreamUrl(ip)
        }
    }

    fun isConnected(): Boolean {
        return raspberryPiClient.isConnected()
    }

    // --- Forced mock mode (Diagnostics → MOCK MODE) -------------------------
    // Drives the offline ballistic-calculator test path. The viewmodel keeps
    // the mock anchor updated with the operator's own GPS so the mock Pi
    // stays in range of whichever city you're testing from.

    fun setMockAnchor(latDeg: Double?, lonDeg: Double?, altM: Double?) =
        raspberryPiClient.setMockAnchor(latDeg, lonDeg, altM)

    fun startForcedMock() = raspberryPiClient.startForcedMock()

    fun stopForcedMock() = raspberryPiClient.stopForcedMock()

    // --- Acoustic-alert slew -----------------------------------------------
    // Branches on SLEW_COMMAND_MODE so the Pi-side dev's choice (still TBD
    // when this lands) is a one-line change here.

    /**
     * Ask the Pi to slew the camera toward an acoustic contact's world
     * bearing. Called by [com.example.mysnipeit.viewmodel.SniperViewModel.acceptAudioAlert].
     *
     * @param worldBearingDeg true-north bearing of the sound source, 0-360.
     * @param compassHeadingDeg current compass heading — required for the
     *   [SlewCommandMode.SET_SERVO_ANGLES] path to convert the world
     *   bearing into servo coordinates. Ignored in
     *   [SlewCommandMode.SLEW_TO_BEARING] mode. Pass null only if the
     *   compass isn't fixed; the SET_SERVO_ANGLES branch will then no-op
     *   rather than send a garbage servo target.
     */
    fun slewToAudioBearing(worldBearingDeg: Double, compassHeadingDeg: Float?) {
        scope.launch {
            val ip = currentIpAddress ?: return@launch
            when (SLEW_COMMAND_MODE) {
                SlewCommandMode.SLEW_TO_BEARING -> {
                    raspberryPiClient.sendCommand(
                        ipAddress = ip,
                        command = "slew_to_bearing",
                        params = mapOf("azimuth_deg" to worldBearingDeg),
                    )
                }
                SlewCommandMode.SET_SERVO_ANGLES -> {
                    val compass = compassHeadingDeg?.toDouble() ?: return@launch
                    // Relative bearing in [-180, +180] → servo H in [0, 180].
                    val rel = ((worldBearingDeg - compass + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
                    val servoH = (RigGeometry.SERVO_HORIZONTAL_CENTER_DEG + rel)
                        .coerceIn(0.0, 180.0)
                    // We can't infer elevation from a mic-only contact;
                    // default to level (servo vertical at the configured
                    // horizon angle). Pi-side autonomous scan can sweep
                    // from there.
                    val servoV = RigGeometry.SERVO_VERTICAL_LEVEL_DEG
                    raspberryPiClient.sendCommand(
                        ipAddress = ip,
                        command = "set_servo_angles",
                        params = mapOf(
                            "horizontal_deg" to servoH,
                            "vertical_deg" to servoV,
                        ),
                    )
                }
            }
        }
    }

    companion object {
        /**
         * The contract the app uses to ask the Pi to slew. **Flip this
         * one line** to switch between the two options once the Pi-side
         * dev has decided. Default is the recommended option (intent-
         * based, Pi owns the geometry).
         */
        val SLEW_COMMAND_MODE: SlewCommandMode = SlewCommandMode.SLEW_TO_BEARING
    }
}
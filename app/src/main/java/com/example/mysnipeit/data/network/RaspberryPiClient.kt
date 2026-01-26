package com.example.mysnipeit.data.network

import android.util.Log
import com.example.mysnipeit.data.models.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import com.google.gson.Gson
import java.net.HttpURLConnection
import java.net.URL

/**
 * Enhanced Raspberry Pi Client
 * Handles all communication with the RPi5
 */
class RaspberryPiClient {

    companion object {
        private const val TAG = "RaspberryPiClient"
        private const val WEBSOCKET_PORT = 8555
        private const val HTTP_PORT = 8000
        private const val VIDEO_STREAM_PORT = 8554
    }

    private val gson = Gson()
    private var webSocketClient: WebSocketClient? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var currentIpAddress: String? = null


    // Sensor data from RPi
    private val _sensorData = MutableStateFlow<SensorData?>(null)
    val sensorData: StateFlow<SensorData?> = _sensorData.asStateFlow()

    private val _streamReady = MutableStateFlow(false)
    val streamReady: StateFlow<Boolean> = _streamReady.asStateFlow()

    private val _rtspStreamUrl = MutableStateFlow<String?>(null)
    val rtspStreamUrl: StateFlow<String?> = _rtspStreamUrl.asStateFlow()

    // Detected targets from RPi
    private val _detectedTargets = MutableStateFlow<List<DetectedTarget>>(emptyList())
    val detectedTargets: StateFlow<List<DetectedTarget>> = _detectedTargets.asStateFlow()

    // Shooting solution from RPi
    private val _shootingSolution = MutableStateFlow<ShootingSolution?>(null)
    val shootingSolution: StateFlow<ShootingSolution?> = _shootingSolution.asStateFlow()

    // System status - FIXED to match your model exactly
    private val _systemStatus = MutableStateFlow(
        SystemStatus(
            connectionStatus = ConnectionState.DISCONNECTED,
            batteryLevel = null,
            cameraStatus = false,
            gpsStatus = false,
            rangefinderStatus = false,
            microphoneStatus = false,
            lastHeartbeat = System.currentTimeMillis() ,
            cpuTemperature = null,
            gpsLatitude = null,
            gpsLongitude = null
        )
    )
    val systemStatus: StateFlow<SystemStatus> = _systemStatus.asStateFlow()

    // Mock data generator
    private var mockDataJob: Job? = null

    /**
     * Connect to Raspberry Pi
     */
    suspend fun connect(ipAddress: String) = withContext(Dispatchers.IO) {
        try {
            currentIpAddress = ipAddress
            Log.d(TAG, "Attempting to connect to RPi at $ipAddress")

//            val networkTester = NetworkTester()
//            val canPing = networkTester.pingDevice(ipAddress)
//
  //          if (!canPing) {
  //              Log.e(TAG, "Cannot ping device at $ipAddress")
//                startMockDataGeneration()
//                return@withContext
//            }
//
//            Log.d(TAG, "Device is reachable")
//
 //           val portStatus = networkTester.scanPorts(
 //               ipAddress,
 //               listOf(WEBSOCKET_PORT, HTTP_PORT, VIDEO_STREAM_PORT)
 //           )
//
//            Log.d(TAG, "Port scan results: $portStatus")
//
//            if (portStatus[WEBSOCKET_PORT] == true) {
//                connectWebSocket(ipAddress)
//            } else {
//                Log.w(TAG, "WebSocket port not open, using mock data")
//                startMockDataGeneration()
//            }
//
//            if (portStatus[HTTP_PORT] == true) {
//                testHttpApi(ipAddress)
//            }
            Log.d(TAG, "Attempting direct WebSocket connection...")

            try {
                connectWebSocket(ipAddress)
                Log.d(TAG, "Successfully initiated WebSocket connection")
            } catch (e: Exception) {
                Log.e(TAG, "WebSocket connection failed: ${e.message}")
                Log.w(TAG, "Falling back to mock data")
                startMockDataGeneration()
            }

            Log.d(TAG, " Successfully connected to RPi")

        } catch (e: Exception) {
            Log.e(TAG, " Connection failed: ${e.message}", e)
            Log.d(TAG, " Falling back to mock data generation")
            startMockDataGeneration()
        }
    }

    private fun connectWebSocket(ipAddress: String) {
        val wsUri = URI("ws://$ipAddress:$WEBSOCKET_PORT")

        webSocketClient = object : WebSocketClient(wsUri) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                Log.d(TAG, "WebSocket connected")
                updateSystemStatus(ConnectionState.CONNECTED)
            }

            override fun onMessage(message: String?) {
                message?.let { handleWebSocketMessage(it) }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.d(TAG, "WebSocket closed: $reason")
                updateSystemStatus(ConnectionState.DISCONNECTED)
                _streamReady.value = false
                _rtspStreamUrl.value = null
            }

            override fun onError(ex: Exception?) {
                Log.e(TAG, "WebSocket error: ${ex?.message}")
                updateSystemStatus(ConnectionState.ERROR)
            }
        }

        webSocketClient?.connect()
    }

    private fun handleWebSocketMessage(message: String) {
        try {
            Log.d(TAG, " Received: $message")

            //val dataType = gson.fromJson(message, Map::class.java)["type"] as? String
            val messageMap = gson.fromJson(message, Map::class.java)
            val dataType = messageMap["type"] as? String ?: messageMap["event"] as? String

            when (dataType) {
                "sensor_data" -> {
                    val data = gson.fromJson(message, SensorData::class.java)
                    _sensorData.value = data
                }
                "target_detection" -> {
                    // Parse RPi5 format with detections array
                    val detectionData = gson.fromJson(message, Map::class.java)
                    val timestampMs = (detectionData["timestamp_ms"] as? Double)?.toLong() ?: System.currentTimeMillis()
                    val detectionsArray = detectionData["detections"] as? List<Map<String, Any>>

                    if (detectionsArray != null) {
                        val targets = detectionsArray.mapNotNull { detection ->
                            try {
                                val bboxMap = detection["bbox"] as? Map<String, Any>
                                if (bboxMap != null) {
                                    DetectedTarget(
                                        id = detection["id"] as? String ?: "UNKNOWN",
                                        targetType = detection["class"] as? String ?: "UNKNOWN",
                                        confidence = (detection["confidence"] as? Double)?.toFloat() ?: 0f,
                                        bbox = BoundingBox(
                                            x = (bboxMap["x"] as? Double)?.toInt() ?: 0,
                                            y = (bboxMap["y"] as? Double)?.toInt() ?: 0,
                                            width = (bboxMap["width"] as? Double)?.toInt() ?: 0,
                                            height = (bboxMap["height"] as? Double)?.toInt() ?: 0
                                        ),
                                        timestamp = timestampMs
                                    )
                                } else null
                            } catch (e: Exception) {
                                Log.e(TAG, "Error parsing detection: ${e.message}")
                                null
                            }
                        }
                        _detectedTargets.value = targets
                        Log.d(TAG, "Parsed ${targets.size} targets from RPi5 at timestamp $timestampMs")
                    }
                }
                "shooting_solution" -> {
                    val solutionMap = gson.fromJson(message, Map::class.java)
                    val solution = ShootingSolution(
                        targetId = solutionMap["targetId"] as? String ?: "",
                        azimuth = solutionMap["azimuth"] as? Double ?: 0.0,
                        elevation = solutionMap["elevation"] as? Double ?: 0.0,
                        windageAdjustment = solutionMap["windageAdjustment"] as? Double ?: 0.0,
                        elevationAdjustment = solutionMap["elevationAdjustment"] as? Double ?: 0.0,
                        confidence = (solutionMap["confidence"] as? Double)?.toFloat() ?: 0f,
                        timestamp = (solutionMap["timestamp"] as? Double)?.toLong() ?: System.currentTimeMillis()
                    )
                    _shootingSolution.value = solution
                    Log.d(TAG, "Received shooting solution for ${solution.targetId}")
                }
                "stream_ready" -> {
                    val streamData = gson.fromJson(message, Map::class.java)
                    val rtspPort = (streamData["rtsp_port"] as? Double)?.toInt() ?: 8554
                    val streamName = streamData["stream_name"] as? String ?: "stream"

                    // Extract IP from WebSocket connection (same IP as WebSocket)
                    val ipAddress = currentIpAddress  // You'll need to store this
                    val rtspUrl = "rtsp://$ipAddress:$rtspPort/$streamName"

                    Log.d(TAG, "Stream ready at: $rtspUrl")
                    _rtspStreamUrl.value = rtspUrl
                    _streamReady.value = true
                }
                "system_status" -> {
                    val status = gson.fromJson(message, SystemStatus::class.java)
                    _systemStatus.value = status
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message: ${e.message}", e)
        }
    }

    private fun updateSystemStatus(connectionState: ConnectionState) {
        _systemStatus.value = _systemStatus.value.copy(
            connectionStatus = connectionState,
            lastHeartbeat = System.currentTimeMillis()
        )
    }

    private suspend fun testHttpApi(ipAddress: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("http://$ipAddress:$HTTP_PORT/api/status")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                val responseCode = connection.responseCode
                val isSuccess = responseCode == 200

                if (isSuccess) {
                    val response = connection.inputStream.bufferedReader().readText()
                    Log.d(TAG, " HTTP API response: $response")
                } else {
                    Log.w(TAG, " HTTP API returned code: $responseCode")
                }

                connection.disconnect()
                isSuccess
            } catch (e: Exception) {
                Log.e(TAG, " HTTP API test failed: ${e.message}")
                false
            }
        }
    }

    suspend fun sendCommand(ipAddress: String, command: String, params: Map<String, Any> = emptyMap()): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("http://$ipAddress:$HTTP_PORT/api/command")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                val jsonCommand = gson.toJson(mapOf("command" to command, "params" to params))
                connection.outputStream.write(jsonCommand.toByteArray())

                val responseCode = connection.responseCode
                val isSuccess = responseCode == 200

                Log.d(TAG, if (isSuccess) " Command sent: $command" else " Command failed: $command")

                connection.disconnect()
                isSuccess
            } catch (e: Exception) {
                Log.e(TAG, " Failed to send command: ${e.message}")
                false
            }
        }
    }

    fun getVideoStreamUrl(ipAddress: String): String {
        return "rtsp://$ipAddress:$VIDEO_STREAM_PORT/stream"
    }

    /**
     * Send lock/unlock command via WebSocket
     */
    fun sendLockCommand(targetId: String, action: String) {
        try {
            val command = mapOf(
                "type" to "command",
                "command" to "select_target",
                "params" to mapOf(
                    "targetId" to targetId,
                    "action" to action
                ),
                "timestamp" to System.currentTimeMillis()
            )
            val jsonCommand = gson.toJson(command)
            webSocketClient?.send(jsonCommand)
            Log.d(TAG, "Sent $action command for target $targetId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send lock command: ${e.message}")
        }
    }

    /**
     * Start mock data generation - FIXED with Boolean values
     */
    private fun startMockDataGeneration() {
        Log.d(TAG, " Starting mock data generation")

        mockDataJob?.cancel()
        mockDataJob = scope.launch {
            while (isActive) {
                // 1. Generate mock sensor data
                _sensorData.value = SensorData(
                    temperature = 20.0 + (Math.random() * 10),
                    humidity = 50.0 + (Math.random() * 20),
                    windSpeed = 5.0 + (Math.random() * 10),
                    windDirection = (Math.random() * 360).toInt().toDouble(),
                    rangefinderDistance = 400.0 + (Math.random() * 200),
                    gpsLatitude = 35.0 + (Math.random() * 10),
                    gpsLongitude = 32.0 + (Math.random() * 10),
                    timestamp = System.currentTimeMillis()
                )

                //  2. GENERATE MOCK TARGETS with bbox in pixels
                val mockTargets = listOf(
                    DetectedTarget(
                        id = "T1",
                        targetType = "HUMAN",
                        confidence = 0.85f,
                        bbox = BoundingBox(
                            x = 576,      // ~30% of 1920
                            y = 432,      // ~40% of 1080
                            width = 150,
                            height = 250
                        )
                    ),
                    DetectedTarget(
                        id = "T2",
                        targetType = "VEHICLE",
                        confidence = 0.72f,
                        bbox = BoundingBox(
                            x = 1344,     // ~70% of 1920
                            y = 540,      // ~50% of 1080
                            width = 200,
                            height = 180
                        )
                    )
                )

                _detectedTargets.value = mockTargets
                Log.d(TAG, " Generated ${mockTargets.size} targets")

                // 3. Generate shooting solution for random target
                if (mockTargets.isNotEmpty()) {
                    val randomTarget = mockTargets.random()

                    _shootingSolution.value = ShootingSolution(
                        targetId = randomTarget.id,
                        azimuth = 245.0 + (Math.random() * 10),
                        elevation = 12.0 + (Math.random() * 5),
                        windageAdjustment = 2.0 + (Math.random() * 2),
                        elevationAdjustment = 1.5 + (Math.random() * 1),
                        confidence = 0.75f + (Math.random() * 0.2).toFloat(),
                        timestamp = System.currentTimeMillis()
                    )

                    Log.d(TAG, " Generated solution for ${randomTarget.id}: AZ=${_shootingSolution.value?.azimuth?.toInt()}° EL=${_shootingSolution.value?.elevation?.toInt()}°")
                }

                // 4. Update system status
                _systemStatus.value = SystemStatus(
                    connectionStatus = ConnectionState.CONNECTED,
                    batteryLevel = 85,
                    cameraStatus = true,
                    gpsStatus = true,
                    rangefinderStatus = true,
                    microphoneStatus = true,
                    lastHeartbeat = System.currentTimeMillis()
                )

                delay(1500) // Update every 1.5 seconds
            }
        }
    }

    fun disconnect() {
        Log.d(TAG, "🔌 Disconnecting from RPi")

        webSocketClient?.close()
        webSocketClient = null

        mockDataJob?.cancel()
        mockDataJob = null

        _systemStatus.value = _systemStatus.value.copy(
            connectionStatus = ConnectionState.DISCONNECTED
        )
        _sensorData.value = null
        _detectedTargets.value = emptyList()
        _shootingSolution.value = null
    }

    fun isConnected(): Boolean {
        return _systemStatus.value.connectionStatus == ConnectionState.CONNECTED
    }
}

/**
 * Expected JSON format from RPi5:
 *
 * Sensor Data:
 * {
 *   "type": "sensor_data",
 *   "temperature": 25.5,
 *   "humidity": 65.0,
 *   "windSpeed": 8.2,
 *   "windDirection": 245,
 *   "rangefinderDistance": 420.5,
 *   "timestamp": 1234567890
 * }
 *
 * Target Detection:
 * {
 *   "type": "target_detection",
 *   "timestamp_ms": 5000,
 *   "detections": [
 *     {
 *       "id": "1",
 *       "class": "HUMAN",
 *       "confidence": 0.85,
 *       "bbox": {
 *         "x": 100,
 *         "y": 50,
 *         "width": 200,
 *         "height": 400
 *       }
 *     }
 *   ]
 * }
 *
 * Shooting Solution:
 * {
 *   "type": "shooting_solution",
 *   "targetId": "1",
 *   "azimuth": 245.5,
 *   "elevation": 12.3,
 *   "windageAdjustment": 2.1,
 *   "elevationAdjustment": 1.5,
 *   "confidence": 0.85,
 *   "timestamp": 1234567890
 * }
 */
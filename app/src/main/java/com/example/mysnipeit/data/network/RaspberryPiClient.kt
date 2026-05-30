package com.example.mysnipeit.data.network

import android.util.Log
import com.example.mysnipeit.data.models.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
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

        // Detection pacing — smooth WS bursts to a max output rate
        private const val PACER_MIN_OUTPUT_INTERVAL_MS = 100L

        // App-side IoU tracker — gives each visible target a stable visual id
        // that persists across frames even when the Pi reassigns its own ids
        // (the Pi's Python script labels detections by confidence rank, so its
        // "1" can swap between two people frame-to-frame).
        // - IOU_MATCH_THRESHOLD: minimum overlap to consider an incoming bbox
        //   the "same" target as an already-tracked one.
        // - TRACKED_TARGET_TIMEOUT_MS: how long a tracked target is kept alive
        //   without a fresh match (replaces the old grace-period mechanism).
        private const val IOU_MATCH_THRESHOLD = 0.3f
        private const val TRACKED_TARGET_TIMEOUT_MS = 600L

        // EMA smoothing on matched bboxes. alpha = weight of the new sample.
        // Higher = more responsive, less smoothing. 0.7 keeps a noticeable
        // jitter reduction without making the bbox feel laggy.
        private const val EMA_ALPHA = 0.7f

        // Stale detection auto-clear — drop bboxes if Pi stops sending them.
        // Tuned to 5s so brief silences between bursty Pi deliveries don't
        // wipe the overlay; still short enough to clear if the detector dies.
        private const val STALENESS_CHECK_INTERVAL_MS = 500L
        private const val STALENESS_TIMEOUT_MS = 5000L

        // WS keepalive — prevent NAT/router idle timeouts
        private const val KEEPALIVE_INTERVAL_MS = 20_000L
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

    // --- Detection pacing (A) -----------------------------------------------
    // Bursts on the WS (e.g. Pi sending 130 detections in 300ms) get smoothed
    // by a single consumer coroutine that drains to the most-recent item and
    // throttles output to a sane refresh rate.
    private val detectionQueue =
        Channel<Pair<List<DetectedTarget>, Long>>(Channel.UNLIMITED)
    private var detectionPacerJob: Job? = null

    // --- App-side IoU tracker state ------------------------------------------
    // Owned and mutated only by the pacer coroutine, so no external lock needed.
    private data class TrackedTarget(
        val visualId: String,        // stable id we assign (T1, T2, ...)
        var bbox: BoundingBox,       // EMA-smoothed bbox
        var confidence: Float,       // EMA-smoothed confidence
        var targetType: String,
        var lastSeenAt: Long
    )
    private val trackedTargets = LinkedHashMap<String, TrackedTarget>()
    private var nextVisualIdCounter = 1

    // --- Staleness watchdog (C) ---------------------------------------------
    // If the Pi stops sending detections (e.g. detector died, video ended,
    // network dropped silently), wipe stale bboxes after STALENESS_TIMEOUT_MS
    // so the user doesn't see a frozen overlay.
    @Volatile private var lastWsDetectionAt: Long = 0L
    private var stalenessJob: Job? = null

    // --- WS keepalive (B) ---------------------------------------------------
    private var keepaliveJob: Job? = null

    init {
        startDetectionPacer()
        startStalenessChecker()
    }

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
                startKeepalive()
            }

            override fun onMessage(message: String?) {
                message?.let { handleWebSocketMessage(it) }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.d(TAG, "WebSocket closed: $reason")
                stopKeepalive()
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
                                    val bbox = BoundingBox(
                                        x = (bboxMap["x"] as? Double)?.toInt() ?: 0,
                                        y = (bboxMap["y"] as? Double)?.toInt() ?: 0,
                                        width = (bboxMap["width"] as? Double)?.toInt() ?: 0,
                                        height = (bboxMap["height"] as? Double)?.toInt() ?: 0
                                    )
                                    val id = detection["id"] as? String ?: "UNKNOWN"
                                    val cls = detection["class"] as? String ?: "UNKNOWN"
                                    val conf = (detection["confidence"] as? Double)?.toFloat() ?: 0f
                                    Log.d(TAG, "  detection id=$id class=$cls conf=$conf " +
                                            "bbox(x=${bbox.x}, y=${bbox.y}, w=${bbox.width}, h=${bbox.height})")
                                    DetectedTarget(
                                        id = id,
                                        targetType = cls,
                                        confidence = conf,
                                        bbox = bbox,
                                        timestamp = timestampMs
                                    )
                                } else null
                            } catch (e: Exception) {
                                Log.e(TAG, "Error parsing detection: ${e.message}")
                                null
                            }
                        }
                        // Hand off to the pacer instead of setting state directly.
                        // Bursts (e.g. 130 detections in 300ms) get coalesced to
                        // the most-recent value and emitted at most every
                        // PACER_MIN_OUTPUT_INTERVAL_MS.
                        detectionQueue.trySend(targets to timestampMs)
                        lastWsDetectionAt = System.currentTimeMillis()
                        Log.d(TAG, "Queued ${targets.size} targets from RPi5 at timestamp $timestampMs")
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
                // 1. Generate mock sensor data — nested structure mirroring
                //    the Pi's new ddl_frame format. Servo is omitted (it's
                //    not displayed on the dashboard and only matters once the
                //    ballistics calc is wired up).
                _sensorData.value = SensorData(
                    type = "sensor_data",
                    timestamp = System.currentTimeMillis(),
                    ddlFrame = DdlFrame(
                        distance = DistanceFrame(
                            valid = true,
                            distanceM = (400f + Math.random().toFloat() * 200f),
                            status = 0,
                            precision = 1,
                            strength = 1200,
                        ),
                        temperatureHumidity = TempHumidityFrame(
                            valid = true,
                            temperatureC = (20f + Math.random().toFloat() * 10f),
                            humidityPct = (50f + Math.random().toFloat() * 20f),
                        ),
                        servo = null,
                        gps = GpsFrame(
                            valid = true,
                            fixType = 3,
                            numSatellites = 9,
                            latitudeDeg = 31.51 + Math.random() * 0.02,
                            longitudeDeg = 34.52 + Math.random() * 0.02,
                            altitudeM = 35.0,
                            hAccM = 1.4,
                        ),
                    ),
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

        stopKeepalive()

        webSocketClient?.close()
        webSocketClient = null

        mockDataJob?.cancel()
        mockDataJob = null

        // Drain any pending detections from the pacer queue and reset
        // staleness tracking so the next connection starts fresh.
        while (detectionQueue.tryReceive().isSuccess) { /* drop */ }
        lastWsDetectionAt = 0L

        // Reset the IoU tracker so the next session starts with no stale
        // tracked targets and fresh visual ids (T1, T2, ...).
        trackedTargets.clear()
        nextVisualIdCounter = 1

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

    // ------------------------------------------------------------------------
    // (A) Detection pacer + IoU tracker + EMA smoother
    // Reads from detectionQueue, drains to the latest available item (so a
    // burst of 130 messages becomes 1 emission of the freshest data), then
    // runs the new detections through the tracker so visual identity is
    // stable across Pi-side id swaps and ML jitter is smoothed via EMA.
    // ------------------------------------------------------------------------
    private fun startDetectionPacer() {
        detectionPacerJob?.cancel()
        detectionPacerJob = scope.launch {
            var lastEmitAt = 0L
            while (isActive) {
                // Suspend until at least one detection arrives
                var latest: Pair<List<DetectedTarget>, Long> = detectionQueue.receive()
                var dropped = 0

                // Drain everything else queued behind it; keep only the newest.
                // This is what smooths burst arrivals — when the Pi dumps 130
                // messages in 300ms we render the most recent one once, not 130
                // times in succession.
                while (true) {
                    val r = detectionQueue.tryReceive()
                    if (r.isSuccess) {
                        latest = r.getOrThrow()
                        dropped++
                    } else break
                }
                if (dropped > 0) {
                    Log.d(TAG, "Pacer coalesced $dropped older detections; emitting ts=${latest.second}")
                }

                // Enforce minimum gap between emissions
                val sinceLast = System.currentTimeMillis() - lastEmitAt
                if (sinceLast < PACER_MIN_OUTPUT_INTERVAL_MS) {
                    delay(PACER_MIN_OUTPUT_INTERVAL_MS - sinceLast)
                }

                // Run the latest detection batch through the IoU tracker.
                // Tracked targets persist for TRACKED_TARGET_TIMEOUT_MS after
                // their last match, replacing the old per-pacer grace period.
                val tracked = matchAndSmooth(latest.first)
                _detectedTargets.value = tracked
                lastEmitAt = System.currentTimeMillis()
            }
        }
    }

    // ------------------------------------------------------------------------
    // IoU tracker / EMA smoother helpers
    // ------------------------------------------------------------------------

    /** Intersection-over-union of two pixel-coord bboxes. */
    private fun computeIoU(a: BoundingBox, b: BoundingBox): Float {
        val x1 = maxOf(a.x, b.x)
        val y1 = maxOf(a.y, b.y)
        val x2 = minOf(a.x + a.width,  b.x + b.width)
        val y2 = minOf(a.y + a.height, b.y + b.height)
        val intersection = maxOf(0, x2 - x1) * maxOf(0, y2 - y1)
        val union = a.width * a.height + b.width * b.height - intersection
        return if (union > 0) intersection.toFloat() / union.toFloat() else 0f
    }

    private fun emaInt(old: Int, new: Int, alpha: Float): Int =
        (alpha * new + (1f - alpha) * old).toInt()

    private fun smoothBbox(old: BoundingBox, new: BoundingBox): BoundingBox =
        BoundingBox(
            x      = emaInt(old.x,      new.x,      EMA_ALPHA),
            y      = emaInt(old.y,      new.y,      EMA_ALPHA),
            width  = emaInt(old.width,  new.width,  EMA_ALPHA),
            height = emaInt(old.height, new.height, EMA_ALPHA)
        )

    /**
     * Match incoming raw detections against currently-tracked targets by IoU
     * (greedy, highest-overlap first). Matched targets get EMA-smoothed
     * positions and a refreshed lastSeenAt. Unmatched incoming detections
     * become new tracked targets with a fresh visual id (T1, T2, ...).
     * Tracked targets that haven't been matched within
     * [TRACKED_TARGET_TIMEOUT_MS] are removed.
     *
     * Returned list = the current tracked-target snapshot, ready for UI.
     */
    private fun matchAndSmooth(incoming: List<DetectedTarget>): List<DetectedTarget> {
        val now = System.currentTimeMillis()

        // Build all candidate (trackedId, detectionIndex, IoU) pairs that meet
        // the threshold, then assign greedily by descending IoU.
        val candidates = mutableListOf<Triple<String, Int, Float>>()
        for (track in trackedTargets.values) {
            for ((idx, det) in incoming.withIndex()) {
                val iou = computeIoU(track.bbox, det.bbox)
                if (iou >= IOU_MATCH_THRESHOLD) {
                    candidates.add(Triple(track.visualId, idx, iou))
                }
            }
        }
        candidates.sortByDescending { it.third }

        val claimedTrackIds = HashSet<String>()
        val claimedDetIdxs  = HashSet<Int>()

        for ((trackId, detIdx, _) in candidates) {
            if (trackId in claimedTrackIds || detIdx in claimedDetIdxs) continue
            val track = trackedTargets[trackId] ?: continue
            val det   = incoming[detIdx]

            track.bbox       = smoothBbox(track.bbox, det.bbox)
            track.confidence = EMA_ALPHA * det.confidence + (1f - EMA_ALPHA) * track.confidence
            track.targetType = det.targetType
            track.lastSeenAt = now

            claimedTrackIds += trackId
            claimedDetIdxs  += detIdx
        }

        // Unmatched incoming detections become new tracked targets.
        for ((idx, det) in incoming.withIndex()) {
            if (idx in claimedDetIdxs) continue
            val visualId = "T${nextVisualIdCounter++}"
            trackedTargets[visualId] = TrackedTarget(
                visualId    = visualId,
                bbox        = det.bbox,
                confidence  = det.confidence,
                targetType  = det.targetType,
                lastSeenAt  = now
            )
        }

        // Drop tracked targets that haven't been refreshed in the timeout window.
        val toRemove = trackedTargets.filterValues {
            now - it.lastSeenAt > TRACKED_TARGET_TIMEOUT_MS
        }.keys
        toRemove.forEach { trackedTargets.remove(it) }

        return trackedTargets.values.map { track ->
            DetectedTarget(
                id          = track.visualId,
                targetType  = track.targetType,
                confidence  = track.confidence,
                bbox        = track.bbox,
                timestamp   = now
            )
        }
    }

    // ------------------------------------------------------------------------
    // (C) Stale detection clear
    // If no WS detection has been received for STALENESS_TIMEOUT_MS, wipe the
    // current bbox overlay so the user doesn't stare at a frozen box from a
    // long-dead detection. Resets itself after clearing so it doesn't fight
    // the mock-data fallback (which sets _detectedTargets directly without
    // touching lastWsDetectionAt).
    // ------------------------------------------------------------------------
    private fun startStalenessChecker() {
        stalenessJob?.cancel()
        stalenessJob = scope.launch {
            while (isActive) {
                delay(STALENESS_CHECK_INTERVAL_MS)
                val last = lastWsDetectionAt
                if (last > 0 && System.currentTimeMillis() - last > STALENESS_TIMEOUT_MS) {
                    if (_detectedTargets.value.isNotEmpty()) {
                        Log.d(TAG, "No WS detections for >${STALENESS_TIMEOUT_MS}ms — clearing stale bboxes")
                        _detectedTargets.value = emptyList()
                    }
                    // Reset so we don't re-clear every tick
                    lastWsDetectionAt = 0L
                }
            }
        }
    }

    // ------------------------------------------------------------------------
    // (B) WS keepalive ping
    // Sends a small JSON ping every KEEPALIVE_INTERVAL_MS to prevent NAT or
    // router idle timeouts from killing the WS connection. Unknown message
    // types fall through silently on the C server, so no Pi change is needed.
    // ------------------------------------------------------------------------
    private fun startKeepalive() {
        stopKeepalive()
        keepaliveJob = scope.launch {
            while (isActive) {
                delay(KEEPALIVE_INTERVAL_MS)
                try {
                    val ping = mapOf(
                        "type" to "ping",
                        "timestamp" to System.currentTimeMillis()
                    )
                    webSocketClient?.send(gson.toJson(ping))
                    Log.d(TAG, "Sent WS keepalive ping")
                } catch (e: Exception) {
                    Log.w(TAG, "Keepalive ping failed: ${e.message}")
                    break
                }
            }
        }
    }

    private fun stopKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = null
    }
}

/**
 * Expected JSON format from RPi5:
 *
 * Sensor Data — nested ddl_frame format (mirrors the Pi-side C structs):
 * {
 *   "type": "sensor_data",
 *   "timestamp": 1748600000000,
 *   "ddl_frame": {
 *     "distance":             { "valid": true, "distance_m": 420.5, "status": 0, "precision": 1, "strength": 1240 },
 *     "temperature_humidity": { "valid": true, "temperature_c": 23.5, "humidity_pct": 47.1 },
 *     "servo":                { "horizontal_deg": 87.3, "vertical_deg": 12.4 },
 *     "gps":                  { "valid": true, "fix_type": 3, "num_satellites": 9,
 *                                "latitude_deg": 32.07, "longitude_deg": 34.78,
 *                                "altitude_m": 35.2, "h_acc_m": 1.4 }
 *   }
 * }
 *
 * Display rule on the app side: each sub-frame's `valid` flag gates whether
 * the dashboard renders the values. ServoFrame has no `valid` and is treated
 * as always valid when the sub-frame is present. See [SensorData] for the
 * exact Kotlin shape and the helper extensions used by the dashboard.
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
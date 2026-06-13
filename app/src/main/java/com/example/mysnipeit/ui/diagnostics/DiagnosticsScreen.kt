package com.example.mysnipeit.ui.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mysnipeit.data.models.SensorData
import com.example.mysnipeit.data.network.NetworkTester
import com.example.mysnipeit.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Diagnostics screen — redesigned to Option A from the design bundle:
 * left rail nav + CONNECTIVITY TEST panel with PING + PORT SCAN cards.
 *
 * The [DiagnosticsViewModel] underneath is unchanged — same NetworkTester
 * calls, same DiagnosticsState data model, same TestStatus enum. Only the
 * UI was rewritten.
 */
/**
 * Which left-rail section the diagnostics screen is currently showing.
 *  - CONNECTIVITY: ping + port scan + recommendations (original behaviour)
 *  - LIVE_SENSORS: raw sensor stream (every sub-frame, valid flags as-is,
 *                  plus a rolling history) — replaces the old TELEMETRY
 *                  placeholder that never had an implementation.
 */
private enum class DiagSection { CONNECTIVITY, LIVE_SENSORS }

@Composable
fun DiagnosticsScreen(
    onBackClick: () -> Unit,
    sensorData: SensorData?,
    sensorHistory: List<SensorData>,
    isDarkTheme: Boolean = true,
    onToggleTheme: () -> Unit = {},
    viewModel: DiagnosticsViewModel = viewModel(),
) {
    val t = LocalTactical.current
    val state by viewModel.diagnosticsState.collectAsState()
    val scope = rememberCoroutineScope()
    var section by remember { mutableStateOf(DiagSection.CONNECTIVITY) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(t.base),
    ) {
        TopBar(
            device = "DIAGNOSTICS",
            onBackClick = onBackClick,
        ) {
            ThemeToggle(isDark = isDarkTheme, onToggle = onToggleTheme)
        }
        Row(modifier = Modifier.fillMaxSize()) {
            DiagSidebar(
                active = section,
                onSelect = { section = it },
            )
            when (section) {
                DiagSection.CONNECTIVITY -> DiagMainPane(
                    state = state,
                    onIpChange = viewModel::updateIpAddress,
                    onRunAll = { scope.launch { viewModel.runFullDiagnostics() } },
                    onQuickPing = { scope.launch { viewModel.quickPingTest() } },
                )
                DiagSection.LIVE_SENSORS -> LiveSensorsPane(
                    sensorData = sensorData,
                    history = sensorHistory,
                )
            }
        }
    }
}

// ----------------------------------------------------------------------------
// Sidebar
//
// Two live sections now: CONNECTIVITY (the original ping/port-scan flow) and
// LIVE SENSORS (raw sensor stream + rolling history; replaces the old
// TELEMETRY placeholder, which was never implemented). MOCK MODE placeholder
// was removed — no concrete plan for it. The BACK button lives in the TopBar
// for cross-screen consistency.
// ----------------------------------------------------------------------------
@Composable
private fun DiagSidebar(
    active: DiagSection,
    onSelect: (DiagSection) -> Unit,
) {
    val t = LocalTactical.current
    Column(
        modifier = Modifier
            .width(responsiveDp(tablet = 220.dp, compact = 140.dp))
            .fillMaxHeight()
            .background(t.panel)
            .drawBehind {
                drawLine(
                    color = t.line,
                    start = Offset(size.width, 0f),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .padding(24.dp),
    ) {
        Lbl(text = "DIAGNOSTICS")
        Spacer(Modifier.height(16.dp))
        DiagNavItem(
            label = "CONNECTIVITY",
            active = active == DiagSection.CONNECTIVITY,
            onClick = { onSelect(DiagSection.CONNECTIVITY) },
        )
        Spacer(Modifier.height(4.dp))
        DiagNavItem(
            label = "LIVE SENSORS",
            active = active == DiagSection.LIVE_SENSORS,
            onClick = { onSelect(DiagSection.LIVE_SENSORS) },
        )
    }
}

@Composable
private fun DiagNavItem(label: String, active: Boolean, onClick: () -> Unit) {
    val t = LocalTactical.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (active) t.panelHi else Color.Transparent)
            .drawBehind {
                if (active) {
                    drawLine(
                        color = t.accent,
                        start = Offset(0f, 0f),
                        end = Offset(0f, size.height),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = if (active) t.ink else t.inkDim,
            fontSize = 11.sp,
            letterSpacing = 0.14.em,
            fontFamily = JetBrainsMono,
        )
    }
}

// ----------------------------------------------------------------------------
// Main pane
// ----------------------------------------------------------------------------
@Composable
private fun DiagMainPane(
    state: DiagnosticsState,
    onIpChange: (String) -> Unit,
    onRunAll: () -> Unit,
    onQuickPing: () -> Unit,
) {
    val t = LocalTactical.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 24.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        // Header row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Lbl(text = "NODE TARGET · ${state.ipAddress.ifBlank { "—" }}")
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "CONNECTIVITY TEST",
                    color = t.ink,
                    fontSize = 22.sp,
                    letterSpacing = 0.06.em,
                    fontFamily = JetBrainsMono,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MonoButton(
                    label = if (state.isRunning) "TESTING…" else "RUN ALL",
                    primary = true,
                    enabled = !state.isRunning,
                    onClick = onRunAll,
                )
                MonoButton(
                    label = "QUICK PING",
                    primary = false,
                    enabled = !state.isRunning,
                    onClick = onQuickPing,
                )
            }
        }
        Spacer(Modifier.height(20.dp))

        // IP address input
        IpAddressField(value = state.ipAddress, onChange = onIpChange)
        Spacer(Modifier.height(16.dp))

        // PING card
        PingCard(state = state)
        Spacer(Modifier.height(16.dp))

        // PORT SCAN card
        PortScanCard(state = state)

        // Recommendations
        if (state.recommendations.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            RecommendationsCard(recommendations = state.recommendations)
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = "FALLBACK MOCK READY · TAP RUN ALL TO RE-TEST",
            color = t.inkMute,
            fontSize = 10.sp,
            letterSpacing = 0.14.em,
            fontFamily = JetBrainsMono,
        )
    }
}

// ----------------------------------------------------------------------------
// LIVE SENSORS pane
//
// Shows every sub-frame from the RAW sensor stream (NOT the dashboard's
// latched version) so the operator can see actual valid/invalid flags as
// they come from the Pi. Top half: current-frame summary cards including
// the sensors the dashboard doesn't display (compass, servo). Bottom half:
// rolling history of the last [SENSOR_HISTORY_SIZE] frames, oldest first.
// ----------------------------------------------------------------------------
@Composable
private fun LiveSensorsPane(
    sensorData: SensorData?,
    history: List<SensorData>,
) {
    val t = LocalTactical.current
    val ddl = sensorData?.ddlFrame

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 24.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Lbl(text = "STREAM · DDL_FRAME · RAW")
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "LIVE SENSORS",
                    color = t.ink,
                    fontSize = 22.sp,
                    letterSpacing = 0.06.em,
                    fontFamily = JetBrainsMono,
                )
            }
            val ts = sensorData?.timestamp
            Chip(
                text = if (ts != null && ts > 0L) "FRAME @ $ts" else "NO DATA",
                tone = if (ts != null && ts > 0L) ChipTone.On else ChipTone.Dim,
            )
        }
        Spacer(Modifier.height(20.dp))

        SubFrameCard(
            title = "DISTANCE",
            valid = ddl?.distance?.valid,
            lines = ddl?.distance?.let {
                listOf(
                    "distance_m  = %.2f".format(it.distanceM),
                    "status      = ${it.status}",
                    "precision   = ${it.precision}",
                    "strength    = ${it.strength}",
                )
            },
        )
        Spacer(Modifier.height(12.dp))
        SubFrameCard(
            title = "TEMPERATURE / HUMIDITY",
            valid = ddl?.temperatureHumidity?.valid,
            lines = ddl?.temperatureHumidity?.let {
                listOf(
                    "temperature_c = %.2f".format(it.temperatureC),
                    "humidity_pct  = %.2f".format(it.humidityPct),
                )
            },
        )
        Spacer(Modifier.height(12.dp))
        SubFrameCard(
            title = "SERVO",
            // ServoFrame has no `valid` flag on the Pi (matches the C struct).
            // Pass null so the chip renders "—" instead of OK/INVALID.
            valid = null,
            lines = ddl?.servo?.let {
                listOf(
                    "horizontal_deg = %.2f".format(it.horizontalDeg),
                    "vertical_deg   = %.2f".format(it.verticalDeg),
                )
            },
        )
        Spacer(Modifier.height(12.dp))
        SubFrameCard(
            title = "GPS",
            valid = ddl?.gps?.valid,
            lines = ddl?.gps?.let {
                listOf(
                    "fix_type       = ${it.fixType}",
                    "num_satellites = ${it.numSatellites}",
                    "latitude_deg   = %.6f".format(it.latitudeDeg),
                    "longitude_deg  = %.6f".format(it.longitudeDeg),
                    "altitude_m     = %.2f".format(it.altitudeM),
                    "h_acc_m        = %.2f".format(it.hAccM),
                )
            },
        )
        Spacer(Modifier.height(12.dp))
        SubFrameCard(
            title = "COMPASS",
            valid = ddl?.compass?.valid,
            lines = ddl?.compass?.let {
                // headingDeg is nullable — show "null" literally so the
                // diagnostic operator sees the Pi's actual emission.
                val heading = it.headingDeg?.let { h -> "%.2f".format(h) } ?: "null"
                listOf(
                    "heading_deg   = $heading",
                    "raw_x         = ${it.rawX}",
                    "raw_y         = ${it.rawY}",
                    "raw_z         = ${it.rawZ}",
                    "temperature_c = %.2f".format(it.temperatureC),
                )
            },
        )
        Spacer(Modifier.height(12.dp))
        SubFrameCard(
            title = "WIND",
            // Wind has TWO independent valid flags; show both rather than
            // forcing a single chip. Pass null so the chip just shows "—"
            // and let the body lines carry the per-channel state.
            valid = null,
            lines = ddl?.wind?.let {
                listOf(
                    "speed_valid     = ${it.speedValid}",
                    "speed_mps       = %.2f".format(it.speedMps),
                    "direction_valid = ${it.directionValid}",
                    "direction_deg   = %.2f".format(it.directionDeg),
                )
            },
        )

        Spacer(Modifier.height(24.dp))
        Lbl(text = "HISTORY · LAST ${history.size} FRAMES")
        Spacer(Modifier.height(8.dp))
        if (history.isEmpty()) {
            Text(
                text = "NO FRAMES RECEIVED",
                color = t.inkMute,
                fontSize = 11.sp,
                letterSpacing = 0.14.em,
                fontFamily = JetBrainsMono,
            )
        } else {
            // Oldest first; reverse so newest sits at the top of the strip,
            // which is what an operator scanning for "what just happened" wants.
            history.asReversed().forEach { frame ->
                HistoryRow(frame = frame)
            }
        }
    }
}

@Composable
private fun SubFrameCard(
    title: String,
    valid: Boolean?,
    lines: List<String>?,
) {
    val t = LocalTactical.current
    val chipText = when (valid) {
        true -> "VALID"
        false -> "INVALID"
        null -> "—"
    }
    val chipTone = when (valid) {
        true -> ChipTone.On
        false -> ChipTone.Danger
        null -> ChipTone.Dim
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(t.panel)
            .border(1.dp, t.line)
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Lbl(text = title)
            Chip(text = chipText, tone = chipTone)
        }
        Spacer(Modifier.height(10.dp))
        if (lines.isNullOrEmpty()) {
            Text(
                text = "(sub-frame absent)",
                color = t.inkMute,
                fontSize = 11.sp,
                letterSpacing = 0.1.em,
                fontFamily = JetBrainsMono,
            )
        } else {
            lines.forEach { line ->
                Text(
                    text = line,
                    color = t.ink,
                    fontSize = 11.sp,
                    letterSpacing = 0.06.em,
                    fontFamily = JetBrainsMono,
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(frame: SensorData) {
    val t = LocalTactical.current
    val ddl = frame.ddlFrame
    // Compact one-line summary — just enough to spot a trend across rows.
    val dist = ddl?.distance?.takeIf { it.valid }?.distanceM?.let { "%.0fm".format(it) } ?: "—"
    val temp = ddl?.temperatureHumidity?.takeIf { it.valid }?.temperatureC?.let { "%.0f°C".format(it) } ?: "—"
    val heading = ddl?.compass?.takeIf { it.valid }?.headingDeg?.let { "%.0f°".format(it) } ?: "—"
    val wind = ddl?.wind?.takeIf { it.speedValid }?.speedMps?.let { "%.1fm/s".format(it) } ?: "—"
    val sats = ddl?.gps?.takeIf { it.valid }?.numSatellites?.let { "${it}sat" } ?: "—"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    color = t.line,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = frame.timestamp.toString(),
            color = t.inkDim,
            fontSize = 10.sp,
            letterSpacing = 0.06.em,
            fontFamily = JetBrainsMono,
            modifier = Modifier.width(140.dp),
        )
        HistoryCell(value = dist, width = 80.dp)
        HistoryCell(value = temp, width = 70.dp)
        HistoryCell(value = heading, width = 70.dp)
        HistoryCell(value = wind, width = 90.dp)
        HistoryCell(value = sats, width = 60.dp)
    }
}

@Composable
private fun HistoryCell(value: String, width: androidx.compose.ui.unit.Dp) {
    val t = LocalTactical.current
    Text(
        text = value,
        color = t.ink,
        fontSize = 11.sp,
        letterSpacing = 0.06.em,
        fontFamily = JetBrainsMono,
        modifier = Modifier.width(width),
    )
}

// ----------------------------------------------------------------------------
// Mono button (small, two variants)
// ----------------------------------------------------------------------------
@Composable
private fun MonoButton(
    label: String,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val t = LocalTactical.current
    Box(
        modifier = Modifier
            .background(if (primary && enabled) t.panelHi else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (primary) t.lineHi else t.line,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            color = if (enabled) t.ink else t.inkMute,
            fontSize = 11.sp,
            letterSpacing = 0.18.em,
            fontFamily = JetBrainsMono,
        )
    }
}

// ----------------------------------------------------------------------------
// IP address input
// ----------------------------------------------------------------------------
@Composable
private fun IpAddressField(value: String, onChange: (String) -> Unit) {
    val t = LocalTactical.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(t.panel)
            .border(1.dp, t.line)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Lbl(text = "RASPBERRY PI IP ADDRESS")
        Spacer(Modifier.height(8.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(
                color = t.ink,
                fontSize = 14.sp,
                letterSpacing = 0.08.em,
                fontFamily = JetBrainsMono,
            ),
            cursorBrush = SolidColor(t.accent),
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawLine(
                        color = t.line,
                        start = Offset(0f, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
                .padding(vertical = 6.dp),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        text = "10.42.1.1",
                        color = t.inkMute,
                        fontSize = 14.sp,
                        fontFamily = JetBrainsMono,
                    )
                }
                inner()
            },
        )
    }
}

// ----------------------------------------------------------------------------
// PING card
// ----------------------------------------------------------------------------
@Composable
private fun PingCard(state: DiagnosticsState) {
    val t = LocalTactical.current
    val (chipText, chipTone) = state.pingStatus.toChip()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(t.panel)
            .border(1.dp, t.line)
            .padding(20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Lbl(text = "ICMP REACHABILITY · ${state.ipAddress.ifBlank { "—" }}")
            Chip(text = chipText, tone = chipTone)
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = if (state.pingMessage.isBlank()) "Awaiting test run." else state.pingMessage.uppercase(),
            color = when (state.pingStatus) {
                TestStatus.SUCCESS -> t.on
                TestStatus.FAILED  -> t.danger
                TestStatus.RUNNING -> t.accent
                TestStatus.PENDING -> t.inkDim
            },
            fontSize = 12.sp,
            letterSpacing = 0.1.em,
            fontFamily = JetBrainsMono,
        )
    }
}

// ----------------------------------------------------------------------------
// PORT SCAN card
// ----------------------------------------------------------------------------
@Composable
private fun PortScanCard(state: DiagnosticsState) {
    val t = LocalTactical.current
    val rows = listOf(
        PortRow(port = 8555, proto = "WEBSOCKET", status = state.websocketStatus),
        PortRow(port = 8000, proto = "HTTP API",  status = state.httpStatus),
        PortRow(port = 8554, proto = "RTSP",      status = state.videoStatus),
    )
    val openCount = rows.count { it.status == TestStatus.SUCCESS }
    val totalCount = rows.size

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(t.panel)
            .border(1.dp, t.line)
            .padding(20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Lbl(text = "PORT SCAN")
            val portChipTone = when {
                openCount == totalCount -> ChipTone.On
                openCount > 0 -> ChipTone.Warn
                else -> ChipTone.Danger
            }
            Chip(text = "$openCount/$totalCount OPEN", tone = portChipTone)
        }
        Spacer(Modifier.height(14.dp))
        rows.forEachIndexed { idx, row ->
            PortRowView(row = row, isFirst = idx == 0)
        }
    }
}

private data class PortRow(val port: Int, val proto: String, val status: TestStatus)

@Composable
private fun PortRowView(row: PortRow, isFirst: Boolean) {
    val t = LocalTactical.current
    val (statusText, tone) = row.status.toChip()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                if (!isFirst) {
                    drawLine(
                        color = t.line,
                        start = Offset(0f, 0f),
                        end = Offset(size.width, 0f),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = ":${row.port}",
            color = t.ink,
            fontSize = 13.sp,
            fontFamily = JetBrainsMono,
            modifier = Modifier.width(90.dp),
        )
        Text(
            text = row.proto,
            color = t.inkDim,
            fontSize = 12.sp,
            letterSpacing = 0.1.em,
            fontFamily = JetBrainsMono,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "TCP",
            color = t.inkDim,
            fontSize = 10.sp,
            letterSpacing = 0.14.em,
            fontFamily = JetBrainsMono,
            modifier = Modifier.padding(end = 20.dp),
        )
        Chip(text = statusText, tone = tone)
    }
}

// ----------------------------------------------------------------------------
// Recommendations card
// ----------------------------------------------------------------------------
@Composable
private fun RecommendationsCard(recommendations: List<String>) {
    val t = LocalTactical.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(t.panel)
            .border(1.dp, t.accentDim)
            .padding(20.dp),
    ) {
        Lbl(text = "RECOMMENDATIONS", color = t.accent)
        Spacer(Modifier.height(10.dp))
        recommendations.forEach { rec ->
            Row(
                modifier = Modifier.padding(vertical = 3.dp),
            ) {
                Text(
                    text = "•",
                    color = t.accent,
                    fontSize = 12.sp,
                    fontFamily = JetBrainsMono,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = rec,
                    color = t.ink,
                    fontSize = 12.sp,
                    fontFamily = JetBrainsMono,
                )
            }
        }
    }
}

// ----------------------------------------------------------------------------
// TestStatus → chip mapping
// ----------------------------------------------------------------------------
private fun TestStatus.toChip(): Pair<String, ChipTone> = when (this) {
    TestStatus.PENDING -> "PENDING"  to ChipTone.Dim
    TestStatus.RUNNING -> "TESTING…" to ChipTone.Warn
    TestStatus.SUCCESS -> "OPEN"     to ChipTone.On
    TestStatus.FAILED  -> "CLOSED"   to ChipTone.Danger
}

// ============================================================================
// ViewModel + State — UNCHANGED behaviour from previous version. The IP
// address, the NetworkTester calls, the recommendations logic — all
// identical. Only the UI above was rewritten.
// ============================================================================

class DiagnosticsViewModel : ViewModel() {
    private val _diagnosticsState = MutableStateFlow(DiagnosticsState())
    val diagnosticsState: StateFlow<DiagnosticsState> = _diagnosticsState.asStateFlow()

    private val networkTester = NetworkTester()

    fun updateIpAddress(ip: String) {
        _diagnosticsState.value = _diagnosticsState.value.copy(ipAddress = ip)
    }

    suspend fun quickPingTest() {
        val ip = _diagnosticsState.value.ipAddress
        if (ip.isEmpty()) return

        _diagnosticsState.value = _diagnosticsState.value.copy(
            isRunning = true,
            pingStatus = TestStatus.RUNNING
        )

        val result = networkTester.pingDevice(ip)

        _diagnosticsState.value = _diagnosticsState.value.copy(
            isRunning = false,
            pingStatus = if (result) TestStatus.SUCCESS else TestStatus.FAILED,
            pingMessage = if (result) "Device is reachable" else "Device not responding"
        )
    }

    suspend fun runFullDiagnostics() {
        val ip = _diagnosticsState.value.ipAddress
        if (ip.isEmpty()) return

        _diagnosticsState.value = _diagnosticsState.value.copy(
            isRunning = true,
            recommendations = emptyList()
        )

        val recommendations = mutableListOf<String>()

        // Test 1: Ping
        _diagnosticsState.value = _diagnosticsState.value.copy(pingStatus = TestStatus.RUNNING)
        val pingResult = networkTester.pingDevice(ip)
        _diagnosticsState.value = _diagnosticsState.value.copy(
            pingStatus = if (pingResult) TestStatus.SUCCESS else TestStatus.FAILED,
            pingMessage = if (pingResult) "Device is reachable" else "Cannot reach device"
        )

        if (!pingResult) {
            recommendations.add("Check that the tablet is joined to the RPi5 access point (MyHotspot).")
            recommendations.add("Verify the IP address is correct (default: 10.42.1.1).")
            recommendations.add("Check that the Raspberry Pi is powered on and streaming_server is running.")
        }

        // Test 2-4: Port scans (only if ping succeeds)
        if (pingResult) {
            val ports = listOf(8555, 8000, 8554)
            val portResults = networkTester.scanPorts(ip, ports)

            // WebSocket (8555)
            val wsResult = portResults[8555] ?: false
            _diagnosticsState.value = _diagnosticsState.value.copy(
                websocketStatus = if (wsResult) TestStatus.SUCCESS else TestStatus.FAILED,
                websocketMessage = if (wsResult) "Port is open and ready" else "Port is closed or blocked"
            )

            // HTTP (8000)
            val httpResult = portResults[8000] ?: false
            _diagnosticsState.value = _diagnosticsState.value.copy(
                httpStatus = if (httpResult) TestStatus.SUCCESS else TestStatus.FAILED,
                httpMessage = if (httpResult) "API server is running" else "API server not responding"
            )

            // Video (8554)
            val videoResult = portResults[8554] ?: false
            _diagnosticsState.value = _diagnosticsState.value.copy(
                videoStatus = if (videoResult) TestStatus.SUCCESS else TestStatus.FAILED,
                videoMessage = if (videoResult) "Video stream available" else "Video stream not available"
            )

            if (!wsResult) recommendations.add("Start streaming_server on the Pi (port 8555 must be listening).")
            if (!httpResult) recommendations.add("Start the HTTP API server on the Pi (port 8000).")
            if (!videoResult) recommendations.add("Start mediamtx on the Pi (port 8554 must be listening).")
            if (wsResult && httpResult && videoResult) {
                recommendations.add("All systems operational. Ready to connect.")
            }
        }

        _diagnosticsState.value = _diagnosticsState.value.copy(
            isRunning = false,
            recommendations = recommendations
        )
    }
}

data class DiagnosticsState(
    val ipAddress: String = "10.42.1.1",
    val isRunning: Boolean = false,
    val pingStatus: TestStatus = TestStatus.PENDING,
    val pingMessage: String = "",
    val websocketStatus: TestStatus = TestStatus.PENDING,
    val websocketMessage: String = "",
    val httpStatus: TestStatus = TestStatus.PENDING,
    val httpMessage: String = "",
    val videoStatus: TestStatus = TestStatus.PENDING,
    val videoMessage: String = "",
    val recommendations: List<String> = emptyList(),
)

enum class TestStatus {
    PENDING, RUNNING, SUCCESS, FAILED
}

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
@Composable
fun DiagnosticsScreen(
    onBackClick: () -> Unit,
    isDarkTheme: Boolean = true,
    onToggleTheme: () -> Unit = {},
    viewModel: DiagnosticsViewModel = viewModel(),
) {
    val t = LocalTactical.current
    val state by viewModel.diagnosticsState.collectAsState()
    val scope = rememberCoroutineScope()

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
            DiagSidebar()
            DiagMainPane(
                state = state,
                onIpChange = viewModel::updateIpAddress,
                onRunAll = { scope.launch { viewModel.runFullDiagnostics() } },
                onQuickPing = { scope.launch { viewModel.quickPingTest() } },
            )
        }
    }
}

// ----------------------------------------------------------------------------
// Sidebar
//
// LOGS removed (the app has no log screen). MOCK MODE and TELEMETRY are kept
// as placeholders for future features but rendered as DISABLED — they don't
// pretend to be clickable. The BACK button used to live at the bottom of
// this sidebar; it now lives in the TopBar for cross-screen consistency.
// ----------------------------------------------------------------------------
@Composable
private fun DiagSidebar() {
    val t = LocalTactical.current
    Column(
        modifier = Modifier
            .width(220.dp)
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
        DiagNavItem(label = "CONNECTIVITY", active = true,  disabled = false)
        Spacer(Modifier.height(4.dp))
        DiagNavItem(label = "MOCK MODE",    active = false, disabled = true)
        Spacer(Modifier.height(4.dp))
        DiagNavItem(label = "TELEMETRY",    active = false, disabled = true)
    }
}

@Composable
private fun DiagNavItem(label: String, active: Boolean, disabled: Boolean) {
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
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            color = when {
                active -> t.ink
                disabled -> t.inkMute
                else -> t.inkDim
            },
            fontSize = 11.sp,
            letterSpacing = 0.14.em,
            fontFamily = JetBrainsMono,
        )
        if (disabled) {
            Text(
                text = "SOON",
                color = t.inkMute,
                fontSize = 8.sp,
                letterSpacing = 0.2.em,
                fontFamily = JetBrainsMono,
            )
        }
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

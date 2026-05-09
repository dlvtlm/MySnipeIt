package com.example.mysnipeit.ui.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
 * Connection Diagnostics Screen
 *
 * Use this to test connectivity to your Raspberry Pi before full integration
 * Helps identify network issues early
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onBackClick: () -> Unit,
    viewModel: DiagnosticsViewModel = viewModel()
) {
    val diagnosticsState by viewModel.diagnosticsState.collectAsState()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "CONNECTION DIAGNOSTICS",
                        color = MilitaryTextPrimary,
                        fontFamily = FontFamily.Monospace
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Text(
                            text = "←",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = MilitaryTextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MilitaryCardBackground
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MilitaryDarkBackground)
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // IP Address Input
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MilitaryCardBackground
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "RASPBERRY PI IP ADDRESS",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MilitaryTextPrimary,
                            fontFamily = FontFamily.Monospace
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = diagnosticsState.ipAddress,
                            onValueChange = { viewModel.updateIpAddress(it) },
                            placeholder = { Text("10.42.1.1") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = MilitaryTextPrimary,
                                unfocusedTextColor = MilitaryTextPrimary,
                                focusedBorderColor = MilitaryAccentGreen,
                                unfocusedBorderColor = MilitaryBorderColor
                            ),
                            singleLine = true
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Test Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                viewModel.runFullDiagnostics()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MilitaryAccentGreen
                        ),
                        enabled = !diagnosticsState.isRunning
                    ) {
                        Text(
                            text = if (diagnosticsState.isRunning) "TESTING..." else "RUN FULL TEST",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                viewModel.quickPingTest()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MilitaryBorderColor
                        ),
                        enabled = !diagnosticsState.isRunning
                    ) {
                        Text(
                            text = "QUICK PING",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MilitaryTextPrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Test Results
                Text(
                    text = "TEST RESULTS",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MilitaryTextPrimary,
                    fontFamily = FontFamily.Monospace
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Ping Test Result
                TestResultCard(
                    title = "PING TEST",
                    status = diagnosticsState.pingStatus,
                    message = diagnosticsState.pingMessage
                )

                Spacer(modifier = Modifier.height(8.dp))

                // WebSocket Port Test
                TestResultCard(
                    title = "WEBSOCKET PORT (8555)",
                    status = diagnosticsState.websocketStatus,
                    message = diagnosticsState.websocketMessage
                )

                Spacer(modifier = Modifier.height(8.dp))

                // HTTP Port Test
                TestResultCard(
                    title = "HTTP API PORT (8000)",
                    status = diagnosticsState.httpStatus,
                    message = diagnosticsState.httpMessage
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Video Port Test
                TestResultCard(
                    title = "VIDEO STREAM PORT (8554)",
                    status = diagnosticsState.videoStatus,
                    message = diagnosticsState.videoMessage
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Recommendations
                if (diagnosticsState.recommendations.isNotEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MilitaryWarningAmber.copy(alpha = 0.1f)
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MilitaryWarningAmber)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = "RECOMMENDATIONS",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MilitaryWarningAmber,
                                fontFamily = FontFamily.Monospace
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            diagnosticsState.recommendations.forEach { recommendation ->
                                Text(
                                    text = "• $recommendation",
                                    fontSize = 12.sp,
                                    color = MilitaryTextPrimary,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TestResultCard(
    title: String,
    status: TestStatus,
    message: String
) {
    val (statusColor, statusText) = when (status) {
        TestStatus.PENDING -> Pair(MilitaryTextSecondary, "PENDING")
        TestStatus.RUNNING -> Pair(StatusConnecting, "TESTING...")
        TestStatus.SUCCESS -> Pair(StatusConnected, "SUCCESS")
        TestStatus.FAILED -> Pair(StatusError, "FAILED")
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MilitaryCardBackground
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            when (status) {
                TestStatus.SUCCESS -> StatusConnected
                TestStatus.FAILED -> StatusError
                else -> MilitaryBorderColor
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MilitaryTextPrimary,
                    fontFamily = FontFamily.Monospace
                )

                if (message.isNotEmpty()) {
                    Text(
                        text = message,
                        fontSize = 10.sp,
                        color = MilitaryTextSecondary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Surface(
                color = statusColor.copy(alpha = 0.2f),
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = statusText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

// ViewModel for diagnostics
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
            recommendations.add("Check if both devices are on the same WiFi network")
            recommendations.add("Verify the IP address is correct")
            recommendations.add("Check if Raspberry Pi is powered on")
        }

        // Test 2-4: Port scans (only if ping succeeds)
        if (pingResult) {
            val ports = mapOf(
                8555 to "websocketStatus",
                8000 to "httpStatus",
                8554 to "videoStatus"
            )

            val portResults = networkTester.scanPorts(ip, ports.keys.toList())

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

            // Add recommendations based on results
            if (!wsResult) recommendations.add("Start WebSocket server on Raspberry Pi (port 8555)")
            if (!httpResult) recommendations.add("Start HTTP API server on Raspberry Pi (port 8000)")
            if (!videoResult) recommendations.add("Start video streaming server on Raspberry Pi (port 8554)")

            if (wsResult && httpResult && videoResult) {
                recommendations.add("All systems operational! Ready to connect.")
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
    val recommendations: List<String> = emptyList()
)

enum class TestStatus {
    PENDING, RUNNING, SUCCESS, FAILED
}
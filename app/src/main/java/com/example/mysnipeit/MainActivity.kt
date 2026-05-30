package com.example.mysnipeit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mysnipeit.ui.theme.MySniperItTheme
import com.example.mysnipeit.viewmodel.SniperViewModel
import com.example.mysnipeit.viewmodel.AppScreen
import com.example.mysnipeit.ui.home.HomeScreen
import com.example.mysnipeit.ui.device.DeviceSelectionScreen
import com.example.mysnipeit.ui.map.MapScreen
import com.example.mysnipeit.ui.dashboard.DashboardScreen
import com.google.android.gms.maps.model.LatLng
import android.util.Log
import com.example.mysnipeit.ui.diagnostics.DiagnosticsScreen

class MainActivity : ComponentActivity() {
    private val viewModel: SniperViewModel by viewModels()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveMode()
        setContent {
            val darkTheme by viewModel.darkTheme.collectAsStateWithLifecycle()
            MySniperItTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SniperApp(viewModel = viewModel)
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Re-hide system bars whenever the app regains focus (user swipes from
        // edge to peek the bars, then they auto-hide again on focus return).
        if (hasFocus) enableImmersiveMode()
    }

    /**
     * Hide both status bar and navigation bar (the bottom strip with the
     * Recents/Home/Back icons) and let users peek them with an edge swipe.
     */
    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

@Composable
fun SniperApp(viewModel: SniperViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val availableDevices by viewModel.availableDevices.collectAsStateWithLifecycle()
    val sensorData by viewModel.sensorData.collectAsStateWithLifecycle()
    val detectedTargets by viewModel.detectedTargets.collectAsStateWithLifecycle()
    val shootingSolution by viewModel.shootingSolution.collectAsStateWithLifecycle()
    val systemStatus by viewModel.systemStatus.collectAsStateWithLifecycle()
    val selectedTargetId = uiState.selectedTargetId
    val streamReady by viewModel.streamReady.collectAsState()
    val rtspStreamUrl by viewModel.rtspStreamUrl.collectAsState()
    val darkTheme by viewModel.darkTheme.collectAsStateWithLifecycle()


    // User location (mock location for now - can be replaced with real GPS later)
    val userLocation = remember { LatLng( 31.518209, 34.521274) }

    // Track menu state
    var showMenu by remember { mutableStateOf(false) }

    when (uiState.currentScreen) {
        AppScreen.HOME -> {
            HomeScreen(
                onDeviceListClick = { viewModel.navigateToDeviceList() },
                onMapClick = { viewModel.navigateToMap() },
                onDiagnosticsClick = { viewModel.navigateToDiagnostics() },
                isDarkTheme = darkTheme,
                onToggleTheme = { viewModel.toggleTheme() },
            )
        }

        AppScreen.DEVICE_SELECTION -> {
            DeviceSelectionScreen(
                devices = availableDevices,
                onDeviceSelected = { device -> viewModel.selectDevice(device) },
                onBackClick = { viewModel.navigateToHome() },
                onMapClick = { viewModel.navigateToMap() },
                onDiagnosticsClick = { viewModel.navigateToDiagnostics() },
                isDarkTheme = darkTheme,
                onToggleTheme = { viewModel.toggleTheme() },
            )
        }

        AppScreen.MAP -> {
            MapScreen(
                devices = availableDevices,
                userLocation = userLocation,
                onDeviceSelected = { device -> viewModel.selectDevice(device) },
                onBackClick = { viewModel.navigateToHome() },
                isDarkTheme = darkTheme,
                onToggleTheme = { viewModel.toggleTheme() },
            )
        }

        AppScreen.DASHBOARD -> {
            DashboardScreen(
                sensorData = sensorData,
                detectedTargets = detectedTargets,
                shootingSolution = shootingSolution,
                systemStatus = systemStatus,
                selectedTargetId = selectedTargetId,
                streamReady = streamReady,
                rtspStreamUrl = rtspStreamUrl,
                onTargetSelect = { targetId ->
                    if (targetId.isEmpty()) viewModel.deselectTarget()
                    else viewModel.selectTarget(targetId)
                },
                onTargetLockToggle = { targetId, isLocking ->
                    if (isLocking) viewModel.lockTarget(targetId)
                    else viewModel.unlockTarget(targetId)
                },
                onConnectClick = { viewModel.connectToSystem() },
                onDisconnectClick = { viewModel.disconnectFromSystem() },
                onBackClick = { viewModel.goBackFromDashboard() },
                onMenuClick = { showMenu = true },
                isDarkTheme = darkTheme,
                onToggleTheme = { viewModel.toggleTheme() },
            )

            // Menu dropdown — same as before; gives the dashboard a way to
            // reach Diagnostics without leaving the operator surface.
            if (showMenu) {
                DashboardMenu(
                    onDismiss = { showMenu = false },
                    onDiagnosticsClick = {
                        showMenu = false
                        viewModel.navigateToDiagnostics()
                    }
                )
            }
        }

        AppScreen.DIAGNOSTICS -> {
            DiagnosticsScreen(
                onBackClick = { viewModel.navigateToHome() },
                isDarkTheme = darkTheme,
                onToggleTheme = { viewModel.toggleTheme() },
            )
        }
    }
}

@Composable
fun DashboardMenu(
    onDismiss: () -> Unit,
    onDiagnosticsClick: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Menu",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column {
                TextButton(
                    onClick = onDiagnosticsClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "⚙ ",
                            fontSize = 20.sp
                        )
                        Text(
                            text = "Diagnostics",
                            fontSize = 16.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
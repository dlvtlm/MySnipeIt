package com.example.mysnipeit.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import com.example.mysnipeit.data.models.SensorData
import com.example.mysnipeit.data.models.DetectedTarget
import com.example.mysnipeit.data.models.ShootingSolution
import com.example.mysnipeit.data.models.SystemStatus
import com.example.mysnipeit.data.models.ConnectionState
import com.example.mysnipeit.ui.theme.*
import android.util.Log

@Composable
fun DashboardScreen(
    sensorData: SensorData?,
    detectedTargets: List<DetectedTarget>,
    shootingSolution: ShootingSolution?,
    systemStatus: SystemStatus,
    selectedTargetId: String?,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onTargetSelect: (String) -> Unit = {},
    onTargetLockToggle: (String, Boolean) -> Unit = { _, _ -> },
    onBackClick: () -> Unit = {},
    onMenuClick: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MilitaryDarkBackground)
    ) {
        // Top HUD Bar with Shooting Solution
        TopHudBar(
            sensorData = sensorData,
            shootingSolution = shootingSolution,
            systemStatus = systemStatus,
            selectedTargetId = selectedTargetId,
            onBackClick = onBackClick,
            onMenuClick = onMenuClick,
            onConnectClick = onConnectClick,
            onDisconnectClick = onDisconnectClick
        )

        // Main Video Feed
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            VideoFeedSection(
                detectedTargets = detectedTargets,
                shootingSolution = shootingSolution,
                selectedTargetId = selectedTargetId,
                systemStatus = systemStatus,
                onTargetSelect = onTargetSelect,
                onTargetLockToggle = onTargetLockToggle,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun TopHudBar(
    sensorData: SensorData?,
    shootingSolution: ShootingSolution?,
    systemStatus: SystemStatus,
    selectedTargetId: String?,
    onBackClick: () -> Unit,
    onMenuClick: () -> Unit,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp),
        color = MilitaryCardBackground.copy(alpha = 0.95f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left section - Navigation
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            MilitaryBorderColor,
                            RoundedCornerShape(4.dp)
                        )
                ) {
                    Text(
                        text = "←",
                        color = MilitaryTextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                IconButton(
                    onClick = onMenuClick,
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            MilitaryBorderColor,
                            RoundedCornerShape(4.dp)
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "Menu",
                        tint = MilitaryTextPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            //  Center section - Shooting Solution Data
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Only show solution if target is selected
                if (shootingSolution != null) {
                    HudDataBox(
                        label = "TGT",
                        value = shootingSolution.targetId,
                        isActive = true,
                        textColor = Color(0xFFFF6B35)  // Orange for active target
                    )
                    HudDataBox(
                        label = "AZ",
                        value = "${shootingSolution.azimuth.toInt()}°",
                        isActive = true
                    )
                    HudDataBox(
                        label = "EL",
                        value = "${if (shootingSolution.elevation > 0) "+" else ""}${shootingSolution.elevation.toInt()}°",
                        isActive = true
                    )
                    HudDataBox(
                        label = "CONF",
                        value = "${(shootingSolution.confidence * 100).toInt()}%",
                        isActive = shootingSolution.confidence > 0.7f,
                        textColor = when {
                            shootingSolution.confidence > 0.8f -> Color(0xFF038C16)
                            shootingSolution.confidence > 0.6f -> Color(0xFFFFAA00)
                            else -> Color(0xFFFF4444)
                        }
                    )
                } else {
                    // No target selected - show dashes
                    HudDataBox(label = "TGT", value = "--", isActive = false)
                    HudDataBox(label = "AZ", value = "--", isActive = false)
                    HudDataBox(label = "EL", value = "--", isActive = false)
                    HudDataBox(label = "CONF", value = "--", isActive = false)
                }
            }

            // Right section - Environmental data
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (sensorData != null) {
                    HudDataBox(
                        label = "TEMP",
                        value = "${sensorData.temperature.toInt()}°C",
                        isActive = true
                    )
                    HudDataBox(
                        label = "HUM",
                        value = "${sensorData.humidity.toInt()}%",
                        isActive = true
                    )
                    HudDataBox(
                        label = "W.SPD",
                        value = "${sensorData.windSpeed.toInt()}m/s",
                        isActive = true
                    )
                    HudDataBox(
                        label = "W.DIR",
                        value = "${sensorData.windDirection.toInt()}°",
                        isActive = true
                    )
                    HudDataBox(
                        label = "RNG",
                        value = "${sensorData.rangefinderDistance.toInt()}m",
                        isActive = true
                    )
                } else {
                    HudDataBox(label = "TEMP", value = "--", isActive = false)
                    HudDataBox(label = "HUM", value = "--", isActive = false)
                    HudDataBox(label = "W.SPD", value = "--", isActive = false)
                    HudDataBox(label = "W.DIR", value = "--", isActive = false)
                    HudDataBox(label = "RNG", value = "--", isActive = false)
                }
            }

            // Far right section - Status indicators
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ConnectionStatusCompact(systemStatus)

                systemStatus.batteryLevel?.let { battery ->
                    HudDataBox(
                        label = "BAT",
                        value = "$battery%",
                        isActive = battery > 20
                    )
                }
            }
        }
    }
}

@Composable
private fun HudDataBox(
    label: String,
    value: String,
    isActive: Boolean,
    textColor: Color? = null
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .background(
                if (isActive) MilitaryAccentGreen.copy(alpha = 0.3f) else Color.Transparent,
                RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            color = MilitaryTextSecondary,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Normal
        )
        Text(
            text = value,
            color = textColor ?: if (isActive) MilitaryTextPrimary else MilitaryTextSecondary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ConnectionStatusCompact(systemStatus: SystemStatus) {
    val statusColor = when (systemStatus.connectionStatus) {
        ConnectionState.CONNECTED -> StatusConnected
        ConnectionState.CONNECTING -> StatusConnecting
        ConnectionState.DISCONNECTED -> StatusDisconnected
        ConnectionState.ERROR -> StatusError
    }

    val statusText = when (systemStatus.connectionStatus) {
        ConnectionState.CONNECTED -> "CONN"
        ConnectionState.CONNECTING -> "..."
        ConnectionState.DISCONNECTED -> "DISC"
        ConnectionState.ERROR -> "ERR"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .background(
                statusColor.copy(alpha = 0.3f),
                RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(statusColor, androidx.compose.foundation.shape.CircleShape)
        )
        Text(
            text = statusText,
            color = MilitaryTextPrimary,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun VideoFeedSection(
    detectedTargets: List<DetectedTarget>,
    shootingSolution: ShootingSolution?,
    selectedTargetId: String?,
    systemStatus: SystemStatus,
    onTargetSelect: (String) -> Unit,
    onTargetLockToggle: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    TacticalVideoPlayer(
        detectedTargets = detectedTargets,
        shootingSolution = shootingSolution,
        selectedTargetId = selectedTargetId,
        connectionState = systemStatus.connectionStatus,
        onTargetClick = { target ->
            Log.d("Dashboard", "Target clicked: ${target.id}")
        },
        onTargetSelect = onTargetSelect,
        onTargetLockToggle = onTargetLockToggle,
        modifier = modifier,
        videoStreamUrl = "rtsp://192.168.7.9:8554/stream"
    )
}
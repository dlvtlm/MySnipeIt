package com.example.mysnipeit.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.example.mysnipeit.data.models.ConnectionState
import com.example.mysnipeit.data.models.DetectedTarget
import com.example.mysnipeit.data.models.SensorData
import com.example.mysnipeit.data.models.ShootingSolution
import com.example.mysnipeit.data.models.SystemStatus
import com.example.mysnipeit.data.models.distanceM
import com.example.mysnipeit.data.models.gpsLatLon
import com.example.mysnipeit.data.models.gpsSatellites
import com.example.mysnipeit.data.models.humidityPct
import com.example.mysnipeit.data.models.temperatureC
import com.example.mysnipeit.ui.theme.*

/**
 * Operator HUD — redesigned to the design's "A layout + dense sensor bar"
 * variant. ALL of the data flow stays — only the chrome around the
 * [TacticalVideoPlayer] changes.
 *
 *  - 36dp [TopBar]: "NODE-CHARLIE · LIVE" + RTSP/CONN chip + back/menu/toggle
 *  - Video fills the rest of the screen with overlays anchored to its edges:
 *      top-left  : TARGETS rail (panel)
 *      top-right : FIRING SOLUTION card (panel)
 *      bottom    : 8-cell sensor strip + UNLOCK button
 *
 * Target reticles inside the video region (corner brackets, bone for
 * tracked, copper for locked) are drawn by [TacticalVideoPlayer]; this
 * file just wraps it in the new chrome.
 */
@Composable
fun DashboardScreen(
    sensorData: SensorData?,
    detectedTargets: List<DetectedTarget>,
    shootingSolution: ShootingSolution?,
    systemStatus: SystemStatus,
    selectedTargetId: String?,
    streamReady: Boolean,
    rtspStreamUrl: String?,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onTargetSelect: (String) -> Unit = {},
    onTargetLockToggle: (String, Boolean) -> Unit = { _, _ -> },
    onBackClick: () -> Unit = {},
    onMenuClick: () -> Unit = {},
    isDarkTheme: Boolean = true,
    onToggleTheme: () -> Unit = {},
) {
    val t = LocalTactical.current
    val lockedTarget = detectedTargets.firstOrNull { it.id == selectedTargetId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(t.base),
    ) {
        TopBar(
            device = "OPERATOR · LIVE",
            onBackClick = onBackClick,
        ) {
            // RTSP / connection chip
            val (chipText, chipTone) = when (systemStatus.connectionStatus) {
                ConnectionState.CONNECTED -> "RTSP OK" to ChipTone.On
                ConnectionState.CONNECTING -> "CONNECTING" to ChipTone.Warn
                ConnectionState.DISCONNECTED -> "OFFLINE" to ChipTone.Danger
                ConnectionState.ERROR -> "ERROR" to ChipTone.Danger
            }
            Chip(text = chipText, tone = chipTone)

            systemStatus.batteryLevel?.let { bat ->
                Text(
                    text = "BAT $bat%",
                    color = if (bat > 20) t.ink else t.danger,
                    fontSize = 10.sp,
                    letterSpacing = 0.14.em,
                    fontFamily = JetBrainsMono,
                )
            }

            // Menu (kept; opens the Diagnostics shortcut dialog from MainActivity)
            TopBarIconButton(label = "MENU", onClick = onMenuClick)
            ThemeToggle(isDark = isDarkTheme, onToggle = onToggleTheme)
        }

        // Video region with overlays
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            TacticalVideoPlayer(
                detectedTargets = detectedTargets,
                shootingSolution = shootingSolution,
                selectedTargetId = selectedTargetId,
                connectionState = systemStatus.connectionStatus,
                streamReady = streamReady,
                videoStreamUrl = rtspStreamUrl,
                onTargetClick = {},
                onTargetSelect = onTargetSelect,
                onTargetLockToggle = onTargetLockToggle,
                modifier = Modifier.fillMaxSize(),
            )

            // Top-left: target list rail
            TargetListRail(
                targets = detectedTargets,
                selectedTargetId = selectedTargetId,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp),
            )

            // Top-right: firing-solution card (shown only when a target is locked)
            if (lockedTarget != null && shootingSolution != null) {
                FiringSolutionCard(
                    targetId = lockedTarget.id,
                    solution = shootingSolution,
                    rangefinder = sensorData.distanceM()?.toDouble(),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .width(280.dp),
                )
            }

            // Bottom: dense 8-cell sensor strip + UNLOCK button
            SensorStrip(
                sensorData = sensorData,
                hasLockedTarget = lockedTarget != null,
                onUnlock = {
                    lockedTarget?.let { onTargetLockToggle(it.id, false) }
                    onTargetSelect("")
                },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(),
            )
        }
    }
}

// ----------------------------------------------------------------------------
// Top bar small icon button (text-only, mono)
// ----------------------------------------------------------------------------
@Composable
private fun TopBarIconButton(label: String, onClick: () -> Unit) {
    val t = LocalTactical.current
    Box(
        modifier = Modifier
            .border(1.dp, t.line)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            color = t.ink,
            fontSize = 9.sp,
            letterSpacing = 0.18.em,
            fontFamily = JetBrainsMono,
        )
    }
}

// ----------------------------------------------------------------------------
// Target list rail (top-left)
// ----------------------------------------------------------------------------
@Composable
private fun TargetListRail(
    targets: List<DetectedTarget>,
    selectedTargetId: String?,
    modifier: Modifier = Modifier,
) {
    val t = LocalTactical.current
    Column(
        modifier = modifier
            .widthIn(min = 200.dp)
            .background(t.videoChrome)
            .border(1.dp, t.lineHi)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Lbl(text = "TARGETS · ${targets.size}")
        Spacer(Modifier.height(4.dp))
        if (targets.isEmpty()) {
            Text(
                text = "NO CONTACTS",
                color = t.inkMute,
                fontSize = 10.sp,
                letterSpacing = 0.14.em,
                fontFamily = JetBrainsMono,
            )
        } else {
            targets.take(6).forEach { target ->
                TargetRailRow(target = target, isLocked = target.id == selectedTargetId)
            }
            if (targets.size > 6) {
                Text(
                    text = "+ ${targets.size - 6} MORE",
                    color = t.inkMute,
                    fontSize = 9.sp,
                    letterSpacing = 0.18.em,
                    fontFamily = JetBrainsMono,
                )
            }
        }
    }
}

@Composable
private fun TargetRailRow(target: DetectedTarget, isLocked: Boolean) {
    val t = LocalTactical.current
    Row(
        modifier = Modifier
            .drawBehind {
                if (isLocked) {
                    drawLine(
                        color = t.accent,
                        start = Offset(0f, 0f),
                        end = Offset(0f, size.height),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
            }
            .padding(start = if (isLocked) 0.dp else 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = target.id,
            color = if (isLocked) t.accent else t.ink,
            fontSize = 13.sp,
            letterSpacing = 0.1.em,
            fontFamily = JetBrainsMono,
            modifier = Modifier.width(46.dp),
        )
        Text(
            text = target.targetType.uppercase(),
            color = t.inkDim,
            fontSize = 10.sp,
            letterSpacing = 0.12.em,
            fontFamily = JetBrainsMono,
            modifier = Modifier.width(80.dp),
        )
        Text(
            text = "${(target.confidence * 100).toInt()}%",
            color = t.ink,
            fontSize = 11.sp,
            fontFamily = JetBrainsMono,
        )
    }
}

// ----------------------------------------------------------------------------
// Firing solution card (top-right) — only shown when a target is locked
// ----------------------------------------------------------------------------
@Composable
private fun FiringSolutionCard(
    targetId: String,
    solution: ShootingSolution,
    rangefinder: Double?,
    modifier: Modifier = Modifier,
) {
    val t = LocalTactical.current
    Column(
        modifier = modifier
            .background(t.videoChrome)
            .border(1.dp, t.lineHi),
    ) {
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
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Lbl(text = "FIRING SOLUTION · $targetId")
            Chip(text = "LOCKED", tone = ChipTone.Warn)
        }
        Column(modifier = Modifier.padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Stat(
                    label = "Azimuth",
                    value = "${solution.azimuth.toInt()}°",
                    modifier = Modifier.weight(1f),
                )
                Stat(
                    label = "Elevation",
                    value = "${if (solution.elevation >= 0) "+" else ""}${solution.elevation.toInt()}°",
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Stat(
                    label = "Windage",
                    value = String.format("%.1f", solution.windageAdjustment),
                    unit = "MIL",
                    modifier = Modifier.weight(1f),
                )
                Stat(
                    label = "Drop",
                    value = String.format("%.1f", solution.elevationAdjustment),
                    unit = "MIL",
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Stat(
                    label = "Range",
                    value = rangefinder?.toInt()?.toString() ?: "—",
                    unit = "m",
                    modifier = Modifier.weight(1f),
                )
                Stat(
                    label = "Confidence",
                    value = "${(solution.confidence * 100).toInt()}%",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// ----------------------------------------------------------------------------
// Sensor strip (bottom) — 8 cells + UNLOCK
// ----------------------------------------------------------------------------
@Composable
private fun SensorStrip(
    sensorData: SensorData?,
    hasLockedTarget: Boolean,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalTactical.current
    // FIXED height: without this, cells using fillMaxHeight() inside a
    // BottomStart-aligned Box-child would expand to the Box's full height
    // (the whole video region) — which is exactly what the user reported.
    Row(
        modifier = modifier
            .height(68.dp)
            .background(t.panel)
            .drawBehind {
                drawLine(
                    color = t.line,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx(),
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Real values come from the ddl_frame nested structure via the helper
        // extensions in data/models/SensorData.kt. Each helper returns null
        // when its sub-frame is absent or its `valid` flag is false, which we
        // render as "—".
        SensorCell(
            label = "T",
            value = sensorData.temperatureC()?.let { "${it.toInt()}°C" } ?: "—",
            modifier = Modifier.weight(1f),
        )
        SensorCell(
            label = "HUM",
            value = sensorData.humidityPct()?.let { "${it.toInt()}%" } ?: "—",
            modifier = Modifier.weight(1f),
        )
        // WIND and DIR are placeholders — the Pi has no anemometer yet. The
        // cells are kept so the bottom strip still feels deliberate, and
        // they'll start populating automatically once a WindFrame is added.
        SensorCell(label = "WIND", value = "—", modifier = Modifier.weight(1f))
        SensorCell(label = "DIR",  value = "—", modifier = Modifier.weight(1f))
        // GPS — lat, lon, and (when valid) satellite count packed into one cell.
        SensorCell(
            label = "GPS",
            value = sensorData.gpsLatLon()?.let { (lat, lon) ->
                val sats = sensorData.gpsSatellites()
                if (sats != null) String.format("%.3f, %.3f · %d SAT", lat, lon, sats)
                else              String.format("%.3f, %.3f", lat, lon)
            } ?: "—",
            modifier = Modifier.weight(1.6f),
        )
        SensorCell(
            label = "LSR",
            value = sensorData.distanceM()?.let { "${it.toInt()}m" } ?: "—",
            modifier = Modifier.weight(1f),
            hideRightBorder = true,
        )
        // UNLOCK action (only enabled if there's something to unlock)
        Box(
            modifier = Modifier
                .background(t.panelHi)
                .drawBehind {
                    drawLine(
                        color = t.lineHi,
                        start = Offset(0f, 0f),
                        end = Offset(0f, size.height),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
                .clickable(enabled = hasLockedTarget, onClick = onUnlock)
                .padding(horizontal = 28.dp, vertical = 14.dp),
        ) {
            Text(
                text = "UNLOCK",
                color = if (hasLockedTarget) t.ink else t.inkMute,
                fontSize = 11.sp,
                letterSpacing = 0.2.em,
                fontFamily = JetBrainsMono,
            )
        }
    }
}

@Composable
private fun SensorCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    hideRightBorder: Boolean = false,
) {
    val t = LocalTactical.current
    Column(
        modifier = modifier
            .fillMaxHeight()  // OK now — parent Row has a fixed 68dp height
            .drawBehind {
                if (!hideRightBorder) {
                    drawLine(
                        color = t.line,
                        start = Offset(size.width, 0f),
                        end = Offset(size.width, size.height),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Lbl(text = label)
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            color = t.ink,
            fontSize = 13.sp,
            letterSpacing = 0.06.em,
            fontFamily = JetBrainsMono,
        )
    }
}

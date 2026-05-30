package com.example.mysnipeit.ui.dashboard

import android.content.Context
import android.util.Log
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import com.example.mysnipeit.R
import com.example.mysnipeit.data.models.DetectedTarget
import com.example.mysnipeit.data.models.ShootingSolution
import com.example.mysnipeit.ui.components.TacticalCompass
import com.example.mysnipeit.ui.theme.*
import kotlinx.coroutines.delay
import com.example.mysnipeit.data.models.ConnectionState


// Video resolution constants (hardcoded for POC)
private const val VIDEO_WIDTH = 1920f
private const val VIDEO_HEIGHT = 1080f

@Composable
fun TacticalVideoPlayer(
    detectedTargets: List<DetectedTarget>,
    shootingSolution: ShootingSolution?,
    selectedTargetId: String?,
    connectionState: ConnectionState,
    streamReady: Boolean,
    videoStreamUrl: String?,
    onTargetClick: (DetectedTarget) -> Unit = {},
    onTargetLockToggle: (String, Boolean) -> Unit = { _, _ -> },
    onTargetSelect: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val exoPlayer = remember { createExoPlayer(context) }

    var scanlinePosition by remember { mutableStateOf(0f) }
    var videoTime by remember { mutableStateOf(0L) }

    // Track locked target (only one at a time)
    var lockedTargetId by remember { mutableStateOf<String?>(null) }

    // Load stream only when both connected AND stream is ready
    LaunchedEffect(connectionState, streamReady, videoStreamUrl) {
        if (connectionState == ConnectionState.CONNECTED &&
            streamReady &&
            videoStreamUrl != null) {

            Log.d("TacticalVideoPlayer", "Stream ready signal received, loading: $videoStreamUrl")
            // Force RTP-over-TCP to match the Pi's `-rtsp_transport tcp` and avoid
            // UDP packet loss / firewall issues on the AP network.
            val mediaSource = RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .setTimeoutMs(8000)
                .createMediaSource(MediaItem.fromUri(videoStreamUrl))
            exoPlayer.setMediaSource(mediaSource)
            exoPlayer.playWhenReady = true
            exoPlayer.prepare()

        } else {
            if (!streamReady) {
                Log.d("TacticalVideoPlayer", "Waiting for stream_ready signal from server...")
            }
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
        }
    }

    // Animate scanning line
    LaunchedEffect(Unit) {
        while (true) {
            scanlinePosition = 0f
            while (scanlinePosition < 1f) {
                scanlinePosition += 0.002f
                delay(16) // ~60 FPS
            }
            delay(1000)
        }
    }

    // Track video playback time
    LaunchedEffect(Unit) {
        while (true) {
            videoTime = exoPlayer.currentPosition
            delay(100)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        // Constrain the video + overlay region to the source 16:9 aspect ratio.
        // This keeps detection bbox coordinates aligned with the rendered video
        // even on tablets that aren't exactly 16:9 (no pillarbox/letterbox math
        // needed in EnhancedTargetMarker).
        Box(
            modifier = Modifier
                .aspectRatio(VIDEO_WIDTH / VIDEO_HEIGHT)
                .background(Color.Black)
        ) {
            // Video player
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Tactical overlays
            Box(modifier = Modifier.fillMaxSize()) {
            // Crosshair
           // CrosshairOverlay()

            // Scanning line
           // ScanLineOverlay(scanlinePosition)

            // Target markers
            //SimulatedTargets(videoTime).forEach { target ->
            detectedTargets.forEach { target ->
                // key(target.id) ensures Compose preserves the same composable
                // instance (and its animation state) for the same target across
                // recompositions. Without it, position-based identity would
                // shuffle when the target list reorders, breaking interpolation.
                key(target.id) {
                    val isLocked = lockedTargetId == target.id
                    val isSelected = target.id == selectedTargetId

                    EnhancedTargetMarker(
                        target = target,
                        isLocked = isLocked,
                        isSelected = isSelected,
                        onLockClick = {
                            if (isLocked) {
                                // Unlock current target
                                lockedTargetId = null
                                onTargetSelect("")  // Clear selection
                                onTargetLockToggle(target.id, false)  // Send unlock command
                            } else {
                                // Unlock previous target if any
                                lockedTargetId?.let { prevTargetId ->
                                    onTargetLockToggle(prevTargetId, false)  // Send unlock command for previous
                                }
                                // Lock this target
                                lockedTargetId = target.id
                                // Immediately select and show shooting solution
                                onTargetSelect(target.id)
                                onTargetLockToggle(target.id, true)  // Send lock command
                            }
                        },
                        onTargetClick = {
                            // Optional: Allow clicking locked target to select/deselect
                            if (isLocked) {
                                if (isSelected) {
                                    onTargetSelect("")  // Deselect
                                } else {
                                    onTargetSelect(target.id)  // Select
                                }
                            }
                        }
                    )
                }
            }

            // Video status
            VideoStatusOverlay(
                modifier = Modifier.align(Alignment.TopStart)
            )

            // Scanning/Tracking mode indicator
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                val hasLockedTarget = lockedTargetId != null
                val hasSelectedTarget = selectedTargetId != null

                Text(
                    text = when {
                        hasSelectedTarget -> "SOLUTION ACTIVE"
                        hasLockedTarget -> "TARGET LOCKED"
                        else -> "SCANNING"
                    },
                    color = when {
                        hasSelectedTarget -> Color(0xFFFF6B35)
                        hasLockedTarget -> Color(0xFFFFAA00)
                        else -> Color(0xFF038C16)
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            //   3D Tactical Compass (bottom-left)
            if (shootingSolution != null && selectedTargetId != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp)
                ) {
                    TacticalCompass(
                        azimuth = shootingSolution.azimuth,
                        elevation = shootingSolution.elevation,
                        confidence = shootingSolution.confidence
                    )
                }
            }
            } // end overlays Box
        } // end aspect-ratio video Box
    } // end outer black Box
}

private fun createExoPlayer(context: Context): ExoPlayer {
    // Low-latency LoadControl tuned for live RTSP. ExoPlayer's defaults are
    // VOD-oriented (~50s buffer) which adds 1-3s of perceived lag. These
    // values keep us under ~1s of buffered data.
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs = */              250,
            /* maxBufferMs = */              1000,
            /* bufferForPlaybackMs = */      100,
            /* bufferForPlaybackAfterRebufferMs = */ 250
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

    return ExoPlayer.Builder(context)
        .setLoadControl(loadControl)
        .build().apply {
            repeatMode = Player.REPEAT_MODE_ALL

            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    android.util.Log.e("ExoPlayer", "Playback error: ${error.message}", error)
                }
            })
        }
}

@Composable
private fun SimulatedTargets(videoTime: Long): List<DetectedTarget> {
    val targets = mutableListOf<DetectedTarget>()

    if (videoTime > 2000) {
        targets.add(
            DetectedTarget(
                id = "T1",
                targetType = "HUMAN",
                confidence = 0.85f,
                bbox = com.example.mysnipeit.data.models.BoundingBox(
                    x = 576,      // ~30% of 1920
                    y = 432,      // ~40% of 1080
                    width = 150,
                    height = 250
                )
            )
        )
    }

    if (videoTime > 8000) {
        targets.add(
            DetectedTarget(
                id = "T2",
                targetType = "UNKNOWN",
                confidence = 0.72f,
                bbox = com.example.mysnipeit.data.models.BoundingBox(
                    x = 1344,     // ~70% of 1920
                    y = 540,      // ~50% of 1080
                    width = 200,
                    height = 180
                )
            )
        )
    }

    if (videoTime > 15000 && videoTime < 25000) {
        targets.add(
            DetectedTarget(
                id = "T3",
                targetType = "HUMAN",
                confidence = 0.91f,
                bbox = com.example.mysnipeit.data.models.BoundingBox(
                    x = 960,      // ~50% of 1920 (center)
                    y = 486,      // ~45% of 1080
                    width = 120,
                    height = 220
                )
            )
        )
    }

    return targets
}

@Composable
private fun EnhancedTargetMarker(
    target: DetectedTarget,
    isLocked: Boolean,
    isSelected: Boolean,
    onLockClick: () -> Unit,
    onTargetClick: () -> Unit
) {
    // Tactical palette — see ui/theme/Color.kt.
    // Bone for tracked targets, copper for the selected/locked target.
    // No saturated greens or cyans (the redesign brief).
    val palette = LocalTactical.current
    // High-visibility bbox colors over the live camera feed:
    //   regular tracking → bright orange
    //   locked target    → vivid red (high-alert)
    // Values are identical in light + dark modes because the video region is
    // always dark (real camera feed); contrast is against the video, not the
    // surrounding UI.
    val markerColor = when {
        isLocked -> palette.bboxLocked
        else     -> palette.bboxTracked
    }

    var pulseAlpha by remember { mutableStateOf(1f) }

    LaunchedEffect(target.id, isLocked, isSelected) {
        if (!isLocked && !isSelected) {
            while (true) {
                pulseAlpha = 0.4f
                delay(600)
                pulseAlpha = 1f
                delay(600)
            }
        } else {
            pulseAlpha = 1f
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Convert bbox pixel coordinates (relative to 1920x1080) to local Dp.
        // The parent BoxWithConstraints is the 16:9 video region (set up in the
        // outer aspect-ratio Box), so these ratios map 1:1 to rendered video pixels.
        val targetX = maxWidth  * (target.bbox.x.toFloat()      / VIDEO_WIDTH)
        val targetY = maxHeight * (target.bbox.y.toFloat()      / VIDEO_HEIGHT)
        val targetW = maxWidth  * (target.bbox.width.toFloat()  / VIDEO_WIDTH)
        val targetH = maxHeight * (target.bbox.height.toFloat() / VIDEO_HEIGHT)

        // Smooth interpolation between detection updates (~167ms apart at 6Hz).
        // Each new detection becomes the new "target" of the tween; Compose
        // animates from the current rendered position/size to the new value over
        // one detection interval. Net effect: the bbox glides to follow people
        // and shrinks smoothly as they walk away, instead of snapping at 6Hz.
        // Linear easing matches constant motion of moving targets.
        val animSpec = tween<androidx.compose.ui.unit.Dp>(
            durationMillis = 167,
            easing = LinearEasing
        )
        val xPos      by animateDpAsState(targetValue = targetX, animationSpec = animSpec, label = "x")
        val yPos      by animateDpAsState(targetValue = targetY, animationSpec = animSpec, label = "y")
        val boxWidth  by animateDpAsState(targetValue = targetW, animationSpec = animSpec, label = "w")
        val boxHeight by animateDpAsState(targetValue = targetH, animationSpec = animSpec, label = "h")

        // Smart card placement: if there isn't enough room below the bbox for
        // the info card, render it ABOVE the bbox instead. Prevents the card
        // from being pushed off the bottom of the video for tall bboxes (e.g.
        // a person filling most of the frame).
        val cardHeight = 72.dp
        val placeCardAbove = (targetY + targetH + cardHeight + 6.dp) > maxHeight

        Box(
            modifier = Modifier
                .offset(x = xPos, y = yPos)
                .size(width = boxWidth, height = boxHeight)
                .clickable { onTargetClick() }
        ) {
            // Target rectangle that fills the actual bbox area
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val strokeWidth = when {
                    isSelected -> 5f
                    isLocked   -> 4f
                    else       -> 3f
                }

                // Main bounding rectangle
                drawRect(
                    color = markerColor.copy(alpha = pulseAlpha),
                    topLeft = Offset(0f, 0f),
                    size = Size(w, h),
                    style = Stroke(width = strokeWidth)
                )

                // Pulsing outer glow for selected target
                if (isSelected) {
                    drawRect(
                        color = markerColor.copy(alpha = 0.3f),
                        topLeft = Offset(-4f, -4f),
                        size = Size(w + 8, h + 8),
                        style = Stroke(width = 2f)
                    )
                }

                // Corner brackets, scaled to ~20% of the smaller bbox side
                val bracketSize = (minOf(w, h) * 0.2f).coerceAtMost(24f)
                val corners = listOf(
                    Offset(0f, 0f),                       // top-left
                    Offset(w - bracketSize, 0f),          // top-right
                    Offset(0f, h - bracketSize),          // bottom-left
                    Offset(w - bracketSize, h - bracketSize) // bottom-right
                )
                corners.forEach { pos ->
                    drawLine(markerColor, pos, Offset(pos.x + bracketSize, pos.y), strokeWidth)
                    drawLine(markerColor, pos, Offset(pos.x, pos.y + bracketSize), strokeWidth)
                }

                // Center crosshair, scaled to ~10% of the smaller side
                val cx = w / 2f
                val cy = h / 2f
                val crosshair = (minOf(w, h) * 0.1f).coerceAtMost(14f)
                drawLine(markerColor, Offset(cx - crosshair, cy), Offset(cx + crosshair, cy), 2f)
                drawLine(markerColor, Offset(cx, cy - crosshair), Offset(cx, cy + crosshair), 2f)

                // Lock indicator (top-right inside the bbox)
                if (isLocked) {
                    val lockSize = 12f
                    val lockX = w - lockSize - 8f
                    val lockY = 8f
                    drawRect(
                        color = markerColor,
                        topLeft = Offset(lockX, lockY + lockSize * 0.4f),
                        size = Size(lockSize, lockSize * 0.6f),
                        style = Stroke(width = 2f)
                    )
                    drawArc(
                        color = markerColor,
                        startAngle = 180f,
                        sweepAngle = 180f,
                        useCenter = false,
                        topLeft = Offset(lockX + lockSize * 0.2f, lockY),
                        size = Size(lockSize * 0.6f, lockSize * 0.6f),
                        style = Stroke(width = 2f)
                    )
                }

                // Selection indicator (asterisk top-left inside the bbox)
                if (isSelected) {
                    val starSize = 8f
                    val starX = 8f + starSize
                    val starY = 8f + starSize
                    drawLine(markerColor, Offset(starX - starSize, starY),
                             Offset(starX + starSize, starY), 3f)
                    drawLine(markerColor, Offset(starX, starY - starSize),
                             Offset(starX, starY + starSize), 3f)
                }
            }

            // Minimal info card: just "T1 | HUMAN" + LOCK button. Placed below
            // the bbox by default, or above it when the bbox is near the bottom
            // of the video (so the card doesn't get pushed off-screen).
            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(
                        y = if (placeCardAbove) -(cardHeight + 6.dp)
                            else boxHeight + 6.dp
                    ),
                colors = CardDefaults.cardColors(
                    containerColor = Color.Black.copy(alpha = 0.85f)
                ),
                shape = RoundedCornerShape(6.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "${target.id} | ${target.targetType}",
                        color = markerColor,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "CONF: ${(target.confidence * 100).toInt()}%",
                        color = when {
                            target.confidence > 0.8f -> Color(0xFF038C16)
                            target.confidence > 0.6f -> Color(0xFFFFAA00)
                            else                     -> Color(0xFFFF4444)
                        },
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Button(
                        onClick = onLockClick,
                        modifier = Modifier
                            .height(26.dp)
                            .widthIn(min = 70.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isLocked) Color(0xFFFFAA00)
                                             else Color(0xFF038C16)
                        ),
                        shape = RoundedCornerShape(4.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (isLocked) "UNLOCK" else "LOCK",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

// Keep existing CrosshairOverlay, ScanLineOverlay, and VideoStatusOverlay functions unchanged
@Composable
private fun CrosshairOverlay() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val centerX = size.width / 2
        val centerY = size.height / 2
        val crosshairSize = 40f
        val color = Color(0xFF00FF41)

        drawLine(
            color = color.copy(alpha = 0.7f),
            start = Offset(centerX - crosshairSize, centerY),
            end = Offset(centerX + crosshairSize, centerY),
            strokeWidth = 2f
        )
        drawLine(
            color = color.copy(alpha = 0.7f),
            start = Offset(centerX, centerY - crosshairSize),
            end = Offset(centerX, centerY + crosshairSize),
            strokeWidth = 2f
        )

        drawCircle(
            color = color,
            radius = 3f,
            center = Offset(centerX, centerY)
        )

        for (i in 1..3) {
            drawCircle(
                color = color.copy(alpha = 0.3f),
                radius = crosshairSize * i,
                center = Offset(centerX, centerY),
                style = Stroke(
                    width = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f))
                )
            )
        }
    }
}

@Composable
private fun ScanLineOverlay(position: Float) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val y = size.height * position
        val color = Color(0xFF00FF41)

        drawLine(
            color = color.copy(alpha = 0.5f),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 2f
        )

        val fadeHeight = 80f
        for (i in 0 until fadeHeight.toInt() step 2) {
            val alpha = (1f - (i / fadeHeight)) * 0.2f
            drawLine(
                color = color.copy(alpha = alpha),
                start = Offset(0f, y + i),
                end = Offset(size.width, y + i),
                strokeWidth = 1f
            )
        }
    }
}

@Composable
private fun VideoStatusOverlay(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .padding(16.dp)
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
            .padding(8.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(StatusConnected, CircleShape)
            )
            Text(
                text = "LIVE",
                color = StatusConnected,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }

        Text(
            text = "1920x1080",
            color = MilitaryTextSecondary,
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
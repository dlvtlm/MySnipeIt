package com.example.mysnipeit.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.example.mysnipeit.ui.theme.Bracket
import com.example.mysnipeit.ui.theme.JetBrainsMono
import com.example.mysnipeit.ui.theme.Lbl
import com.example.mysnipeit.ui.theme.LocalTactical
import com.example.mysnipeit.ui.theme.ThemeToggle
import com.example.mysnipeit.ui.theme.TopBar
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Landing screen — "centered terminal" variation (design B).
 *
 * Layout (1280×800 reference):
 *   - 36dp [TopBar] with brand + STANDBY + clock + theme toggle
 *   - Top-left:  small bullet list of system-ready status
 *   - Top-right: deliberately left empty (the design's mock op-id / callsign
 *                aren't real app data — see chat decision E)
 *   - Center:    "// SECURE TERMINAL", giant SNIPEIT wordmark,
 *                letter-spaced subtitle, three bracket buttons
 *   - Bottom:    "AUTHORIZED PERSONNEL ONLY · UNCLASSIFIED//FOUO"
 */
@Composable
fun HomeScreen(
    onDeviceListClick: () -> Unit,
    onMapClick: () -> Unit,
    onDiagnosticsClick: () -> Unit = {},
    isDarkTheme: Boolean = true,
    onToggleTheme: () -> Unit = {},
) {
    val t = LocalTactical.current
    val clock = useZuluClock()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(t.base),
    ) {
        TopBar(device = "STANDBY") {
            Text(
                text = clock,
                color = t.ink,
                fontSize = 10.sp,
                letterSpacing = 0.14.em,
                fontFamily = JetBrainsMono,
            )
            ThemeToggle(isDark = isDarkTheme, onToggle = onToggleTheme)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp)
        ) {
            // Top-left: terminal status list
            Column(
                modifier = Modifier.align(Alignment.TopStart),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusBullet(text = "TERMINAL READY")
                StatusBullet(text = "4 NODES DETECTED")
                StatusBullet(text = "MESH HEALTHY")
            }

            // Centered terminal: SECURE TERMINAL → SNIPEIT → subtitle → buttons
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "// SECURE TERMINAL",
                    color = t.inkDim,
                    fontSize = 11.sp,
                    letterSpacing = 0.4.em,
                    fontFamily = JetBrainsMono,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "SNIPEIT",
                    color = t.ink,
                    fontSize = 88.sp,
                    lineHeight = 88.sp,
                    letterSpacing = 0.02.em,
                    fontFamily = JetBrainsMono,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                // Letter-spaced subtitle — each character rendered separately
                // so the spacing matches the design's wide tracking.
                Row {
                    "SMART SPOTTER SYSTEM".forEach { ch ->
                        Box(
                            modifier = Modifier.width(14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = ch.toString(),
                                color = t.inkDim,
                                fontSize = 12.sp,
                                fontFamily = JetBrainsMono,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(64.dp))

                // Three bracket buttons, side by side
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    BracketButton(
                        number = "01",
                        title = "DEVICE LIST",
                        primary = true,
                        onClick = onDeviceListClick,
                    )
                    BracketButton(
                        number = "02",
                        title = "MAP VIEW",
                        primary = true,
                        onClick = onMapClick,
                    )
                    BracketButton(
                        number = "03",
                        title = "DIAGNOSTICS",
                        primary = false,
                        onClick = onDiagnosticsClick,
                    )
                }
            }

            // Bottom footer
            Text(
                text = "AUTHORIZED PERSONNEL ONLY · UNCLASSIFIED//FOUO",
                color = t.inkMute,
                fontSize = 9.sp,
                letterSpacing = 0.3.em,
                fontFamily = JetBrainsMono,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun StatusBullet(text: String) {
    val t = LocalTactical.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "●",
            color = t.on,
            fontSize = 10.sp,
            fontFamily = JetBrainsMono,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            color = t.inkDim,
            fontSize = 10.sp,
            letterSpacing = 0.2.em,
            fontFamily = JetBrainsMono,
        )
    }
}

@Composable
private fun BracketButton(
    number: String,
    title: String,
    primary: Boolean,
    onClick: () -> Unit,
) {
    val t = LocalTactical.current
    Bracket(
        modifier = Modifier
            .size(width = 200.dp, height = 90.dp)
            .clickable(onClick = onClick),
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Lbl(text = number)
            Spacer(Modifier.height(6.dp))
            Text(
                text = title,
                color = if (primary) t.ink else t.inkDim,
                fontSize = 16.sp,
                letterSpacing = 0.14.em,
                fontFamily = JetBrainsMono,
            )
        }
    }
}

/** Updates every second with Zulu time (HH:mm:ssZ). */
@Composable
private fun useZuluClock(): String {
    var now by remember { mutableStateOf(formatZulu()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = formatZulu()
            delay(1000L)
        }
    }
    return now
}

private fun formatZulu(): String =
    SimpleDateFormat("HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date()) + "Z"

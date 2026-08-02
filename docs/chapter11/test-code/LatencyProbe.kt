package com.example.mysnipeit.data.network

import android.util.Log
import java.util.Locale

/**
 * TEMPORARY instrumentation for Chapter 11, measurement B2.
 *
 * Measures the elapsed time between a sensor frame being parsed off the
 * WebSocket and a new firing solution being emitted by the ViewModel.
 * That interval is the application's share of the "firing angle within
 * two seconds" non-functional requirement.
 *
 * ---------------------------------------------------------------------
 * HOW TO INSTALL
 *
 * 1. Copy this file to:
 *      app/src/main/java/com/example/mysnipeit/data/network/LatencyProbe.kt
 *
 * 2. In RaspberryPiClient.kt, in the "sensor_data" branch (around line
 *    256), add ONE line:
 *
 *        "sensor_data" -> {
 *            val data = gson.fromJson(message, SensorData::class.java)
 *            LatencyProbe.markSensorParsed()          // <-- add
 *            _sensorData.value = data
 *        }
 *
 * 3. In SniperViewModel.kt, in the firingSolution declaration (around
 *    line 339), wrap the computed value:
 *
 *        ) { sensor, sniperLatLng, sniperAlt, cart, rifle ->
 *            computeFiringSolution(sensor, sniperLatLng, sniperAlt, cart, rifle)
 *                .also { com.example.mysnipeit.data.network.LatencyProbe.markSolutionEmitted() }
 *        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
 *
 * 4. Run the app, enable MOCK MODE, and collect with:
 *
 *        adb logcat -c
 *        adb logcat -s SnipeItLatency > B2_latency_raw.txt
 *
 * 5. Analyse with B2_analyze_latency.ps1 (Windows) or .sh (Linux/macOS).
 *
 * 6. REMOVE when done:
 *
 *        git checkout -- app/src/main/java/com/example/mysnipeit/data/network/RaspberryPiClient.kt
 *        git checkout -- app/src/main/java/com/example/mysnipeit/viewmodel/SniperViewModel.kt
 *        del app\src\main\java\com\example\mysnipeit\data\network\LatencyProbe.kt
 * ---------------------------------------------------------------------
 *
 * Deliberately a plain object with volatile fields. The two call sites run
 * on different dispatchers and the measurement only needs last-write-wins,
 * so a lock would add contention without adding accuracy.
 */
object LatencyProbe {

    private const val TAG = "SnipeItLatency"

    @Volatile
    private var sensorParsedAtNs: Long = 0L

    @Volatile
    private var sampleIndex: Int = 0

    /** Call the moment a sensor frame finishes parsing. */
    fun markSensorParsed() {
        sensorParsedAtNs = System.nanoTime()
    }

    /** Call the moment a new firing solution is produced. */
    fun markSolutionEmitted() {
        val start = sensorParsedAtNs
        if (start == 0L) return
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
        // Locale.US so the decimal separator is always a dot, whatever the
        // device locale is. The analysis scripts parse on that assumption.
        Log.i(TAG, String.format(Locale.US, "sample=%d latency_ms=%.3f", ++sampleIndex, elapsedMs))
    }
}

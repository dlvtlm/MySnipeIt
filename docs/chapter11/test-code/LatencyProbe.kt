package com.example.mysnipeit.data.network

import android.util.Log
import java.util.Locale

/**
 * TEMPORARY instrumentation for Chapter 11, measurement B2.
 *
 * Measures the elapsed time between a sensor frame being produced and a
 * new firing solution being emitted by the ViewModel. That interval is
 * the application's share of the "firing angle within two seconds"
 * non-functional requirement.
 *
 * ---------------------------------------------------------------------
 * HOW TO INSTALL — THREE call sites, not two.
 *
 * 1. Copy this file to:
 *      app/src/main/java/com/example/mysnipeit/data/network/LatencyProbe.kt
 *
 * 2. RaspberryPiClient.kt, the "sensor_data" branch (around line 256).
 *    This is the REAL Pi path:
 *
 *        "sensor_data" -> {
 *            val data = gson.fromJson(message, SensorData::class.java)
 *            LatencyProbe.markSensorParsed()          // <-- add
 *            _sensorData.value = data
 *        }
 *
 * 3. RaspberryPiClient.kt, inside startMockDataGeneration (around line
 *    490), immediately BEFORE the assignment. This is the MOCK path, and
 *    it is the one B2 actually runs on:
 *
 *        LatencyProbe.markSensorParsed()              // <-- add
 *        _sensorData.value = SensorData(
 *            type = "sensor_data",
 *            ...
 *
 *    Skipping this one is why the log stays empty in MOCK MODE: the mock
 *    generator assigns _sensorData directly and never reaches the
 *    "sensor_data" branch in step 2.
 *
 * 4. SniperViewModel.kt, in the firingSolution declaration (around line
 *    339), wrap the computed value:
 *
 *        ) { sensor, sniperLatLng, sniperAlt, cart, rifle ->
 *            computeFiringSolution(sensor, sniperLatLng, sniperAlt, cart, rifle)
 *                .also { com.example.mysnipeit.data.network.LatencyProbe.markSolutionEmitted() }
 *        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
 *
 * 5. Run the app, enable MOCK MODE, and collect the log — either from the
 *    Logcat tool window filtered on `tag:SnipeItLatency`, or with adb.
 *    See INSTRUMENTATION_PATCHES.md for both.
 *
 * 6. Analyse with B2_analyze_latency.ps1 (Windows) or .sh (Linux/macOS).
 *
 * 7. REMOVE when done:
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

    @Volatile
    private var warnedMissingStart: Boolean = false

    /** Call the moment a sensor frame is produced, on BOTH paths. */
    fun markSensorParsed() {
        sensorParsedAtNs = System.nanoTime()
    }

    /** Call the moment a new firing solution is produced. */
    fun markSolutionEmitted() {
        val start = sensorParsedAtNs
        if (start == 0L) {
            // Without this the probe fails silently and the log simply
            // stays empty, which is indistinguishable from "no solutions
            // are being produced". Says so once, loudly, under the same
            // tag so it shows up in the same filter.
            if (!warnedMissingStart) {
                warnedMissingStart = true
                Log.w(
                    TAG,
                    "markSolutionEmitted() ran but markSensorParsed() never did. " +
                        "Install step 3 is missing: add the call inside " +
                        "startMockDataGeneration(), not only in the \"sensor_data\" branch."
                )
            }
            return
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
        // Locale.US so the decimal separator is always a dot, whatever the
        // device locale is. The analysis scripts parse on that assumption.
        Log.i(TAG, String.format(Locale.US, "sample=%d latency_ms=%.3f", ++sampleIndex, elapsedMs))
    }
}

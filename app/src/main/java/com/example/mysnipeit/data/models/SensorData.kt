package com.example.mysnipeit.data.models

import com.google.gson.annotations.SerializedName

/**
 * Sensor data from the RPi5 sensor pipeline.
 *
 * Mirrors the nested `ddl_frame` JSON the Pi sends:
 *
 * ```
 * {
 *   "type": "sensor_data",
 *   "timestamp": 1748600000000,
 *   "ddl_frame": {
 *     "distance":             { "valid": ..., "distance_m": ..., "status": ..., "precision": ..., "strength": ... },
 *     "temperature_humidity": { "valid": ..., "temperature_c": ..., "humidity_pct": ... },
 *     "servo":                { "horizontal_deg": ..., "vertical_deg": ... },
 *     "gps":                  { "valid": ..., "fix_type": ..., "num_satellites": ..., "latitude_deg": ..., "longitude_deg": ..., "altitude_m": ..., "h_acc_m": ... }
 *   }
 * }
 * ```
 *
 * Display rule: a sub-frame's data is shown on the dashboard only if the
 * sub-frame is present AND its `valid` flag is true. [ServoFrame] has no
 * `valid` field (matches the C struct on the Pi); it's treated as valid
 * whenever its sub-frame is present.
 *
 * Fields the Pi may send but that we don't yet display (e.g. distance
 * precision/strength, GPS altitude/h_acc) are still parsed into these
 * classes so they're available when the app starts computing its own
 * shooting solution.
 */
data class SensorData(
    val type: String? = null,
    val timestamp: Long = 0L,
    @SerializedName("ddl_frame") val ddlFrame: DdlFrame? = null,
)

/**
 * Container for the four current sensor sub-frames. Each sub-frame is
 * nullable so the Pi can omit a subsystem that's offline / not yet
 * initialized without breaking the parse. Leaf fields inside each
 * sub-frame are non-null per the C-struct contract on the Pi.
 */
data class DdlFrame(
    val distance: DistanceFrame? = null,
    @SerializedName("temperature_humidity") val temperatureHumidity: TempHumidityFrame? = null,
    val servo: ServoFrame? = null,
    val gps: GpsFrame? = null,
)

/** Laser rangefinder. `valid` gates whether `distanceM` is displayed. */
data class DistanceFrame(
    val valid: Boolean = false,
    @SerializedName("distance_m") val distanceM: Float = 0f,
    val status: Int = 0,
    val precision: Int = 0,
    val strength: Int = 0,
)

/** Environmental temperature + humidity. */
data class TempHumidityFrame(
    val valid: Boolean = false,
    @SerializedName("temperature_c") val temperatureC: Float = 0f,
    @SerializedName("humidity_pct") val humidityPct: Float = 0f,
)

/**
 * Camera mount servo angles. Has NO `valid` field on the Pi side — when
 * this sub-frame is present, both angles are real readings.
 */
data class ServoFrame(
    @SerializedName("horizontal_deg") val horizontalDeg: Float = 0f,
    @SerializedName("vertical_deg") val verticalDeg: Float = 0f,
)

/** GPS position + fix quality. `valid` gates whether the position is shown. */
data class GpsFrame(
    val valid: Boolean = false,
    @SerializedName("fix_type") val fixType: Int = 0,
    @SerializedName("num_satellites") val numSatellites: Int = 0,
    @SerializedName("latitude_deg") val latitudeDeg: Double = 0.0,
    @SerializedName("longitude_deg") val longitudeDeg: Double = 0.0,
    @SerializedName("altitude_m") val altitudeM: Double = 0.0,
    @SerializedName("h_acc_m") val hAccM: Double = 0.0,
)

// ---------------------------------------------------------------------------
// Extension helpers — keep dashboard code clean. Each returns the field's
// value only if its sub-frame is present AND valid (or just present for
// the servo case which has no valid flag). Returning null means "don't
// display", and the dashboard renders that as "—".
// ---------------------------------------------------------------------------

fun SensorData?.temperatureC(): Float? =
    this?.ddlFrame?.temperatureHumidity?.takeIf { it.valid }?.temperatureC

fun SensorData?.humidityPct(): Float? =
    this?.ddlFrame?.temperatureHumidity?.takeIf { it.valid }?.humidityPct

fun SensorData?.distanceM(): Float? =
    this?.ddlFrame?.distance?.takeIf { it.valid }?.distanceM

fun SensorData?.gpsLatLon(): Pair<Double, Double>? =
    this?.ddlFrame?.gps?.takeIf { it.valid }?.let { it.latitudeDeg to it.longitudeDeg }

fun SensorData?.gpsSatellites(): Int? =
    this?.ddlFrame?.gps?.takeIf { it.valid }?.numSatellites

// Servo angles — used by the future ballistics calculation, not displayed
// on the dashboard. No `valid` check because the C struct doesn't have one.
fun SensorData?.servoHorizontalDeg(): Float? =
    this?.ddlFrame?.servo?.horizontalDeg

fun SensorData?.servoVerticalDeg(): Float? =
    this?.ddlFrame?.servo?.verticalDeg

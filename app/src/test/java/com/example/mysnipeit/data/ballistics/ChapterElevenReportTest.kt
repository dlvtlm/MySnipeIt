package com.example.mysnipeit.data.ballistics

import com.example.mysnipeit.data.models.BallisticProfiles
import com.example.mysnipeit.data.models.GpsFrame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Report-generating tests for Chapter 11 of the project book.
 *
 * These are NOT ordinary regression tests. They exist to print two
 * tables that the report needs as measured evidence, and they assert
 * only the pass criteria the book's Chapter 10 test plan defines:
 *
 *  - [geometryValidationTable]  → Table 11.1, Chapter 10 "firing angle" stage 1.
 *    Ten cases, hand-computed angles vs. solver output, tolerance 2 deg.
 *
 *  - [sensorSensitivityTable]   → Table 11.3, Chapter 10 "firing angle" stage 2.
 *    One baseline plus twenty single-variable perturbations, showing how
 *    each environmental input moves the solution.
 *
 * Run them with:
 *
 *   ./gradlew :app:testDebugUnitTest --tests '*ChapterElevenReportTest*' -i
 *
 * The `-i` (info) flag is what makes Gradle forward stdout to the console.
 * See docs/chapter11/TEST_PLAN_CHAPTER11.md, tests A2 and A3, for the full
 * procedure and for where each table goes in the chapter.
 *
 * On the independence of the expected values: the expected azimuth and
 * look angle are computed here with SPHERICAL formulas (haversine distance
 * and the standard initial-bearing formula), which are not the formulas the
 * solver uses internally. The comparison therefore cross-checks the whole
 * pipeline — rig geometry, geodetic projection and the sniper-side recompute
 * — against textbook geometry, rather than against itself. It does not
 * validate the ballistic model; that is what the external-calculator
 * comparison in test A3-EXT is for.
 */
class ChapterElevenReportTest {

    // -----------------------------------------------------------------------
    // Shared fixtures
    // -----------------------------------------------------------------------

    private val m80 = BallisticProfiles.cartridgeById("762x51_m80")
    private val m24 = BallisticProfiles.rifleById("m24_sws")

    /** Tolerance for stage 1, as defined in the book's Chapter 10. */
    private val geometryToleranceDeg = 2.0

    private fun piGpsAt(lat: Double, lon: Double, alt: Double) = GpsFrame(
        valid = true,
        fixType = 3,
        numSatellites = 9,
        latitudeDeg = lat,
        longitudeDeg = lon,
        altitudeM = alt,
    )

    // -----------------------------------------------------------------------
    // Independent (spherical) reference geometry — deliberately NOT the
    // formulas used inside the solver.
    // -----------------------------------------------------------------------

    private val earthRadiusM = 6_371_000.0

    /** Great-circle ground distance, haversine. */
    private fun referenceGroundDistanceM(
        lat1: Double, lon1: Double, lat2: Double, lon2: Double,
    ): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) +
            cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * earthRadiusM * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial great-circle bearing, degrees true, normalised to 0..360. */
    private fun referenceBearingDeg(
        lat1: Double, lon1: Double, lat2: Double, lon2: Double,
    ): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Angle from the observer's horizon up to the target. */
    private fun referenceLookAngleDeg(groundDistanceM: Double, deltaAltM: Double): Double =
        Math.toDegrees(atan2(deltaAltM, groundDistanceM))

    private fun angularDiffDeg(a: Double, b: Double): Double {
        var d = abs(a - b) % 360.0
        if (d > 180.0) d = 360.0 - d
        return d
    }

    // -----------------------------------------------------------------------
    // A2 — Table 11.1: geometric validation, ten cases
    // -----------------------------------------------------------------------

    /**
     * One row of the geometry table. The sniper sits at a stated offset from
     * the Pi so that the two-stage pipeline is genuinely exercised: the Pi's
     * own bearing and range are NOT the sniper's.
     */
    private data class GeometryCase(
        val label: String,
        val piLat: Double,
        val piLon: Double,
        val piAltM: Double,
        val headingDeg: Float,
        val servoVerticalDeg: Float,
        val rangeM: Float,
        val sniperLat: Double,
        val sniperLon: Double,
        val sniperAltM: Double,
    )

    @Test
    fun geometryValidationTable() {
        val baseLat = 31.500000
        val baseLon = 34.500000

        // Roughly 90 m per 0.001 deg of longitude at this latitude, and
        // 111 m per 0.001 deg of latitude. Sniper offsets are chosen to be
        // realistic standoffs of tens to a couple of hundred metres.
        val cases = listOf(
            GeometryCase(
                "צפון, מפלס אופקי, צלף במקום המכשיר",
                baseLat, baseLon, 100.0, 0f, 90f, 300f,
                baseLat, baseLon, 100.0,
            ),
            GeometryCase(
                "מזרח, מפלס אופקי, צלף במקום המכשיר",
                baseLat, baseLon, 100.0, 90f, 90f, 300f,
                baseLat, baseLon, 100.0,
            ),
            GeometryCase(
                "דרום-מזרח 135, צלף 100 מ' מדרום למכשיר",
                baseLat, baseLon, 100.0, 135f, 90f, 400f,
                baseLat - 0.000900, baseLon, 100.0,
            ),
            GeometryCase(
                "מערב 270, צלף 100 מ' ממזרח למכשיר",
                baseLat, baseLon, 100.0, 270f, 90f, 400f,
                baseLat, baseLon + 0.001060, 100.0,
            ),
            GeometryCase(
                "צפון, מטרה גבוהה, סרוו 100 מעלות",
                baseLat, baseLon, 100.0, 0f, 100f, 500f,
                baseLat, baseLon, 100.0,
            ),
            GeometryCase(
                "צפון, מטרה נמוכה, סרוו 80 מעלות",
                baseLat, baseLon, 150.0, 0f, 80f, 500f,
                baseLat, baseLon, 150.0,
            ),
            GeometryCase(
                "צפון-מזרח 45, צלף 50 מ' מצפון וגבוה ב-20 מ'",
                baseLat, baseLon, 100.0, 45f, 90f, 600f,
                baseLat + 0.000450, baseLon, 120.0,
            ),
            GeometryCase(
                "דרום 180, צלף 150 מ' ממערב",
                baseLat, baseLon, 100.0, 180f, 90f, 350f,
                baseLat, baseLon - 0.001580, 100.0,
            ),
            GeometryCase(
                "טווח קצר 40 מ', גבול תחתון של תקציב מד הטווח",
                baseLat, baseLon, 100.0, 30f, 90f, 40f,
                baseLat, baseLon, 100.0,
            ),
            GeometryCase(
                "טווח ארוך 1000 מ', שילוב הטיה וסטנדוף",
                baseLat, baseLon, 100.0, 210f, 95f, 1000f,
                baseLat + 0.000900, baseLon + 0.001060, 105.0,
            ),
        )

        println()
        println("=".repeat(118))
        println("טבלה 11.1 — אימות גיאומטרי של מנוע חישוב פתרון הירי")
        println("סף המעבר לפי פרק 10, שלב 1: סטייה עד ${geometryToleranceDeg} מעלות")
        println("=".repeat(118))
        println(
            "%-3s | %-44s | %9s | %9s | %7s | %9s | %9s | %7s".format(
                "#", "תרחיש", "AZ צפוי", "AZ בפועל", "סטייה", "EL צפוי", "EL בפועל", "סטייה",
            )
        )
        println("-".repeat(118))

        var worstAz = 0.0
        var worstEl = 0.0
        val failures = mutableListOf<String>()

        cases.forEachIndexed { index, c ->
            val target = localizeTarget(
                piGps = piGpsAt(c.piLat, c.piLon, c.piAltM),
                compassHeadingDeg = c.headingDeg,
                servoHorizontalDeg = 90f,
                servoVerticalDeg = c.servoVerticalDeg,
                rangefinderDistanceM = c.rangeM,
            )
            assertNotNull("case ${index + 1} (${c.label}) failed to localize", target)
            target!!

            val solution = solveFiringSolution(
                sniperLatDeg = c.sniperLat,
                sniperLonDeg = c.sniperLon,
                sniperAltM = c.sniperAltM,
                target = target,
                cartridge = m80,
                rifle = m24,
            )
            assertNotNull("case ${index + 1} (${c.label}) produced no solution", solution)
            solution!!

            val expectedGround = referenceGroundDistanceM(
                c.sniperLat, c.sniperLon, target.latitudeDeg, target.longitudeDeg,
            )
            val expectedAz = referenceBearingDeg(
                c.sniperLat, c.sniperLon, target.latitudeDeg, target.longitudeDeg,
            )
            val expectedEl = referenceLookAngleDeg(
                expectedGround, target.altitudeM - c.sniperAltM,
            )

            val azDiff = angularDiffDeg(expectedAz, solution.azimuthDeg)
            val elDiff = abs(expectedEl - solution.lookAngleDeg)

            worstAz = maxOf(worstAz, azDiff)
            worstEl = maxOf(worstEl, elDiff)
            if (azDiff > geometryToleranceDeg || elDiff > geometryToleranceDeg) {
                failures += "case ${index + 1} (${c.label}): AZ diff %.2f, EL diff %.2f"
                    .format(azDiff, elDiff)
            }

            println(
                "%-3d | %-44s | %8.2f° | %8.2f° | %6.2f° | %8.2f° | %8.2f° | %6.2f°".format(
                    index + 1, c.label,
                    expectedAz, solution.azimuthDeg, azDiff,
                    expectedEl, solution.lookAngleDeg, elDiff,
                )
            )
        }

        println("-".repeat(118))
        println("סטייה מרבית באזימוט: %.2f מעלות".format(worstAz))
        println("סטייה מרבית בזווית המבט: %.2f מעלות".format(worstEl))
        println("מספר מקרים שחרגו מהסף: ${failures.size} מתוך ${cases.size}")
        println("=".repeat(118))
        println()

        assertTrue(
            "Cases exceeded the ${geometryToleranceDeg} deg tolerance:\n" +
                failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    // -----------------------------------------------------------------------
    // A3 — Table 11.2: sensor sensitivity, twenty perturbations
    // -----------------------------------------------------------------------

    private data class SensitivityCase(
        val parameter: String,
        val change: String,
        val rangeM: Float = 300f,
        val servoVerticalDeg: Float = 90f,
        val windSpeedMps: Float? = null,
        val windDirectionDeg: Float? = null,
        val temperatureC: Float? = null,
        val humidityPct: Float? = null,
    )

    @Test
    fun sensorSensitivityTable() {
        val baseLat = 31.500000
        val baseLon = 34.500000
        val baseAlt = 100.0

        // Baseline: 300 m due north, level, no environmental data at all.
        // Every row below changes exactly one input relative to this.
        val baseline = SensitivityCase("קו בסיס", "ללא נתוני סביבה")

        val cases = listOf(
            baseline,
            SensitivityCase("טמפרטורה", "0°C", temperatureC = 0f, humidityPct = 50f),
            SensitivityCase("טמפרטורה", "15°C (ICAO)", temperatureC = 15f, humidityPct = 50f),
            SensitivityCase("טמפרטורה", "30°C", temperatureC = 30f, humidityPct = 50f),
            SensitivityCase("טמפרטורה", "45°C", temperatureC = 45f, humidityPct = 50f),
            SensitivityCase("לחות", "10%", temperatureC = 25f, humidityPct = 10f),
            SensitivityCase("לחות", "50%", temperatureC = 25f, humidityPct = 50f),
            SensitivityCase("לחות", "90%", temperatureC = 25f, humidityPct = 90f),
            SensitivityCase("רוח צד ימין", "5 מ/ש מ-090", windSpeedMps = 5f, windDirectionDeg = 90f),
            SensitivityCase("רוח צד שמאל", "5 מ/ש מ-270", windSpeedMps = 5f, windDirectionDeg = 270f),
            SensitivityCase("רוח צד ימין", "10 מ/ש מ-090", windSpeedMps = 10f, windDirectionDeg = 90f),
            SensitivityCase("רוח חזיתית", "10 מ/ש מ-000", windSpeedMps = 10f, windDirectionDeg = 0f),
            SensitivityCase("רוח גבית", "10 מ/ש מ-180", windSpeedMps = 10f, windDirectionDeg = 180f),
            SensitivityCase("רוח אלכסונית", "8 מ/ש מ-045", windSpeedMps = 8f, windDirectionDeg = 45f),
            SensitivityCase("מרחק", "50 מ'", rangeM = 50f),
            SensitivityCase("מרחק", "150 מ'", rangeM = 150f),
            SensitivityCase("מרחק", "600 מ'", rangeM = 600f),
            SensitivityCase("מרחק", "1000 מ'", rangeM = 1000f),
            SensitivityCase("שיפוע כלפי מעלה", "סרוו 100 מעלות", servoVerticalDeg = 100f),
            SensitivityCase("שיפוע כלפי מטה", "סרוו 80 מעלות", servoVerticalDeg = 80f),
            SensitivityCase(
                "שילוב מלא", "30°C, 60%, רוח 6 מ/ש מ-090",
                temperatureC = 30f, humidityPct = 60f,
                windSpeedMps = 6f, windDirectionDeg = 90f,
            ),
        )

        println()
        println("=".repeat(126))
        println("טבלה 11.3 — רגישות פתרון הירי לנתוני החיישנים")
        println("קו בסיס: מטרה 300 מ' צפונה, מפלס אופקי, ללא נתוני סביבה. בכל שורה משתנה קלט אחד בלבד.")
        println("תחמושת: ${m80.displayName}   רובה: ${m24.displayName}")
        println("=".repeat(126))
        println(
            "%-3s | %-18s | %-22s | %8s | %10s | %10s | %8s | %6s".format(
                "#", "פרמטר", "ערך", "טווח", "תיקון גובה", "תיקון רוח", "זמן מעוף", "ביטחון",
            )
        )
        println("-".repeat(126))

        cases.forEachIndexed { index, c ->
            val target = localizeTarget(
                piGps = piGpsAt(baseLat, baseLon, baseAlt),
                compassHeadingDeg = 0f,
                servoHorizontalDeg = 90f,
                servoVerticalDeg = c.servoVerticalDeg,
                rangefinderDistanceM = c.rangeM,
            )
            assertNotNull("row ${index} (${c.parameter}) failed to localize", target)

            val solution = solveFiringSolution(
                sniperLatDeg = baseLat,
                sniperLonDeg = baseLon,
                sniperAltM = baseAlt,
                target = target!!,
                cartridge = m80,
                rifle = m24,
                windSpeedMps = c.windSpeedMps,
                windDirectionDeg = c.windDirectionDeg,
                temperatureC = c.temperatureC,
                humidityPct = c.humidityPct,
            )
            assertNotNull("row ${index} (${c.parameter}) produced no solution", solution)
            solution!!

            println(
                "%-3s | %-18s | %-22s | %7.1fמ | %9.3f° | %9.3f° | %7.3fש | %5.2f".format(
                    if (index == 0) "בסיס" else index.toString(),
                    c.parameter, c.change,
                    solution.rangeM,
                    solution.elevationDeg,
                    solution.windageDeg,
                    solution.timeOfFlightS,
                    solution.confidence,
                )
            )
        }

        println("-".repeat(126))
        println("הערה: תיקון הרוח חיובי כאשר יש להחזיק ימינה. ערך הביטחון יורד כאשר נתוני סביבה חסרים.")
        println("=".repeat(126))
        println()
    }
}

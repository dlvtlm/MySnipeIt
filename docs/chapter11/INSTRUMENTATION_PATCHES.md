# תיקוני קוד זמניים למדידות פרק 11

מסמך זה מכיל את כל שינויי הקוד הדרושים כדי לבצע מדידות שהקוד הקיים אינו תומך בהן.
כל התיקונים כאן **זמניים ומיועדים למחיקה** לאחר איסוף המדידה. אף אחד מהם אינו אמור להיכנס לענף הראשי.

מסמך זה מופנה מתוך `TEST_PLAN_CHAPTER11.md`. אין להריץ אותו כשלעצמו.

---

## תיקון 1 (בדיקה B2) — מדידת השהיית עדכון פתרון הירי

**מה זה מודד:** את הזמן מרגע שהודעת חיישנים נקלטה ופוענחה באפליקציה ועד שפתרון ירי חדש נפלט.
זהו המספר שדרישה לא-פונקציונלית מס' 3 מדברת עליו, בגבולות האפליקציה.

**כמה קוד:** שתי שורות לוג ומשתנה סטטי אחד. אין שינוי לוגי.

### שלב 1: יצירת מחזיק חותמת הזמן

צור קובץ חדש בנתיב
`app/src/main/java/com/example/mysnipeit/data/network/LatencyProbe.kt`
עם התוכן הבא:

```kotlin
package com.example.mysnipeit.data.network

import android.util.Log

/**
 * TEMPORARY — Chapter 11 measurement B2 only. Delete after collection.
 *
 * Records the wall-clock instant at which a sensor frame was parsed, so
 * the ViewModel can subtract it when a new firing solution is emitted.
 * Deliberately a plain object with a volatile field: the two sides run on
 * different dispatchers and we only need last-write-wins.
 */
object LatencyProbe {
    private const val TAG = "SnipeItLatency"

    @Volatile
    private var sensorParsedAtNs: Long = 0L

    @Volatile
    private var sampleIndex: Int = 0

    fun markSensorParsed() {
        sensorParsedAtNs = System.nanoTime()
    }

    fun markSolutionEmitted() {
        val start = sensorParsedAtNs
        if (start == 0L) return
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
        Log.i(TAG, "sample=${++sampleIndex} latency_ms=%.3f".format(elapsedMs))
    }
}
```

### שלב 2: סימון נקודת הכניסה

בקובץ `app/src/main/java/com/example/mysnipeit/data/network/RaspberryPiClient.kt`,
בענף `"sensor_data"` (סביב שורה 256), הוסף שורה אחת:

```kotlin
"sensor_data" -> {
    val data = gson.fromJson(message, SensorData::class.java)
    LatencyProbe.markSensorParsed()          // <-- הוסף שורה זו
    _sensorData.value = data
}
```

### שלב 3: סימון נקודת היציאה

בקובץ `app/src/main/java/com/example/mysnipeit/viewmodel/SniperViewModel.kt`,
בהגדרת `firingSolution` (סביב שורה 339), עטוף את תוצאת החישוב:

```kotlin
val firingSolution: StateFlow<FiringSolution?> = combine(
    latchedSensorData,
    userLocation,
    userAltitudeM,
    selectedCartridge,
    selectedRifle,
) { sensor, sniperLatLng, sniperAlt, cart, rifle ->
    computeFiringSolution(sensor, sniperLatLng, sniperAlt, cart, rifle)
        .also { com.example.mysnipeit.data.network.LatencyProbe.markSolutionEmitted() }
}.stateIn(viewModelScope, SharingStarted.Eagerly, null)
```

### שלב 4: הרצה ואיסוף

חבר את המכשיר בכבל, ודא שניפוי באגים דרך USB מופעל, והרץ:

```bash
adb devices                                    # ודא שהמכשיר מזוהה
adb logcat -c                                  # נקה את הלוג הקיים
adb logcat -s SnipeItLatency > B2_latency_raw.txt
```

באפליקציה: היכנס למסך הדיאגנוסטיקה, הפעל MOCK MODE, חזור לדשבורד, והשאר פועל **חמש דקות**.
המחולל פולט מסגרת כל שנייה וחצי, כלומר כמאתיים דגימות. הספר דורש שלושים.

עצור עם `Ctrl+C` וחשב את הסטטיסטיקה:

```bash
grep -o 'latency_ms=[0-9.]*' B2_latency_raw.txt | cut -d= -f2 | sort -n | awk '
  {a[NR]=$1; s+=$1}
  END {
    printf "דגימות: %d\n", NR
    printf "ממוצע:  %.3f ms\n", s/NR
    printf "חציון:  %.3f ms\n", a[int(NR/2)+1]
    printf "מקסימום: %.3f ms\n", a[NR]
    printf "אחוזון 95: %.3f ms\n", a[int(NR*0.95)]
  }'
```

### שלב 5: ניקוי

```bash
git checkout -- app/src/main/java/com/example/mysnipeit/data/network/RaspberryPiClient.kt
git checkout -- app/src/main/java/com/example/mysnipeit/viewmodel/SniperViewModel.kt
rm app/src/main/java/com/example/mysnipeit/data/network/LatencyProbe.kt
```

### הסתייגות שחייבת להיכתב בפרק

מדידה זו מבודדת את חלקה של האפליקציה בלבד. ההשהיה מקצה לקצה, מרגע שהחיישן הפיזי מדד ועד שהערך מוצג,
כוללת גם את מחזור הדגימה בצד המכשיר (שתי שניות לפי רשומה 4 של החומרה) ואת זמן הרשת.
אין להציג את המספר הזה כאילו הוא ההשהיה המלאה.

---

## תיקון 2 (בדיקה D5) — מדידת השהיית המודול האקוסטי

**מה זה מודד:** את הזמן מרגע זיהוי ה-onset ועד שהודעת ה-JSON נשלחת.
מסמך התשובות של המודול האקוסטי קובע במפורש שהמפרט `< 50ms` **מעולם לא נמדד** ושאין
בקוד שום מדידת זמן, ולכן ללא התיקון הזה אי אפשר לדווח על השהיה בפרק 11.

**היכן:** בקובץ `acoustic_bridge.c`, בתוך `acoustic_bridge_tick`, אחרי תפיסת האירוע וסביב מקטע ה-DSP.

```c
/* TEMPORARY - Chapter 11 measurement D5. Remove after collection. */
struct timespec t0, t1;
clock_gettime(CLOCK_MONOTONIC, &t0);

/* ... הקוד הקיים: snapshot, GCC-PHAT, SRP-PHAT, ws_send_json ... */

clock_gettime(CLOCK_MONOTONIC, &t1);
{
    double dsp_ms = (t1.tv_sec - t0.tv_sec) * 1e3
                  + (t1.tv_nsec - t0.tv_nsec) / 1e6;
    uint64_t emit_us = (uint64_t)t1.tv_sec * 1000000ULL + t1.tv_nsec / 1000ULL;
    double total_ms = (emit_us - event_timestamp) / 1000.0;
    fprintf(stderr, "[ACOUSTIC-TIMING] dsp=%.3f ms  onset_to_emit=%.3f ms\n",
            dsp_ms, total_ms);
}
```

**המספר החשוב הוא השני.** `dsp` מודד רק את החישוב, בעוד ש-`onset_to_emit` מכסה גם את השהיית
חוט הלכידה ואת מסירת הדגל לחוט הראשי, וזה מה שהמפרט מדבר עליו.

**הרצה:**

```bash
cd ~/SnipeIt/pi-streaming
make clean && make
sudo ./streaming_server 2>&1 | grep ACOUSTIC-TIMING | tee D5_acoustic_latency.txt
```

מחא כפיים כעשרים פעמים במרווחים של שתי שניות לפחות, שכן זמן העצירה הוא חצי שנייה.

**הערה שכדאי לכתוב בפרק:** תקופת העצירה של חצי שנייה **אינה** משפיעה על השהיית אירוע בודד,
משום שהיא חוסמת את הזיהוי הבא ולא את הנוכחי.

---

## תיקון 3 (בדיקה A7) — סקירת סף הזיהוי האקוסטי

**מה זה מודד:** את יחסי הגומלין בין סף הזיהוי `ONSET_THRESHOLD` לבין שיעור הזיהוי ודיוק הזווית.
זו הבדיקה שמייצרת את הממצא החזק ביותר במודול האקוסטי, ראה בהמשך.

**אין צורך בשינוי קוד לוגי, רק בשינוי קבוע והידור מחדש.**

```bash
cd acoustic_detection_v2/acoustic_detection

for THR in 10.0 5.0 3.0 2.0 1.5 1.2; do
  sed -i "s/#define ONSET_THRESHOLD .*/#define ONSET_THRESHOLD ${THR}f/" \
      include/acoustic_config.h
  make clean && make
  for SIGMA in 0.001 0.01 0.05 0.10; do
    echo "=== THRESHOLD=${THR} SIGMA=${SIGMA} ==="
    ./test_harness --sweep --noise $SIGMA
    sleep 1        # test_harness.c:673 seeds with time(NULL)
  done
done > A7_threshold_study.txt 2>&1

git checkout -- include/acoustic_config.h    # החזר את ערך המקור 10.0
```

**מה מחפשים בפלט:** את השורה שבה שיעור הזיהוי מגיע למאה אחוז אך שגיאת הזווית הממוצעת קופצת
לעשרות מעלות. במדידה שכבר בוצעה זה קרה ב-`SIGMA=0.10, THRESHOLD=1.2` עם שגיאה ממוצעת של 73.2 מעלות.
זו ההדגמה החדה ביותר לכך שגלאי רגיש מדי אינו מתדרדר בהדרגה אלא מייצר תשובות שגויות בביטחון מלא.

---

## תיקון 4 (בדיקה A8) — שחזור השוואת מדדי הביטחון

**מה זה מודד:** את המדד הנטוש מול המדד הנוכחי על אותם נתונים בדיוק.
זהו הבסיס המספרי לסיפור הכישלון המרכזי במודול האקוסטי.

**היכן:** בקובץ `src/srp_phat.c`, לפני ההצמדה לתחום בשורה 190 בקירוב, הוסף חישוב של הנוסחה הנטושה
לצד הקיימת והדפס את שתיהן.

```c
/* TEMPORARY - Chapter 11 measurement A8. Remove after collection. */
{
    float best = 0.0f, second = 0.0f;
    for (int i = 0; i < num_angles; i++) {
        if (result->power_spectrum[i] > best) {
            second = best;
            best = result->power_spectrum[i];
        } else if (result->power_spectrum[i] > second) {
            second = result->power_spectrum[i];
        }
    }
    float old_metric = (best > 0.0f) ? (1.0f - second / best) : 0.0f;
    fprintf(stderr, "[CONF-COMPARE] az=%.1f  old=%.4f  new=%.4f\n",
            result->azimuth_deg, old_metric, result->confidence);
}
```

**הרצה:**

```bash
make clean && make
for AZ in -60 -30 0 30 60; do
  ./test_harness --azimuth $AZ --noise 0.01
  sleep 1
done 2>&1 | grep CONF-COMPARE | tee A8_confidence_comparison.txt
git checkout -- src/srp_phat.c
```

**מה זה אמור להראות:** המדד הישן בתחום 0.003 עד 0.016, המדד החדש בתחום 0.84 עד 0.89.
מאחר ששער התקפות הוא `confidence > 0.3`, המשמעות היא שכל אירוע היה משודר עם `valid: false`
למרות זווית מדויקת, כלומר כשל שקט מוחלט.

---

## סיכום התיקונים

| תיקון | בדיקה | קבצים שמשתנים | זמן הרצה משוער | דורש חומרה |
|---|---|---|---|---|
| 1 | B2 | 3 (אחד חדש) | 15 דקות | טאבלט בלבד |
| 2 | D5 | 1 | 20 דקות | Pi עם מיקרופונים |
| 3 | A7 | 1 (קבוע בלבד) | 25 דקות | לא |
| 4 | A8 | 1 | 10 דקות | לא |

**החזרת המצב לקדמותו לאחר כל התיקונים:**

```bash
git -C /path/to/MySnipeIt status         # ודא שאין שאריות
git -C /path/to/SnipeIt   status
```

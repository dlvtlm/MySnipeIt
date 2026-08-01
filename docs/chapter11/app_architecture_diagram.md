# תרשימי ארכיטקטורה — אפליקציית MySnipeIt

טיוטת תרשימים לפרק 11. שלושה תרשימים, כל אחד ממוקד בשאלה אחרת.
מסומנים בשמות ה-placeholders שישולבו בטקסט הפרק.

---

## `[FIG: app-mvvm-layers]` — שכבות MVVM וזרימת המידע

התרשים המרכזי לסעיף 11.2.1. מראה את שלוש השכבות, את שלושת ערוצי התקשורת,
ואת נקודת ההפרדה שבה החישוב הבליסטי מתבצע באפליקציה ולא ב-Pi.

```mermaid
flowchart TB
    subgraph PI["🔧 Raspberry Pi 5 — מכשיר השטח"]
        direction LR
        SENS["חיישנים<br/>מרחק · טמפ' · לחות<br/>GPS · מצפן · רוח · סרוו"]
        MICS["מערך 4 מיקרופונים<br/>GCC-PHAT + SRP-PHAT"]
        CAM["מצלמה + Orin<br/>זיהוי עצמים SSD"]
    end

    subgraph NET["📡 שלושה ערוצי תקשורת"]
        direction LR
        WS["WebSocket :8555<br/><i>דו-כיווני</i>"]
        RTSP["RTSP :8554<br/><i>וידאו</i>"]
        HTTP["HTTP :8000<br/><i>לא מומש</i>"]
    end

    subgraph MODEL["🗄️ שכבת Model"]
        direction TB
        CLIENT["RaspberryPiClient<br/><small>Coroutines · Dispatchers.IO</small>"]
        REPO["SniperRepository"]
        WIFI["WifiBinder<br/>WifiPerfLock"]
        BALL["מנוע בליסטי<br/><small>TargetLocalizer<br/>FiringSolutionSolver<br/>AcousticBearing</small>"]
        GPS["DeviceLocationProvider<br/><small>FusedLocationProvider</small>"]
    end

    subgraph VM["⚙️ שכבת ViewModel"]
        SVM["SniperViewModel<br/><small>מצב האפליקציה · ניווט · פקודות</small>"]
        FLOWS["StateFlow<br/><small>latchedSensorData · firingSolution<br/>detectedTargets · activeAudioAlert<br/>tripodWorldBearingDeg</small>"]
    end

    subgraph VIEW["🖥️ שכבת View — Jetpack Compose"]
        direction LR
        DASH["דשבורד מבצעי<br/><small>וידאו + שכבת-על + HUD</small>"]
        DIAG["דיאגנוסטיקה"]
        MAP["מפה"]
        DEV["בחירת מכשיר"]
    end

    SENS -->|"sensor_data<br/>(ddl_frame)"| WS
    MICS -->|"acoustic_event"| WS
    CAM -->|"target_detection"| WS
    CAM ==>|"H.264"| RTSP

    WS <--> CLIENT
    RTSP ==> DASH
    HTTP -.->|"ננטש"| CLIENT

    WIFI -.->|"כופה ניתוב<br/>ל-Wi-Fi"| CLIENT
    CLIENT --> REPO
    REPO --> SVM
    GPS -->|"מיקום הצלף"| SVM
    SVM <--> BALL
    SVM --> FLOWS

    FLOWS ==>|"collectAsStateWithLifecycle"| DASH
    FLOWS ==> DIAG
    FLOWS ==> MAP
    FLOWS ==> DEV

    DASH -->|"נעילה · SLEW · כיול"| SVM
    SVM -->|"select_target<br/>set_servo_angles"| CLIENT

    classDef pi fill:#2d3436,stroke:#e17055,stroke-width:2px,color:#fff
    classDef net fill:#1e3799,stroke:#4a69bd,stroke-width:2px,color:#fff
    classDef model fill:#0c2461,stroke:#60a3bc,stroke-width:2px,color:#fff
    classDef vm fill:#6F1E51,stroke:#B53471,stroke-width:2px,color:#fff
    classDef view fill:#006266,stroke:#009432,stroke-width:2px,color:#fff

    class SENS,MICS,CAM pi
    class WS,RTSP,HTTP net
    class CLIENT,REPO,WIFI,BALL,GPS model
    class SVM,FLOWS vm
    class DASH,DIAG,MAP,DEV view
```

---

## `[FIG: ballistic-pipeline]` — צינור החישוב הבליסטי הדו-שלבי

לסעיף 11.2.3. הנקודה ההנדסית: הצלף וה-Pi נמצאים במיקומים פיזיים שונים,
ולכן אי אפשר להשתמש ישירות במרחק ובכיוון שה-Pi מדד.

```mermaid
flowchart LR
    subgraph IN["קלט"]
        direction TB
        A1["מרחק (לייזר)"]
        A2["מצפן heading"]
        A3["זווית סרוו"]
        A4["GPS + גובה של ה-Pi"]
        B1["GPS + גובה<br/>של הצלף"]
        B2["רוח: מהירות + כיוון"]
        B3["טמפ' + לחות"]
        B4["לואאוט:<br/>קרטרידג' + רובה"]
    end

    subgraph P1["שלב 1 — localizeTarget()"]
        L1["גיאומטריית הריג<br/><small>RigGeometry</small>"]
        L2["הטלה גיאודזית<br/>קדימה"]
    end

    TL["📍 TargetLocation<br/><small>קו רוחב · קו אורך · גובה</small>"]

    subgraph P2["שלב 2 — solveFiringSolution()"]
        S1["טווח + אזימוט<br/>מהצלף"]
        S2["צפיפות אוויר<br/><small>טמפ' · לחות · גובה</small>"]
        S3["אינטגרציית גרר G1"]
        S4["סחיפת רוח"]
    end

    subgraph OUT["פלט — FiringSolution"]
        direction TB
        O1["אזימוט"]
        O2["זווית מבט"]
        O3["hold-over"]
        O4["windage"]
        O5["זמן מעוף"]
        O6["מהירות פגיעה"]
        O7["confidence"]
    end

    A1 & A2 & A3 & A4 --> L1
    L1 --> L2 --> TL
    TL --> S1
    B1 --> S1
    B3 --> S2
    B4 --> S3
    S1 --> S3
    S2 --> S3
    B2 --> S4
    S3 --> S4
    S4 --> OUT

    LATCH["⏱️ Sensor Latching<br/><small>כל תת-מסגרת שומרת את<br/>הקריאה התקפה האחרונה<br/>עד 5 שניות</small>"]
    LATCH -.->|"מסנן<br/>הבהובים"| IN

    classDef inp fill:#0c2461,stroke:#60a3bc,color:#fff
    classDef proc fill:#6F1E51,stroke:#B53471,color:#fff
    classDef out fill:#006266,stroke:#009432,color:#fff
    classDef mid fill:#833471,stroke:#D980FA,color:#fff

    class A1,A2,A3,A4,B1,B2,B3,B4 inp
    class L1,L2,S1,S2,S3,S4 proc
    class O1,O2,O3,O4,O5,O6,O7 out
    class TL,LATCH mid
```

---

## `[FIG: acoustic-alert-flow]` — זרימת ההתראה האקוסטית מקצה לקצה

לסעיף 11.2.4. מראה את שתי דרכי הפעולה: כיוון עולמי כשהכיול תקף,
וזווית יחסית כשאינו — ובשני המקרים ה-SLEW עובד, כי הוא תלוי רק
באזימוט הגולמי.

```mermaid
flowchart TB
    START(["💥 אירוע אימפולסיבי<br/>בסביבת המערכת"])

    subgraph PISIDE["צד ה-Pi — C"]
        direction TB
        CAP["לכידת I2S<br/><small>4 ערוצים · 48kHz</small>"]
        ONSET["גלאי Onset<br/><small>HPF 300Hz + יחס EMA</small>"]
        GCC["GCC-PHAT<br/><small>6 זוגות → TDOA</small>"]
        SRP["SRP-PHAT<br/><small>סריקת 181 זוויות</small>"]
        GATE{"valid?<br/><small>conf > 0.3<br/>amp > 0.05</small>"}
    end

    JSON["📨 acoustic_event<br/><small>azimuth_deg · confidence<br/>peak_amplitude · valid</small>"]

    subgraph APPSIDE["צד האפליקציה — Kotlin"]
        direction TB
        PARSE["פענוח → AcousticEvent"]
        CALCHK{"הכיול תקף?<br/><small>קיים וטרם פג<br/>(90 דקות)</small>"}
        WORLD["worldBearingFromAcousticEvent<br/><small>כיוון_חצובה + אזימוט</small>"]
        REL["שמירת הזווית<br/>היחסית בלבד"]
        DEDUP["dedupe / debounce<br/><small>±15° · 20שנ' timeout<br/>30שנ' debounce</small>"]
        LOCKCHK{"מטרה<br/>נעולה?"}
    end

    CARD["🔔 כרטיס התראה<br/><small>כיוון עולמי + SLEW + DISMISS</small>"]
    CARDREL["🔔 כרטיס התראה<br/><small>'REL 42°' + SLEW + DISMISS</small>"]
    CHIP["🏷️ chip פסיבי<br/><small>ללא הפרעה לנעילה</small>"]

    SLEW["📐 set_servo_angles<br/><small>סרוו = אזימוט + 90°</small>"]
    SERVO(["🎥 המצלמה מסתובבת<br/>וממשיכה בסריקה"])

    START --> CAP --> ONSET --> GCC --> SRP --> GATE
    GATE -->|"כן"| JSON
    GATE -->|"לא"| JSON
    JSON -->|"WebSocket :8555"| PARSE
    PARSE --> CALCHK
    CALCHK -->|"כן"| WORLD
    CALCHK -->|"לא / פג"| REL
    WORLD --> DEDUP
    REL --> DEDUP
    DEDUP --> LOCKCHK
    LOCKCHK -->|"לא — מצב אינטראקטיבי"| CARD
    LOCKCHK -->|"לא — ללא כיול"| CARDREL
    LOCKCHK -->|"כן — מצב פסיבי"| CHIP
    CARD -->|"SLEW"| SLEW
    CARDREL -->|"SLEW"| SLEW
    SLEW -->|"WebSocket"| SERVO

    classDef pi fill:#2d3436,stroke:#e17055,color:#fff
    classDef app fill:#6F1E51,stroke:#B53471,color:#fff
    classDef ui fill:#006266,stroke:#009432,color:#fff
    classDef msg fill:#1e3799,stroke:#4a69bd,color:#fff

    class CAP,ONSET,GCC,SRP,GATE pi
    class PARSE,CALCHK,WORLD,REL,DEDUP,LOCKCHK app
    class CARD,CARDREL,CHIP ui
    class JSON,SLEW msg
```

---

## הערות

1. התרשימים ב-Mermaid כדי שיהיו ניתנים לעריכה. להטמעה ב-Word — לייצא ל-PNG/SVG
   דרך [mermaid.live](https://mermaid.live) ולהדביק.
2. תרשים 1 מיועד להשלים את איור 8.1 (תרשים הארכיטקטורה הכללי) בזום פנימה
   על האפליקציה בלבד — לא להחליף אותו.
3. ערוץ ה-HTTP מסומן מקווקו ומסומן "ננטש" בכוונה — זו החלטה מתועדת
   ברשומת פיתוח 11.1.1, לא השמטה.

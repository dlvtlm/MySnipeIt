#!/usr/bin/env bash
# B2_analyze_latency.sh
#
# Linux and macOS equivalent of B2_analyze_latency.ps1. Computes the
# statistics for Chapter 11 measurement B2 from an adb logcat capture
# produced by LatencyProbe.
#
# Usage, from the repository root:
#     ./docs/chapter11/test-code/B2_analyze_latency.sh docs/chapter11/results/B2_latency_raw.txt

set -euo pipefail

SRC="${1:-docs/chapter11/results/B2_latency_raw.txt}"
OUT="${2:-docs/chapter11/results/B2_latency_summary.txt}"

if [ ! -f "$SRC" ]; then
    echo "Capture file not found: $SRC" >&2
    echo "" >&2
    echo "Collect it first with:" >&2
    echo "    adb logcat -c" >&2
    echo "    adb logcat -s SnipeItLatency > $SRC" >&2
    exit 1
fi

mkdir -p "$(dirname "$OUT")"

grep -o 'latency_ms=[0-9.]*' "$SRC" | cut -d= -f2 | sort -n | awk '
  { a[NR] = $1; sum += $1 }
  END {
    if (NR == 0) { print "No latency_ms lines found." > "/dev/stderr"; exit 1 }
    mean = sum / NR
    for (i = 1; i <= NR; i++) { d = a[i] - mean; ss += d * d }
    sd  = (NR > 1) ? sqrt(ss / (NR - 1)) : 0
    med = a[int((NR + 1) / 2)]
    p95 = a[int(NR * 0.95) < 1 ? 1 : int(NR * 0.95)]
    sep = "=============================================================="
    print sep
    print "בדיקה B2 - השהיית עדכון פתרון הירי"
    print sep
    printf "מספר דגימות      : %d\n",      NR
    printf "ממוצע            : %.3f ms\n", mean
    printf "חציון            : %.3f ms\n", med
    printf "אחוזון 95        : %.3f ms\n", p95
    printf "מקסימום          : %.3f ms\n", a[NR]
    printf "מינימום          : %.3f ms\n", a[1]
    printf "סטיית תקן        : %.3f ms\n", sd
    print "--------------------------------------------------------------"
    print "הדרישה הלא-פונקציונלית: עד 2000 ms"
    printf "תוצאה: %s\n", (a[NR] < 2000 ? "עומד בדרישה" : "אינו עומד בדרישה")
    print sep
    print ""
    print "הסתייגות שיש לכתוב בפרק: המדידה מבודדת את חלקה של האפליקציה בלבד."
    print "ההשהיה מקצה לקצה כוללת גם את מחזור הדגימה בצד המכשיר ואת זמן הרשת."
  }' | tee "$OUT"

echo ""
echo "Summary written to $OUT"

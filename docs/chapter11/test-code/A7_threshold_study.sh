#!/usr/bin/env bash
# A7_threshold_study.sh
#
# Chapter 11 measurement A7 - onset threshold study for the acoustic module.
#
# Sweeps ONSET_THRESHOLD across six values and, for each, runs the azimuth
# sweep at four noise levels. Produces the detection-rate versus accuracy
# trade-off, including the pathological case where a too-sensitive detector
# fires on a noise excursion BEFORE the impulse arrives and reports a
# confident but meaningless bearing.
#
# Needs no microphones and no Raspberry Pi. Any Linux box with libfftw3-dev.
#
#     sudo apt install build-essential libfftw3-dev
#
# Run from inside the acoustic source tree:
#
#     cd acoustic_detection_v2/acoustic_detection
#     /path/to/A7_threshold_study.sh
#
# The original ONSET_THRESHOLD is restored on exit, including on Ctrl+C.

set -uo pipefail

CONFIG="include/acoustic_config.h"
OUT="${1:-A7_threshold_study.txt}"
THRESHOLDS=(10.0 5.0 3.0 2.0 1.5 1.2)
SIGMAS=(0.001 0.01 0.05 0.10)

if [ ! -f "$CONFIG" ]; then
    echo "ERROR: $CONFIG not found." >&2
    echo "Run this script from inside acoustic_detection_v2/acoustic_detection." >&2
    exit 1
fi

if [ ! -f "$CONFIG.a7backup" ]; then
    cp "$CONFIG" "$CONFIG.a7backup"
fi

restore() {
    echo ""
    echo "Restoring the original $CONFIG ..."
    cp "$CONFIG.a7backup" "$CONFIG"
    rm -f "$CONFIG.a7backup"
    make clean >/dev/null 2>&1 || true
    echo "Done. The shipped threshold is back in place."
}
trap restore EXIT INT TERM

: > "$OUT"

for THR in "${THRESHOLDS[@]}"; do
    echo "### Building with ONSET_THRESHOLD = ${THR}"
    sed -i -E "s/#define[[:space:]]+ONSET_THRESHOLD[[:space:]]+[0-9.]+f?/#define ONSET_THRESHOLD ${THR}f/" "$CONFIG"

    if ! grep -q "ONSET_THRESHOLD ${THR}f" "$CONFIG"; then
        echo "ERROR: failed to set the threshold in $CONFIG. Check the macro name." >&2
        exit 1
    fi

    make clean >/dev/null 2>&1
    if ! make >/dev/null 2>&1; then
        echo "ERROR: build failed at threshold ${THR}." >&2
        exit 1
    fi

    for SIGMA in "${SIGMAS[@]}"; do
        {
            echo ""
            echo "=================================================="
            echo "THRESHOLD=${THR}  SIGMA=${SIGMA}"
            echo "=================================================="
        } >> "$OUT"
        ./test_harness --sweep --noise "$SIGMA" >> "$OUT" 2>&1
        # test_harness seeds from time(NULL); space the runs so the noise differs.
        sleep 1
    done
done

echo ""
echo "Raw output written to $OUT"
echo ""
echo "What to look for:"
echo "  1. At the shipped threshold of 10.0, detection stops entirely above"
echo "     a certain noise level. That is a deliberate conservative choice."
echo "  2. Lowering the threshold recovers detection at high noise with the"
echo "     bearing accuracy still inside spec, which shows the localizer is"
echo "     not the bottleneck."
echo "  3. At the lowest thresholds detection reaches 100 percent while the"
echo "     mean error explodes. That is the detector firing on noise before"
echo "     the impulse arrives, so the analysis window misses the event."

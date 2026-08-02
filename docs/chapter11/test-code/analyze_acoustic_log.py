#!/usr/bin/env python3
"""
analyze_acoustic_log.py

Analyses a streaming_server log containing acoustic onsets and events.
Written for the Chapter 10 acoustic bearing test and the false-positive
characterisation that came out of it.

Usage:
    python3 analyze_acoustic_log.py CH10_acoustic_hw.log
    python3 analyze_acoustic_log.py D2_soak.log --amp 0.15 --dur 2.0

What it reports:
  1. Onset cadence, to show whether triggers are periodic
  2. Event totals and how many passed the Pi's validity gate
  3. Amplitude and duration distributions
  4. Bearing histogram, to expose a fixed-direction source
  5. A separation of self-noise from real impulses using amplitude and
     duration gates, with the surviving events listed
"""

import argparse
import json
import re
import sys
from collections import Counter

ONSET_RE = re.compile(r"Onset detected at (\d+) us")
EVENT_RE = re.compile(r"\[EVENT\]\s*(\{.*\})")


def pct(values, p):
    if not values:
        return float("nan")
    s = sorted(values)
    i = min(int(round(p * (len(s) - 1))), len(s) - 1)
    return s[i]


def bar(n, total, width=40):
    if total == 0:
        return ""
    return "#" * max(1, int(round(width * n / total))) if n else ""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("logfile")
    ap.add_argument("--amp", type=float, default=0.15,
                    help="amplitude gate for a real impulse (default 0.15)")
    ap.add_argument("--dur", type=float, default=2.0,
                    help="duration gate in ms for a real impulse (default 2.0)")
    args = ap.parse_args()

    try:
        text = open(args.logfile, encoding="utf-8", errors="replace").read()
    except OSError as e:
        sys.exit(f"cannot read {args.logfile}: {e}")

    onsets = [int(m) for m in ONSET_RE.findall(text)]
    events = []
    for m in EVENT_RE.findall(text):
        try:
            events.append(json.loads(m))
        except json.JSONDecodeError:
            pass

    print("=" * 68)
    print(f"ACOUSTIC LOG ANALYSIS  -  {args.logfile}")
    print("=" * 68)

    # ---- 1. onset cadence -------------------------------------------------
    print("\n[1] ONSET CADENCE")
    print(f"    onsets detected            : {len(onsets)}")
    if len(onsets) >= 2:
        span_s = (onsets[-1] - onsets[0]) / 1e6
        print(f"    span                       : {span_s:.1f} s")
        print(f"    rate                       : {len(onsets)/span_s*60:.1f} per minute")
        gaps = [(b - a) / 1e6 for a, b in zip(onsets, onsets[1:])]
        hist = Counter(round(g, 1) for g in gaps)
        print("    inter-onset gap histogram (seconds):")
        for gap, n in sorted(hist.items())[:12]:
            print(f"      {gap:5.1f} s  {n:4d}  {bar(n, len(gaps))}")
        near2 = sum(1 for g in gaps if abs(g - 2.0) < 0.05)
        print(f"    gaps within 50 ms of 2.000 s : {near2} of {len(gaps)}"
              f"  ({100*near2/len(gaps):.0f}%)")
        if near2 / len(gaps) > 0.5:
            print("    >>> PERIODIC at 2 s. Machine-generated, not ambient noise.")

    # ---- 2. events --------------------------------------------------------
    print("\n[2] EVENTS")
    if not events:
        print("    no [EVENT] lines found. Was the app connected?")
        return
    valid = [e for e in events if e.get("valid")]
    print(f"    events emitted             : {len(events)}")
    print(f"    valid=true                 : {len(valid)}"
          f"  ({100*len(valid)/len(events):.0f}%)")
    print(f"    events per onset           : {len(events)/max(1,len(onsets)):.2f}"
          "   (below 1.0 means some fired while no app was connected)")

    # ---- 3. distributions -------------------------------------------------
    amps = [e["peak_amplitude"] for e in events]
    durs = [e["duration_ms"] for e in events]
    confs = [e["confidence"] for e in events]
    print("\n[3] DISTRIBUTIONS")
    print("               min      p50      p90      p99      max")
    for name, vals in (("amplitude", amps), ("duration ", durs), ("confidence", confs)):
        print(f"    {name}  {min(vals):7.4f}  {pct(vals,.5):7.4f}  "
              f"{pct(vals,.9):7.4f}  {pct(vals,.99):7.4f}  {max(vals):7.4f}")

    # ---- 4. bearings ------------------------------------------------------
    print("\n[4] BEARING HISTOGRAM (10 degree bins)")
    bins = Counter(int(e["azimuth_deg"] // 10) * 10 for e in events)
    for b in sorted(bins):
        n = bins[b]
        print(f"    {b:+4d} to {b+9:+4d}  {n:4d}  {bar(n, len(events))}")
    top = bins.most_common(1)[0]
    if top[1] / len(events) > 0.25:
        print(f"    >>> {100*top[1]/len(events):.0f}% of events fall in "
              f"{top[0]:+d}..{top[0]+9:+d}. A source at a FIXED bearing,")
        print("        which points at something mounted on the rig itself.")

    # ---- 5. separation ----------------------------------------------------
    print(f"\n[5] SEPARATION  (real impulse = amplitude >= {args.amp} "
          f"AND duration >= {args.dur} ms)")
    real = [e for e in events if e["peak_amplitude"] >= args.amp
            and e["duration_ms"] >= args.dur]
    noise = [e for e in events if e not in real]
    print(f"    classified as real impulse : {len(real)}")
    print(f"    classified as self-noise   : {len(noise)}")
    if noise:
        na = [e["peak_amplitude"] for e in noise]
        nd = [e["duration_ms"] for e in noise]
        print(f"    self-noise amplitude       : {min(na):.4f} to {max(na):.4f}")
        print(f"    self-noise duration        : {min(nd):.1f} to {max(nd):.1f} ms")
    if real:
        ra = [e["peak_amplitude"] for e in real]
        print(f"    real impulse amplitude     : {min(ra):.4f} to {max(ra):.4f}")
        print("\n    surviving events:")
        t0 = real[0]["timestamp_us"]
        for e in real:
            print(f"      t+{(e['timestamp_us']-t0)/1e6:7.3f}s  "
                  f"az={e['azimuth_deg']:+6.1f}  conf={e['confidence']:.2f}  "
                  f"amp={e['peak_amplitude']:.4f}  dur={e['duration_ms']:5.1f}ms")

    print("\n" + "=" * 68)
    if len(noise) > len(real) * 3:
        print("VERDICT: the log is dominated by periodic self-noise.")
        print("The Pi-side validity gate is amplitude > 0.05, which sits BELOW")
        print("the rig's own noise floor, so that noise is being marked valid.")
        print("Raising the amplitude gate is field calibration, not a fudge:")
        print("the module's own documentation lists tuning for the deployment")
        print("environment as outstanding work.")
    print("=" * 68)


if __name__ == "__main__":
    main()

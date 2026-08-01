# Questions for the Acoustic Module session — input needed for Chapter 11 (Deliverables)

**Context:** I'm writing Chapter 11 ("תוצרים" / Deliverables) of the SnipeIt final-year engineering report. Chapter 11 has two mandatory parts:

- **Part A — Development Log:** must show *engineering process* — experiments, alternatives evaluated, difficulties, **failures and what was learned from them**, and how literature-survey papers concretely influenced implementation decisions. Chronological, with dates.
- **Part B — Final Deliverables:** the working product only. Input → Processing → Output, plus **performance metrics and measured results**. No chronology, no bugs, no alternatives.

I have read `ACOUSTIC_MODULE_TECHNICAL_REPORT.md` (status date 29 July 2026) in full. It is an excellent source for Part A's *technical* content. What I'm missing is almost entirely **dates, alternatives, measured numbers, and three specific reconciliations.**

Please answer inline in this file (or as a new MD) and send it back. Where a number was never measured, **say "not measured"** — I will write it as planned/future work rather than invent a figure.

---

## 🔴 PRIORITY 1 — Three discrepancies that must be reconciled before I can write

### Q1. The book chose a *different algorithm and a different mic count* than what was built

The project book, Chapter 9 ("ניתוח חלופות טכנולוגיות למימוש"), Tables 9.11 and 9.12, formally evaluated four options and selected:

> **"GCC-PHAT ו-Hyperbolic Localization עם 6 מיקרופונים"** — scored 0.76, chosen over Multilateration/4 mics (0.545), Wavefront Precession/8 mics (0.61), Levenberg-Marquardt/16 mics (0.325).

What was actually built is **GCC-PHAT + SRP-PHAT with 4 microphones**. That is a change in both the localization algorithm and the hardware count, relative to a decision that is already written into the submitted book.

This is not a problem — it is exactly the kind of engineering evolution the development log is *supposed* to document. But I need the real story, and it must also be entered into the book's change-log table (Table 1, "טבלת ריכוז השינויים"):

- **a.** When was the decision to go with 4 mics + SRP-PHAT made? (approximate date / project phase)
- **b.** **Why 4 and not 6?** Cost? Wiring complexity (the report notes only 2 SDI lanes were brought up)? The RP1 I2S lane limit? Physical bracket size? Something else?
- **c.** **Why SRP-PHAT instead of hyperbolic localization?** My reading of the report is that hyperbolic localization solves for a *position* (range + bearing), while SRP-PHAT solves only for a *bearing* — and SnipeIt only ever needed a bearing (to slew the camera), so the extra machinery bought nothing. **Is that the correct reasoning?** If so it's a strong "we simplified after understanding the actual requirement" narrative.
- **d.** Was the 6-mic option formally abandoned or is it still the roadmap? (§7 "Upgrading to 6 mics" reads like it's still open — `NUM_CHANNELS` → 6, positions template already in comments.)
- **e.** Was the 2-mic bring-up phase (implied by §4.6 "during 2-mic bring-up" and the `googlevoicehat-soundcard` overlay in §4.4/§9-item-9) a **planned incremental step**, or a fallback after a 4-mic attempt failed? This matters — planned incrementalism is a good engineering story; the report currently doesn't say which it was.

### Q2. Two mutually incompatible bearing-composition formulas exist

**Report §5.1** specifies:
```
world_bearing = (compass_heading − servo_horizontal_deg + array_mount_offset + azimuth_deg) mod 360
```
and flags a caveat: compass/servo readings can be up to `DDL_BRIDGE_INTERVAL_MS` (1 s) stale.

**The Android app deliberately does NOT do this.** It uses an operator-performed calibration instead:
```
world_bearing = tripod_world_bearing_at_calibration + azimuth_deg
```
where `tripod_world_bearing_at_calibration` is the compass reading captured once, at setup, while the camera arm is centred at servo 90°. It is persisted, and it **expires after 90 minutes** (after which alerts still fire but display a *relative* mic angle instead of a world bearing). The rationale recorded in the app is: the compass rides the moving head while the mic array is on the fixed tripod, so the live compass tells you where the *camera* points, not where the *array* points — and backing the servo angle out of it depends on two sensor readings that are both up to 1 s stale.

- **a.** Was §5.1's formula ever actually implemented or tested anywhere, or is it a design proposal only?
- **b.** Do you agree the app-side calibration approach supersedes it? **I need one authoritative answer**, because Part B must describe the shipping behaviour and cannot present two contradictory formulas.
- **c.** Is `array_mount_offset` physically 0° on the real rig (i.e. does the array's forward axis align with the tripod's forward axis)? The app assumes the calibration absorbs this entirely.
- **d.** The report proposes as future work moving composition into `acoustic_bridge.c` and pulling the DDL snapshot at the exact event timestamp. **Should I present that as the agreed roadmap?** It would resolve the staleness caveat *and* remove the need for operator calibration — that's a clean "known limitation + planned fix" paragraph.

### Q3. §9, outstanding-work item 3 is out of date

The report states:

> "**Android handler** — no `acoustic_event` handling exists in `MySnipeIt` yet (no matches for 'acoustic' anywhere in the app source)."

This has not been true since **18 June 2026**. The app currently has, all merged to master:

| Component | File |
|---|---|
| `acoustic_event` WS parsing → `AcousticEvent` model | `RaspberryPiClient.kt`, `data/models/AcousticEvent.kt` |
| Bearing composition (pure fn) + 12 unit tests | `data/ballistics/AcousticBearing.kt`, `AcousticBearingTest.kt` |
| Derived alert state: dedupe ±15°, 20 s auto-dismiss, 30 s post-dismiss debounce, lock-aware passive/interactive modes | `SniperViewModel.activeAudioAlert`, `data/models/AudioAlert.kt` |
| Alert UI (corner card + passive chip) | `ui/dashboard/AudioAlert.kt` |
| Operator calibration dialog + 90-min expiry + top-bar age chip | `ui/dashboard/CalibrateBearingDialog.kt` |
| SLEW command → `set_servo_angles` over WS (servo = azimuth + 90°) | `SniperViewModel.acceptAudioAlert()` |
| Synthetic `acoustic_event` every 20–40 s in MOCK MODE for offline testing | `RaspberryPiClient` mock generator |
| Raw event display in the Diagnostics screen | `ui/diagnostics/DiagnosticsScreen.kt` |

- **a.** Please confirm you agree this item is closed, so I don't list it as outstanding work in the chapter.
- **b.** **Does the app's SLEW contract match the Pi?** The app sends `set_servo_angles` over WS with `horizontal = azimuth_deg + 90`, expecting the Pi's `ddl_bridge_handle_command` to route it to `ddl_servo_set_target` + a `NOISE_DETECTED` event that slews and then resumes the autonomous scan. **Has this path been exercised end-to-end, or only written on the app side against the spec?**
- **c.** The app gates nothing on confidence — it forwards any event where `valid == true` (which the Pi already defines as `confidence > 0.3 && peak_amplitude > 0.05`). §9-item-3 suggested an additional app-side gate at `confidence > 0.5`. **Was that ever agreed?** Right now it isn't implemented, and I'd rather document the single Pi-side gate than a phantom one.

---

## 🟠 PRIORITY 2 — Dates (Part A requires a chronology)

I have exact commit dates for the Android app but **nothing for the acoustic module**. If the acoustic code is under version control, `git log --format="%h|%ad|%s" --date=short` is the single most useful thing you can send me. Failing that, approximate month-level dates for:

| # | Milestone | Date |
|---|---|---|
| 1 | Acoustic subsystem work started | |
| 2 | Array geometry + 3D bracket designed | |
| 3 | 2-mic bring-up (googlevoicehat overlay) working | |
| 4 | GPIO 21 → GPIO 22 discovered (§4.1) | |
| 5 | Custom `snipeit-4mic-i2s.dts` overlay working — one 4-ch card (§4.2, §4.3) | |
| 6 | GCC-PHAT implemented and validated in simulation | |
| 7 | SRP-PHAT implemented | |
| 8 | Confidence metric replaced: second-peak → peak-to-mean (§4.6) | |
| 9 | `test_harness.c` written / first sweep results | |
| 10 | First real hardware detection on dev Pi (±5° claps) | |
| 11 | `acoustic_bridge.c` written — integration into `pi-streaming` | |
| 12 | Runbook Phases 0–4 completed green | |
| 13 | Current status date | 29 Jul 2026 |

Also: was there a **stall or pause** in acoustic work (waiting on hardware delivery, etc.)? The Android side had a ~3-month gap Jan–Apr 2026; if the acoustic side had one too, saying so once covers both honestly.

---

## 🟡 PRIORITY 3 — Measured numbers (Part B requires performance metrics)

### Q4. The simulation table (§6.1)

| Noise σ | Mean error | Max error | Detection rate |
|---|---|---|---|
| 0.001 | < 2° | < 3° | 100 % |
| 0.01 | < 3° | < 5° | 100 % |
| 0.05 | < 5° | < 8° | > 95 % |
| 0.10 | < 8° | < 15° | > 85 % |

- **a.** Are these **actual measured outputs** of `make sweep`, or design targets / estimates? This matters enormously — measured data goes in Part B as a results table; estimates have to be labelled as such.
- **b.** If measured: **please send the raw stdout of `make sweep`** (and `./test_harness --azimuth X --noise Y` runs). Exact per-angle errors let me build a proper error-vs-azimuth plot, which is worth far more in the report than a 4-row summary. **This test needs no microphones and no Pi — it runs on any Linux box with `libfftw3-dev` installed**, so it should be runnable right now even though the mics are unavailable.
- **c.** How many trials per cell? What was the detection-rate denominator?
- **d.** Was the sweep run with **4 mics** or during the 2-mic phase?

### Q5. Hardware validation (§6.2) — "±5° on real claps"

- **a.** How many trials? At which true bearings?
- **b.** What was the physical setup (distance from array, indoor/outdoor, cardboard semicircle at 8 cm)?
- **c.** How was ground-truth bearing established (tape measure? protractor? marked floor)?
- **d.** Is there a **log file, terminal capture, or photo** of the setup? A photo of the cardboard array rig would be a genuinely good figure for the chapter.
- **e.** Was ±5° a **mean** error or a **max/worst-case** error?

### Q6. Latency

Design target is `< 50 ms detect-to-emit`.

- **a.** Was this **measured**, or is it derived from the design (10 ms capture chunks + 1 ms main-loop poll)?
- **b.** If measured — how, and what came out? (Timestamping onset vs. `ws_send_json` would be the obvious method.)
- **c.** Do you have a rough cost for the DSP stage itself — 6× 4096-point FFT pairs + a 181-point grid search on a Pi 5? Even a single `clock_gettime` delta around `acoustic_bridge_tick` would give me a real number for Part B.

### Q7. False positives

§4.9/§4.10 describe the validity gate and the three anti-false-trigger guards, which reads like they were added in response to observed problems.

- **a.** **Before** the guards: roughly how often did it false-fire? ("constantly", "a few per minute", a count?)
- **b.** **After**: was any quiet-room soak test run? E.g. 10–30 min of ambient with no impulses — how many `valid: true` events?
- **c.** Was `ONSET_THRESHOLD = 10.0` tuned empirically, and if so from what starting value?

### Q8. The confidence-metric failure (§4.6)

This is the best "failed experiment → root cause → fix" story in the whole module and I want to write it properly.

- **a.** Do you have **before/after numbers**? E.g. "clean clap at 30°: old metric 0.02, new metric 0.87". Even one paired example makes the paragraph concrete.
- **b.** Was the failure discovered during the 2-mic phase only, or did it also show up with 4 mics (just less severely)?

---

## 🟢 PRIORITY 4 — Alternatives considered (Part A explicitly requires these)

The report documents *problems solved*, but the log also needs **options weighed and rejected**. §4.7 is the only place an explicit options list appears ("(a) allocate 6 × 4096-float buffers, (b) approximate"). Please add whatever you have for:

- **a. Onset detection.** Was dual-EMA energy ratio compared against alternatives (spectral flux, matched filter, simple threshold, ML classifier)? Why energy-ratio?
- **b. Why EMA rather than true sliding windows?** The report says one multiply-add per sample vs. maintaining a ring of squares — was that a measured CPU concern or an a-priori choice?
- **c. High-pass at 300 Hz.** Were other cutoffs tried? Why 2nd-order Butterworth specifically?
- **d. Array radius = 8 cm.** How was that chosen? Trade-off between angular resolution (bigger is better) and spatial aliasing / bracket size (smaller is better)? Any calculation behind it?
- **e. Semicircular arc vs. other layouts** (linear, square, circular)? Why 180° forward-only coverage rather than 360°? (I assume: the camera can't see behind it anyway, and a forward arc avoids front/back ambiguity — please confirm.)
- **f. Threading model.** Was "callback sets a flag, main thread does the DSP" (§3.5) weighed against doing the DSP on the audio thread or on a third worker thread?
- **g. FFT size 4096 (≈85 ms).** Why that value?

## Q9. Literature → implementation (mandatory section of the chapter)

The chapter must state *explicitly* how surveyed papers influenced actual code.

- **a.** **[4] Abiri & Parsayan (2020)** is already in the book's literature survey, and GCC-PHAT clearly comes from it. But that paper uses the *bullet shockwave* and two 3-D arrays to recover the shooter's *position*. SnipeIt uses a single planar array and reports *bearing only*. **Please confirm my framing:** we adopted the paper's GCC-PHAT/TDOA core but deliberately dropped the shockwave-vs-muzzle-blast distinction and the position-recovery stage, because the requirement is only "point the camera at the noise", not "locate the shooter". Anything else taken from it (or deliberately rejected)?
- **b.** **Pourmohammad & Ahadi (2012)** appears in the acoustic report's references but **is not in the project book's Chapter 13**. It will need to be added as reference **[7]**. What specifically did it contribute — the 4-mic arrangement? the PHAT weighting? Please give me 2–3 sentences I can turn into the citation's justification.
- **c. Where did SRP-PHAT itself come from?** It is not attributable to either of the above. Was there a third source (paper, textbook, a specific implementation)? If it's uncited it needs a source before it can appear in the report.
- **d.** Any other papers, application notes, or datasheets that materially shaped the design (INMP441 datasheet, RP1 documentation, ALSA/ASoC docs)?

---

## Q10. Figures and assets for the chapter

Please send whatever exists — I'm placing named placeholders in the text now and will drop these in:

| Asset | Placeholder it fills |
|---|---|
| Photo of the 4-mic array on its bracket (the real one, and/or the cardboard prototype) | `[FIG: acoustic-array-hardware]` |
| Render or screenshot of the 3D bracket model (`mics_3D_board/`, `bracket_models/`) | `[FIG: acoustic-bracket-3d]` |
| A clean diagram of the array geometry (I can redraw the ASCII one from §2 as a proper figure — just confirm the coordinates are final) | `[FIG: acoustic-array-geometry]` |
| Terminal capture of a live detection (`[EVENT] {...acoustic_event...}` lines) | `[FIG: acoustic-event-log]` |
| Raw `make sweep` output | `[TABLE: acoustic-sweep-results]` |
| A plot of error vs. azimuth, if you have the data | `[FIG: acoustic-error-vs-azimuth]` |
| Photo of the wired Pi + array during a bring-up session | `[FIG: acoustic-bringup-setup]` |

## Q11. Current state and what will realistically be finished

§6.3 says Phases 0–4 are green and Phases 5–6 are blocked on physically moving the mics to the main Pi.

- **a.** Is that still the status as of today?
- **b.** **Will Phases 5–6 be completed before the report is submitted?** This changes how I write Part B: "validated end-to-end on the integrated system" vs. "validated in simulation and on the development Pi; integration validated through Phase 4, with Phases 5–6 pending hardware transfer". Both are perfectly respectable — I just have to write the true one.
- **c.** Have the High-severity items in §9 (the `FFTW_MEASURE` back-port, the `frames_written` `int` overflow at ~12.4 h) been fixed in the workspace copy? Item 2 in particular is a genuinely good "latent defect found by review, not by testing" note for the log — **please tell me whether it was found by code review, by a long-running test, or by static analysis.**

---

## Summary — the minimum I need to write the acoustic development-log entry

If you can only answer a few things, answer these five:

1. **Q1** — why 4 mics + SRP-PHAT instead of the book's 6 mics + hyperbolic localization, and when.
2. **Q2b** — which bearing-composition formula is authoritative.
3. **Q4a/Q4b** — are the §6.1 numbers measured, and if so the raw sweep output. *(Runnable right now without any hardware.)*
4. **Priority 2** — dates, at month granularity.
5. **Q11b** — will Phases 5–6 be done before submission.

Everything else improves the chapter; these five determine whether it can be written truthfully at all.

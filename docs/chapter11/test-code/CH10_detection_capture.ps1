# CH10_detection_capture.ps1
#
# Capture helper for the Chapter 10 target-detection test.
#
# The test defines 45 cases in stage 1 alone, each observed for one minute.
# Recording and tallying those by hand is where the time goes, so this
# script prompts for the case parameters, records a 60 second screen
# capture from the tablet, and pulls it down under a structured filename.
#
# Usage, from the repository root, with the tablet connected over adb:
#
#     .\docs\chapter11\test-code\CH10_detection_capture.ps1
#
# Filenames come out as:
#     CH10_det_s1_d25_bg-vegetation_a45.mp4
# so the whole set sorts and filters cleanly afterwards.

param(
    [string]$OutDir  = "docs\chapter11\results\detection",
    [int]$Seconds    = 60
)

$ErrorActionPreference = "Stop"

# --- preflight -------------------------------------------------------------
$devices = (adb devices) -split "`n" | Where-Object { $_ -match "\tdevice$" }
if (-not $devices) {
    Write-Error "No device found. Connect the tablet and enable USB debugging."
    exit 1
}
Write-Host "Device connected." -ForegroundColor Green

if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }

# keep the screen awake for the session
adb shell settings put system screen_off_timeout 1800000 | Out-Null

$log = Join-Path $OutDir "CH10_detection_index.csv"
if (-not (Test-Path $log)) {
    "timestamp,stage,distance_m,background,angle_deg,filename,notes" |
        Set-Content -Path $log -Encoding UTF8
}

Write-Host ""
Write-Host "Chapter 10 detection capture. Ctrl+C to stop." -ForegroundColor Cyan
Write-Host "Each case records $Seconds seconds." -ForegroundColor Cyan
Write-Host ""

while ($true) {
    Write-Host ("-" * 60)
    $stage = Read-Host "Stage (1=standing, 2=moving, 3=multiple, 4=scanning), or Q to quit"
    if ($stage -match '^[Qq]') { break }

    $dist  = Read-Host "Distance in metres (10 / 25 / 40)"
    $bg    = Read-Host "Background (wall / vegetation / moving)"
    $angle = Read-Host "Subject angle in degrees (0 / 45 / 90 / 135 / 180), or - if not applicable"
    $notes = Read-Host "Notes, optional"

    $safeAngle = if ($angle -eq '-') { "na" } else { "a$angle" }
    $name = "CH10_det_s${stage}_d${dist}_bg-${bg}_${safeAngle}.mp4"
    $dest = Join-Path $OutDir $name

    if (Test-Path $dest) {
        $suffix = Get-Date -Format "HHmmss"
        $name = $name -replace '\.mp4$', "_$suffix.mp4"
        $dest = Join-Path $OutDir $name
        Write-Host "A file already existed, saving as $name" -ForegroundColor Yellow
    }

    Write-Host ""
    Write-Host "Get into position. Recording starts in 5 seconds..." -ForegroundColor Yellow
    for ($i = 5; $i -ge 1; $i--) { Write-Host "  $i"; Start-Sleep -Seconds 1 }

    Write-Host "RECORDING for $Seconds seconds" -ForegroundColor Red
    adb shell screenrecord --time-limit $Seconds /sdcard/ch10_capture.mp4
    Start-Sleep -Seconds 2

    adb pull /sdcard/ch10_capture.mp4 $dest | Out-Null
    adb shell rm /sdcard/ch10_capture.mp4 | Out-Null

    $ts = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    "$ts,$stage,$dist,$bg,$angle,$name,`"$notes`"" | Add-Content -Path $log -Encoding UTF8

    Write-Host "Saved $dest" -ForegroundColor Green
    Write-Host ""
}

Write-Host ""
Write-Host "Index written to $log" -ForegroundColor Green
Write-Host ""
Write-Host "Next: play each clip back and tally, per case:" -ForegroundColor Cyan
Write-Host "  - was the person detected at all"
Write-Host "  - the confidence values observed"
Write-Host "  - the number of false positives, meaning boxes on things that are not people"
Write-Host ""
Write-Host "Pass criteria from Chapter 10: detection in 90 percent of cases per stage,"
Write-Host "and no more than two false positives across each stage."

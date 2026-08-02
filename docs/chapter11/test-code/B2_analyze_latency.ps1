# B2_analyze_latency.ps1
#
# Computes the statistics for Chapter 11 measurement B2 from an adb logcat
# capture produced by LatencyProbe.
#
# Usage, from the repository root:
#
#     .\docs\chapter11\test-code\B2_analyze_latency.ps1 `
#         -Path docs\chapter11\results\B2_latency_raw.txt
#
# The output block is what goes into the report. Paste it as-is.

param(
    [string]$Path = "docs\chapter11\results\B2_latency_raw.txt",
    [string]$OutFile = "docs\chapter11\results\B2_latency_summary.txt"
)

if (-not (Test-Path $Path)) {
    Write-Error "Capture file not found: $Path"
    Write-Host ""
    Write-Host "Collect it first with:" -ForegroundColor Yellow
    Write-Host "    adb logcat -c"
    Write-Host "    adb logcat -s SnipeItLatency > $Path"
    exit 1
}

$values = Select-String -Path $Path -Pattern 'latency_ms=([0-9]+\.?[0-9]*)' -AllMatches |
          ForEach-Object { $_.Matches } |
          ForEach-Object { [double]$_.Groups[1].Value }

if ($values.Count -eq 0) {
    Write-Error "No 'latency_ms=' lines found in $Path."
    Write-Host "Check that LatencyProbe is installed and that MOCK MODE was running."
    exit 1
}

$sorted = $values | Sort-Object
$n      = $sorted.Count

function Percentile([double[]]$s, [double]$p) {
    $i = [math]::Ceiling($p * $s.Count) - 1
    if ($i -lt 0) { $i = 0 }
    if ($i -ge $s.Count) { $i = $s.Count - 1 }
    return $s[$i]
}

$mean   = ($sorted | Measure-Object -Average).Average
$sd     = if ($n -gt 1) {
              [math]::Sqrt((($sorted | ForEach-Object { [math]::Pow($_ - $mean, 2) } |
                             Measure-Object -Sum).Sum) / ($n - 1))
          } else { 0 }

$lines = @()
$lines += "=" * 62
$lines += "בדיקה B2 - השהיית עדכון פתרון הירי"
$lines += "מקור הנתונים: $Path"
$lines += "=" * 62
$lines += ("מספר דגימות      : {0}" -f $n)
$lines += ("ממוצע            : {0:N3} ms" -f $mean)
$lines += ("חציון            : {0:N3} ms" -f (Percentile $sorted 0.50))
$lines += ("אחוזון 95        : {0:N3} ms" -f (Percentile $sorted 0.95))
$lines += ("מקסימום          : {0:N3} ms" -f $sorted[-1])
$lines += ("מינימום          : {0:N3} ms" -f $sorted[0])
$lines += ("סטיית תקן        : {0:N3} ms" -f $sd)
$lines += "-" * 62
$lines += ("הדרישה הלא-פונקציונלית: עד 2000 ms")
$lines += ("תוצאה: {0}" -f $(if ($sorted[-1] -lt 2000) { "עומד בדרישה" } else { "אינו עומד בדרישה" }))
$lines += "=" * 62
$lines += ""
$lines += "הסתייגות שיש לכתוב בפרק: המדידה מבודדת את חלקה של האפליקציה בלבד."
$lines += "ההשהיה מקצה לקצה כוללת גם את מחזור הדגימה בצד המכשיר ואת זמן הרשת."

$lines | ForEach-Object { Write-Host $_ }

$dir = Split-Path $OutFile -Parent
if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
$lines -join "`r`n" | Set-Content -Path $OutFile -Encoding UTF8

Write-Host ""
Write-Host "Summary written to $OutFile" -ForegroundColor Green

param(
    [int]$Runs = 20,
    [int]$Warmup = 5
)

$adb = "D:\AS\SDK\platform-tools\adb.exe"

Write-Host "=== CPoseMouse Benchmark ===" -ForegroundColor Cyan
Write-Host "Runs=$Runs Warmup=$Warmup"

& $adb shell "settings put global app_logging_enabled 1" 2>$null
& $adb logcat -c
& $adb shell am force-stop com.CPoseMouse
Start-Sleep -Milliseconds 500

Write-Host "Starting benchmark..." -ForegroundColor Yellow
& $adb shell am start -a com.CPoseMouse.ACTION_BENCHMARK -n com.CPoseMouse/.MainActivity

$found = $false
$timeout = 60
$startTime = Get-Date

while (((Get-Date) - $startTime).TotalSeconds -lt $timeout) {
    Start-Sleep -Seconds 1
    $log = & $adb logcat -d -s "NcnnEngine:*" 2>$null
    $match = $log | Select-String "BENCHMARK_RESULT:"
    if ($match) {
        $line = $match.Line
        if ($line -match "avg=([0-9.]+)ms\s+FPS=([0-9.]+)") {
            $avgMs = $matches[1]
            $fps = $matches[2]
            Write-Host ""
            Write-Host "=== RESULT ===" -ForegroundColor Green
            Write-Host "  Avg: ${avgMs}ms  FPS: $fps" -ForegroundColor Green
            Write-Host "=============" -ForegroundColor Green
            $found = $true
            break
        }
    }
}

if (-not $found) {
    Write-Host "Timeout or failed. Raw log:" -ForegroundColor Red
    & $adb logcat -d -s "NcnnEngine:*" | Select-String "BENCHMARK|loadModel|model loaded"
}

& $adb shell am force-stop com.CPoseMouse
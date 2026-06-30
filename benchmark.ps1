param(
    [int]$Duration = 60,
    [int]$Interval = 2
)

$adb = "D:\AS\SDK\platform-tools\adb.exe"

Write-Host "=== CPoseMouse 持续FPS监测 ===" -ForegroundColor Cyan
Write-Host "时长=${Duration}s  采样间隔=${Interval}s"

& $adb shell "settings put global app_logging_enabled 1" 2>$null
& $adb logcat -c
& $adb shell am force-stop com.CPoseMouse
Start-Sleep -Milliseconds 500

Write-Host "启动benchmark..." -ForegroundColor Yellow
& $adb shell am start -a com.CPoseMouse.ACTION_BENCHMARK -n com.CPoseMouse/.MainActivity

$started = $false
$startTime = Get-Date
$history = @()

while (((Get-Date) - $startTime).TotalSeconds -lt ($Duration + 30)) {
    Start-Sleep -Seconds $Interval
    $log = & $adb logcat -d -s "NcnnEngine:*" 2>$null

    $startLine = $log | Select-String "BENCH_C: start" | Select-Object -Last 1
    if ($startLine -and -not $started) {
        Write-Host "推理已启动，开始监测..." -ForegroundColor Green
        $started = $true
    }

    $dataLine = $log | Select-String "BENCH_C: t=" | Select-Object -Last 1
    if ($dataLine) {
        $line = $dataLine.Line
        if ($line -match "t=(\d+)s frames=(\d+) avg=([0-9.]+)ms instFPS=([0-9.]+) overallFPS=([0-9.]+)") {
            $t = $matches[1]
            $frames = $matches[2]
            $avg = $matches[3]
            $instFps = $matches[4]
            $overallFps = $matches[5]
            $ts = Get-Date -Format "HH:mm:ss"
            Write-Host "[$ts] t=${t}s frames=$frames avg=${avg}ms instFPS=$instFps overallFPS=$overallFps" -ForegroundColor White
            $history += [PSCustomObject]@{Time=$ts; Sec=$t; Frames=$frames; AvgMs=$avg; InstFps=$instFps; OverallFps=$overallFps}
        }
    }

    $doneLine = $log | Select-String "BENCH_C_DONE:" | Select-Object -Last 1
    if ($doneLine) {
        $line = $doneLine.Line
        if ($line -match "frames=(\d+) avg=([0-9.]+)ms FPS=([0-9.]+) duration=([0-9.]+)") {
            Write-Host ""
            Write-Host "========== 最终结果 ==========" -ForegroundColor Green
            Write-Host "  总帧数: $($matches[1])" -ForegroundColor Green
            Write-Host "  平均延迟: $($matches[2])ms" -ForegroundColor Green
            Write-Host "  平均FPS: $($matches[3])" -ForegroundColor Green
            Write-Host "  持续时长: $($matches[4])s" -ForegroundColor Green
            Write-Host "===============================" -ForegroundColor Green
        }

        if ($history.Count -ge 3) {
            $fpsVals = $history | ForEach-Object { [double]$_.OverallFps }
            $minFps = ($fpsVals | Measure-Object -Minimum).Minimum
            $maxFps = ($fpsVals | Measure-Object -Maximum).Maximum
            $avgFps = ($fpsVals | Measure-Object -Average).Average
            Write-Host ""
            Write-Host "========== 稳定性分析 ==========" -ForegroundColor Cyan
            Write-Host "  采样次数: $($history.Count)" -ForegroundColor Cyan
            Write-Host "  FPS范围: $minFps ~ $maxFps" -ForegroundColor Cyan
            Write-Host "  FPS均值: $([math]::Round($avgFps, 1))" -ForegroundColor Cyan
            Write-Host "  FPS波动: $([math]::Round($maxFps - $minFps, 1))" -ForegroundColor Cyan
            Write-Host "================================" -ForegroundColor Cyan
        }
        break
    }
}

& $adb shell am force-stop com.CPoseMouse

# Review-only driver for runner_t.ps1 scenarios. Everything stays under hlv2-adoption (copy + temp lock folders).
param([Parameter(Mandatory = $true)][string]$Scenario)
$B = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\hlv2-adoption"
$CODE = "$B\code\heavy_lock.py"
$PY = "C:\Python313\python.exe"
$PWSH = "C:\Program Files\PowerShell\7\pwsh.exe"
if ($CODE -like "*ff9f2caf*") { throw "live path" }

function Fresh([string]$tag) {
    $d = "$B\locks\$tag"
    if (Test-Path $d) { Remove-Item $d -Recurse -Force }
    New-Item -ItemType Directory -Force $d | Out-Null
    return $d
}
function Rec([string]$d) { $f = "$d\heavy-build.lock"; if (Test-Path $f) { Get-Content $f -Raw } else { "<no lock file>" } }
function Kids([int]$id) { @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$id" | Select-Object ProcessId, Name, CommandLine) }
function Alive([int]$id) { [bool](Get-Process -Id $id -ErrorAction SilentlyContinue) }
function StartRunner([string]$d, [string]$log, [string]$hbMode, [int]$lease, [int]$every, [int]$jobSec, [string]$hostExe = $PWSH) {
    $a = @('-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', "$B\runner_t.ps1", '-Lock', $CODE, '-Dir', $d,
           '-Log', $log, '-Heartbeat', $hbMode, '-Lease', "$lease", '-Every', "$every", '-JobSeconds', "$jobSec")
    return Start-Process $hostExe -ArgumentList $a -PassThru -WindowStyle Hidden
}
function StartWaiter([string]$d, [string]$name) {
    $env:SHARED_LOCK_DIR = $d; $env:SHARED_LOCK_POLL_SECONDS = "0.2"
    $w = Start-Process $PY -ArgumentList '-I', $CODE, 'acquire', $name, 'next-heavy-job' -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput "$d\$name.out" -RedirectStandardError "$d\$name.err"
    Remove-Item Env:SHARED_LOCK_DIR, Env:SHARED_LOCK_POLL_SECONDS
    return $w
}
function WaitLog([string]$log, [string]$pattern, [int]$sec) {
    $t = [DateTime]::UtcNow.AddSeconds($sec)
    while ([DateTime]::UtcNow -lt $t) { if ((Test-Path $log) -and (Select-String -Path $log -Pattern $pattern -Quiet)) { return $true }; Start-Sleep -Milliseconds 200 }
    return $false
}
function Status([string]$d) {
    $env:SHARED_LOCK_DIR = $d
    $s = & $PY -I $CODE status 2>&1
    Remove-Item Env:SHARED_LOCK_DIR
    $s | Select-Object -Skip 1 | ForEach-Object { "    status| $_" }
}
function Now { (Get-Date).ToUniversalTime().ToString("HH:mm:ss.fff") }

switch ($Scenario) {
    "S1-readme-job-plainkill" {
        $d = Fresh "s1"; $log = "$d\runner.log"
        $r = StartRunner $d $log "job" 6 2 120
        if (-not (WaitLog $log "\[job \d+\] hb rc=0" 40)) { "no job heartbeat seen"; Get-Content $log; break }
        $kids = Kids $r.Id
        "$(Now) runner pid=$($r.Id) children:"; $kids | ForEach-Object { "    child $($_.ProcessId) $($_.Name)" }
        "$(Now) Stop-Process -Force runner $($r.Id) (TerminateProcess, no tree)"
        $before = @(Get-Content $log | Select-String "\[job \d+\] hb rc=").Count
        Stop-Process -Id $r.Id -Force
        Start-Sleep -Seconds 10
        "$(Now) 10 s later: runner alive=$(Alive $r.Id)"
        $kids | ForEach-Object { "    child $($_.ProcessId) $($_.Name) alive=$(Alive $_.ProcessId)" }
        $afterCount = @(Get-Content $log | Select-String "\[job \d+\] hb rc=").Count
        "    job heartbeat lines: $before at the kill, $afterCount ten seconds later"
        Get-Content $log | Select-Object -Last 4 | ForEach-Object { "    log| $_" }
        Status $d
        $w = StartWaiter $d "W"
        Start-Sleep -Seconds 15
        "$(Now) waiter after 15 s (lease 6 s, holder runner dead): " + $(if (Alive $w.Id) { "STILL WAITING" } else { "exited " + $w.ExitCode })
        Get-Content "$d\W.err" -ErrorAction SilentlyContinue | Select-Object -First 2 | ForEach-Object { "    W.err| $_" }
        Status $d
        $kids | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
        if (Alive $w.Id) { Stop-Process -Id $w.Id -Force }
        Start-Sleep -Seconds 4
        "$(Now) after killing the leftover children:"; Get-Content $log | Select-Object -Last 2 | ForEach-Object { "    log| $_" }
    }
    "S2-inproc-plainkill" {
        $d = Fresh "s2"; $log = "$d\runner.log"
        $r = StartRunner $d $log "inproc" 6 2 60
        if (-not (WaitLog $log "hb rc=0" 40)) { "no heartbeat seen"; Get-Content $log; break }
        $kids = Kids $r.Id
        "$(Now) runner pid=$($r.Id) children:"; $kids | ForEach-Object { "    child $($_.ProcessId) $($_.Name)" }
        $mvnPid = ($kids | Where-Object Name -eq "python.exe" | Select-Object -First 1).ProcessId
        "$(Now) Stop-Process -Force runner $($r.Id) (no tree)"
        Stop-Process -Id $r.Id -Force
        $w = StartWaiter $d "W"
        $t0 = [DateTime]::UtcNow
        while ((Alive $w.Id) -and ([DateTime]::UtcNow - $t0).TotalSeconds -lt 30) { Start-Sleep -Milliseconds 200 }
        "$(Now) waiter: " + $(if (Alive $w.Id) { "STILL WAITING after 30 s" } else { "exit $($w.ExitCode) after {0:N1} s" -f ([DateTime]::UtcNow - $t0).TotalSeconds })
        Get-Content "$d\W.out" -ErrorAction SilentlyContinue | ForEach-Object { "    W.out| $_" }
        Get-Content "$d\W.err" -ErrorAction SilentlyContinue | ForEach-Object { "    W.err| $_" }
        "$(Now) orphaned 'Maven' stand-in pid=$mvnPid alive=$(Alive $mvnPid)   <- still running while W holds the lock"
        Get-Content "$d\heavy-build.lock.served.log" | ForEach-Object { "    served| $_" }
        Status $d
        Stop-Process -Id $mvnPid -Force -ErrorAction SilentlyContinue
        if (Alive $w.Id) { Stop-Process -Id $w.Id -Force }
    }
    "S3-inproc-treekill" {
        $d = Fresh "s3"; $log = "$d\runner.log"
        $r = StartRunner $d $log "inproc" 6 2 60
        if (-not (WaitLog $log "hb rc=0" 40)) { "no heartbeat seen"; Get-Content $log; break }
        $kids = Kids $r.Id
        $mvnPid = ($kids | Where-Object Name -eq "python.exe" | Select-Object -First 1).ProcessId
        "$(Now) taskkill /T /F runner $($r.Id)"
        & taskkill /T /F /PID $r.Id | Out-Null
        $w = StartWaiter $d "W"
        $t0 = [DateTime]::UtcNow
        while ((Alive $w.Id) -and ([DateTime]::UtcNow - $t0).TotalSeconds -lt 30) { Start-Sleep -Milliseconds 200 }
        "$(Now) waiter: " + $(if (Alive $w.Id) { "STILL WAITING after 30 s" } else { "exit $($w.ExitCode) after {0:N1} s" -f ([DateTime]::UtcNow - $t0).TotalSeconds })
        Get-Content "$d\W.err" -ErrorAction SilentlyContinue | ForEach-Object { "    W.err| $_" }
        "$(Now) 'Maven' stand-in pid=$mvnPid alive=$(Alive $mvnPid)"
        Get-Content "$d\heavy-build.lock.served.log" | ForEach-Object { "    served| $_" }
        if (Alive $w.Id) { Stop-Process -Id $w.Id -Force }
    }
    "S4-queued-plainkill" {
        $d = Fresh "s4"; $log = "$d\runner.log"
        $env:SHARED_LOCK_DIR = $d
        & $PY -I $CODE acquire H "blocking holder (old-style)" | Out-Null
        Remove-Item Env:SHARED_LOCK_DIR
        $r = StartRunner $d $log "inproc" 8 2 30
        $t0 = [DateTime]::UtcNow
        while (-not (Get-ChildItem "$d\heavy-build.lock.queue" -ErrorAction SilentlyContinue | Where-Object Name -like "*-customwf-t-*") -and ([DateTime]::UtcNow - $t0).TotalSeconds -lt 30) { Start-Sleep -Milliseconds 200 }
        $acq = (Kids $r.Id | Where-Object Name -eq "python.exe" | Select-Object -First 1).ProcessId
        "$(Now) runner pid=$($r.Id) queued; its acquire child pid=$acq"
        "$(Now) Stop-Process -Force runner (cancel a queued run, no tree)"
        Stop-Process -Id $r.Id -Force
        Start-Sleep -Seconds 2
        "$(Now) runner alive=$(Alive $r.Id); orphaned acquire alive=$(Alive $acq)"
        $env:SHARED_LOCK_DIR = $d
        & $PY -I $CODE release H | ForEach-Object { "    $(Now) release H| $_" }
        Remove-Item Env:SHARED_LOCK_DIR
        Start-Sleep -Seconds 2
        "$(Now) lock after H released (runner is dead):"; "    " + (Rec $d)
        Status $d
        Get-Content $log | ForEach-Object { "    runner.log| $_" }
        $w = StartWaiter $d "W"
        $t1 = [DateTime]::UtcNow
        while ((Alive $w.Id) -and ([DateTime]::UtcNow - $t1).TotalSeconds -lt 30) { Start-Sleep -Milliseconds 200 }
        "$(Now) waiter: " + $(if (Alive $w.Id) { "STILL WAITING after 30 s" } else { "exit $($w.ExitCode) after {0:N1} s (machine idle all that time)" -f ([DateTime]::UtcNow - $t1).TotalSeconds })
        Get-Content "$d\W.err" -ErrorAction SilentlyContinue | Select-Object -First 2 | ForEach-Object { "    W.err| $_" }
        if (Alive $w.Id) { Stop-Process -Id $w.Id -Force }
        if (Alive $acq) { Stop-Process -Id $acq -Force }
    }
    "S6-readme-job-plainkill-winps51" {
        $d = Fresh "s6"; $log = "$d\runner.log"
        $r = StartRunner $d $log "job" 6 2 120 "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
        if (-not (WaitLog $log "\[job \d+\] hb rc=0" 60)) { "no job heartbeat seen"; Get-Content $log; break }
        $kids = Kids $r.Id
        "$(Now) Windows PowerShell 5.1 runner pid=$($r.Id) children:"; $kids | ForEach-Object { "    child $($_.ProcessId) $($_.Name)" }
        $before = @(Get-Content $log | Select-String "\[job \d+\] hb rc=").Count
        "$(Now) Stop-Process -Force runner (no tree)"
        Stop-Process -Id $r.Id -Force
        Start-Sleep -Seconds 10
        $afterCount = @(Get-Content $log | Select-String "\[job \d+\] hb rc=").Count
        "$(Now) 10 s later: runner alive=$(Alive $r.Id); job heartbeat lines: $before at the kill, $afterCount now"
        $kids | ForEach-Object { "    child $($_.ProcessId) $($_.Name) alive=$(Alive $_.ProcessId)" }
        $kids | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    }
    "S5-normal" {
        foreach ($mode in "inproc", "job") {
            $d = Fresh "s5-$mode"; $log = "$d\runner.log"
            $r = StartRunner $d $log $mode 6 2 7
            $r.WaitForExit(60000) | Out-Null
            "=== mode=$mode runner exit=$($r.ExitCode)"
            Get-Content $log | ForEach-Object { "    log| $_" }
            Start-Sleep -Seconds 3
            $stray = @(Get-CimInstance Win32_Process -Filter "Name='pwsh.exe'" | Where-Object { $_.CommandLine -like "*-s -NoLogo*" -or $_.CommandLine -like "*-s -NoProfile*" })
            "    lock file: $(Rec $d)"
        }
    }
}

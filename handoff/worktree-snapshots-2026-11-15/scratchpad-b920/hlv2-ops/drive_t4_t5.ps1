# Test-only driver: PowerShell detached-runner failure modes against TEMP lock dirs under hlv2-ops\locks (never the live lock).
param([string[]]$Tests = @('T4', 'T5'))
$B = 'C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\hlv2-ops'   # 8.3 form: Start-Process does not quote arguments with spaces
$code = Join-Path $B 'code\heavy_lock.py'
$PY = 'C:\Python313\python.exe'
function Fresh([string]$name) {
    $d = Join-Path $B "locks\$name"
    if ($d -match 'ff9f2caf') { throw 'live dir' }
    if (Test-Path $d) { Get-ChildItem $d -Recurse -Force | Sort-Object FullName -Descending | ForEach-Object { $_.Delete() } }
    New-Item -ItemType Directory -Force $d | Out-Null
    $env:SHARED_LOCK_DIR = $d; $env:SHARED_LOCK_POLL_SECONDS = '0.2'
    return $d
}
function Lock([string[]]$a) { $o = & $PY -I $code @a 2>&1; return "rc=$LASTEXITCODE " + (($o | ForEach-Object { "$_" }) -join ' | ') }
function Rec([string]$d) { try { Get-Content (Join-Path $d 'heavy-build.lock') -Raw -ErrorAction Stop | ConvertFrom-Json } catch { $null } }
function Kids([int]$id) { @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$id") }
function Sleeper { Start-Process $PY -ArgumentList '-I', '-c', '"import time; time.sleep(300)"' -PassThru -WindowStyle Hidden }

if ($Tests -contains 'T4') {
    foreach ($mode in 'redirect', 'pipe') {
        "===== T4[$mode]: runner killed while QUEUED (Stop-Process on the runner pid only)"
        $d = Fresh "T4_$mode"
        $sh = Sleeper
        "blocker H: " + (Lock @('acquire', 'H', 'blocker', '--pid', "$($sh.Id)", '--lease', '600'))
        $log = Join-Path $d 'runner.log'
        $r = Start-Process pwsh -ArgumentList '-NoProfile', '-File', (Join-Path $B 'runner_queued.ps1'), '-Lock', $code, '-Dir', $d, '-Log', $log, '-Mode', $mode -PassThru -WindowStyle Hidden
        $t0 = Get-Date
        while (-not (Get-ChildItem (Join-Path $d 'heavy-build.lock.queue') -ErrorAction SilentlyContinue | Where-Object Name -like '*-R-*') -and ((Get-Date) - $t0).TotalSeconds -lt 30) { Start-Sleep -Milliseconds 100 }
        $acq = Kids $r.Id | Where-Object { $_.CommandLine -match 'acquire R' }
        "runner pid $($r.Id) queued; its acquire child pid $($acq.ProcessId)"
        Stop-Process -Id $r.Id -Force
        Start-Sleep -Seconds 1
        "after killing the runner: acquire child alive = " + [bool](Get-Process -Id $acq.ProcessId -ErrorAction SilentlyContinue)
        "release H: " + (Lock @('release', 'H'))
        Start-Sleep -Seconds 4
        $rec = Rec $d
        "record now: owner=$($rec.owner) holder_pid=$($rec.holder_pid) holder_start=$($rec.holder_start) lease_seconds=$($rec.lease_seconds)  (runner pid was $($r.Id), dead)"
        "orphan acquire still alive = " + [bool](Get-Process -Id $acq.ProcessId -ErrorAction SilentlyContinue)
        $st = & $PY -I $code status 2>&1; "status: " + (($st | Select-Object -Skip 1 -First 2) -join ' | ')
        "runner.log: " + ((Get-Content $log -ErrorAction SilentlyContinue) -join ' | ')
        $w = Start-Process $PY -ArgumentList '-I', "`"$code`"", 'acquire', 'W', '"next job"' -PassThru -WindowStyle Hidden -RedirectStandardError (Join-Path $d 'W.err') -RedirectStandardOutput (Join-Path $d 'W.out')
        Start-Sleep -Seconds 4
        "next waiter W after 4 s: still waiting=" + (-not $w.HasExited) + "; stderr: " + ((Get-Content (Join-Path $d 'W.err') -ErrorAction SilentlyContinue) -join ' | ')
        foreach ($p in @($w.Id, $sh.Id, $acq.ProcessId)) { Stop-Process -Id $p -Force -ErrorAction SilentlyContinue }
    }
}

if ($Tests -contains 'T4c') {
    "===== T4c: runner launched like sl45 (Win32_Process.Create, no handle kept), killed while QUEUED with taskkill /F (no /T)"
    $d = Fresh 'T4c'
    $sh = Sleeper
    "blocker H: " + (Lock @('acquire', 'H', 'blocker', '--pid', "$($sh.Id)", '--lease', '600'))
    $log = Join-Path $d 'runner.log'
    $cmd = "pwsh -NoProfile -File $(Join-Path $B 'runner_queued.ps1') -Lock $code -Dir $d -Log $log -Mode pipe"
    $res = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = $cmd }
    $rid = [int]$res.ProcessId
    $t0 = Get-Date
    while (-not (Get-ChildItem (Join-Path $d 'heavy-build.lock.queue') -ErrorAction SilentlyContinue | Where-Object Name -like '*-R-*') -and ((Get-Date) - $t0).TotalSeconds -lt 30) { Start-Sleep -Milliseconds 100 }
    $acq = Kids $rid | Where-Object { $_.CommandLine -match 'acquire R' }
    "runner pid $rid queued (WMI-created); acquire child $($acq.ProcessId)"
    $null = & taskkill /F /PID $rid 2>&1
    Start-Sleep -Seconds 1
    "release H: " + (Lock @('release', 'H'))
    Start-Sleep -Seconds 4
    $rec = Rec $d
    "record now: owner=$($rec.owner) holder_pid=$($rec.holder_pid) holder_start=[$($rec.holder_start)] lease_seconds=$($rec.lease_seconds)"
    $st = & $PY -I $code status 2>&1; "status: " + (($st | Select-Object -Skip 1 -First 2) -join ' | ')
    Stop-Process -Id $sh.Id -Force -ErrorAction SilentlyContinue
    # the reuse step, planted: a running process now owns that pid number and holder_start is null -> judged alive forever
    $imp = Sleeper
    $raw = Get-Content (Join-Path $d 'heavy-build.lock') -Raw | ConvertFrom-Json
    $raw.holder_pid = $imp.Id; $raw.lease_until = [double]([DateTimeOffset]::UtcNow.ToUnixTimeSeconds() - 60)
    ($raw | ConvertTo-Json -Compress) | Set-Content -NoNewline -Encoding ascii (Join-Path $d 'heavy-build.lock')
    $st = & $PY -I $code status 2>&1; "planted reuse (holder_start null, pid now a live unrelated process, lease expired): " + (($st | Select-Object -Skip 1 -First 2) -join ' | ')
    $w = Start-Process $PY -ArgumentList '-I', "`"$code`"", 'acquire', 'W', '"next job"' -PassThru -WindowStyle Hidden -RedirectStandardError (Join-Path $d 'W.err') -RedirectStandardOutput (Join-Path $d 'W.out')
    Start-Sleep -Seconds 4
    "waiter after 4 s: still waiting=" + (-not $w.HasExited) + "; stderr: " + ((Get-Content (Join-Path $d 'W.err') -ErrorAction SilentlyContinue) -join ' | ')
    foreach ($p in @($w.Id, $imp.Id)) { Stop-Process -Id $p -Force -ErrorAction SilentlyContinue }
}

if ($Tests -contains 'T5') {
    "===== T5: runner killed MID-JOB (runner pid only); its child job keeps running; lock auto-released after the lease"
    $d = Fresh 'T5'
    $log = Join-Path $d 'runner.log'
    $r = Start-Process pwsh -ArgumentList '-NoProfile', '-File', (Join-Path $B 'runner_job.ps1'), '-Lock', $code, '-Dir', $d, '-Log', $log, '-Lease', '3' -PassThru -WindowStyle Hidden
    $t0 = Get-Date
    while (-not (Test-Path (Join-Path $d 'maven.alive')) -and ((Get-Date) - $t0).TotalSeconds -lt 30) { Start-Sleep -Milliseconds 100 }
    $mvn = Kids $r.Id | Where-Object { $_.CommandLine -match 'maven.alive' }
    "runner $($r.Id) holds the lock (owner $((Rec $d).owner)); stand-in Maven child pid $($mvn.ProcessId)"
    Stop-Process -Id $r.Id -Force
    "runner killed; Maven child alive = " + [bool](Get-Process -Id $mvn.ProcessId -ErrorAction SilentlyContinue)
    $w = Start-Process $PY -ArgumentList '-I', "`"$code`"", 'acquire', 'W', '"next heavy job"' -PassThru -WindowStyle Hidden -RedirectStandardError (Join-Path $d 'W.err') -RedirectStandardOutput (Join-Path $d 'W.out')
    $t1 = Get-Date
    while (-not $w.HasExited -and ((Get-Date) - $t1).TotalSeconds -lt 20) { Start-Sleep -Milliseconds 200 }
    "waiter W finished=$($w.HasExited) after $([int]((Get-Date) - $t1).TotalSeconds) s; stdout: " + ((Get-Content (Join-Path $d 'W.out') -ErrorAction SilentlyContinue) -join ' | ')
    "W stderr: " + ((Get-Content (Join-Path $d 'W.err') -ErrorAction SilentlyContinue) -join ' | ')
    "lock owner now: $((Rec $d).owner); orphaned Maven child still alive = " + [bool](Get-Process -Id $mvn.ProcessId -ErrorAction SilentlyContinue) + "  => two heavy jobs at once"
    "runner.log: " + ((Get-Content $log -ErrorAction SilentlyContinue) -join ' | ')
    Stop-Process -Id $mvn.ProcessId -Force -ErrorAction SilentlyContinue
    Lock @('release', 'W') | Out-Null

    "===== T5b: same, but the runner is stopped as a TREE (taskkill /T /F)"
    $d = Fresh 'T5b'
    $log = Join-Path $d 'runner.log'
    $r = Start-Process pwsh -ArgumentList '-NoProfile', '-File', (Join-Path $B 'runner_job.ps1'), '-Lock', $code, '-Dir', $d, '-Log', $log, '-Lease', '3' -PassThru -WindowStyle Hidden
    $t0 = Get-Date
    while (-not (Test-Path (Join-Path $d 'maven.alive')) -and ((Get-Date) - $t0).TotalSeconds -lt 30) { Start-Sleep -Milliseconds 100 }
    $mvn = Kids $r.Id | Where-Object { $_.CommandLine -match 'maven.alive' }
    $tk = & taskkill /T /F /PID $r.Id 2>&1
    Start-Sleep -Seconds 1
    "taskkill /T: Maven child alive = " + [bool](Get-Process -Id $mvn.ProcessId -ErrorAction SilentlyContinue) + "; lock owner = $((Rec $d).owner) (released only after the lease, by the next waiter)"
    Lock @('release', 'R5') | Out-Null
}

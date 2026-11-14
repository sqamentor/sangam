# Test-only driver for runner_recipe.ps1 against TEMP lock dirs under hlv2-ops\locks (never the live lock).
param([string[]]$Tests = @('R1', 'R2', 'R3', 'R4', 'R5'))
$B = 'C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\hlv2-ops'
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
function Owner([string]$d) { try { (Get-Content (Join-Path $d 'heavy-build.lock') -Raw -ErrorAction Stop | ConvertFrom-Json).owner } catch { '<none>' } }
function Kids([int]$id) { @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$id") }
function Runner([string]$d, [string[]]$extra) {
    $log = Join-Path $d 'runner.log'
    $a = @('-NoProfile', '-File', (Join-Path $B 'runner_recipe.ps1'), '-Lock', $code, '-Dir', $d, '-Log', $log) + $extra
    return Start-Process pwsh -ArgumentList $a -PassThru -WindowStyle Hidden
}
function WaitFor([scriptblock]$cond, [int]$sec) { $t = Get-Date; while (-not (& $cond) -and ((Get-Date) - $t).TotalSeconds -lt $sec) { Start-Sleep -Milliseconds 100 }; return (& $cond) }
function ShowLog([string]$d) { (Get-Content (Join-Path $d 'runner.log') -ErrorAction SilentlyContinue | ForEach-Object { '    ' + ($_ -replace '^\S+ ', '') }) -join "`n" }

if ($Tests -contains 'R1') {
    "===== R1: happy path (job 8 s, heartbeat every 2 s, lease 6 s)"
    $d = Fresh 'R1'
    $r = Runner $d @('-JobSeconds', '8', '-BeatSeconds', '2', '-Lease', '6')
    $null = WaitFor { $r.HasExited } 60
    "runner exit code: $($r.ExitCode); lock owner after: $(Owner $d)"
    ShowLog $d
    "served: " + ((Get-Content (Join-Path $d 'heavy-build.lock.served.log')) -join ' | ')
}
if ($Tests -contains 'R2') {
    "===== R2: runner killed mid-job with Stop-Process on the runner pid ONLY (Job Object on)"
    $d = Fresh 'R2'
    $r = Runner $d @('-JobSeconds', '120', '-BeatSeconds', '2', '-Lease', '4')
    $null = WaitFor { Test-Path (Join-Path $d 'maven.alive') } 40
    $mvn = Kids $r.Id | Where-Object { $_.CommandLine -match 'maven.alive' }
    Stop-Process -Id $r.Id -Force
    Start-Sleep -Seconds 1
    "after killing runner $($r.Id): job child $($mvn.ProcessId) alive = " + [bool](Get-Process -Id $mvn.ProcessId -ErrorAction SilentlyContinue)
    $w = Start-Process $PY -ArgumentList '-I', "`"$code`"", 'acquire', 'W', '"next heavy job"' -PassThru -WindowStyle Hidden -RedirectStandardError (Join-Path $d 'W.err') -RedirectStandardOutput (Join-Path $d 'W.out')
    $null = WaitFor { $w.HasExited } 20
    "next waiter: finished=$($w.HasExited) stdout=" + ((Get-Content (Join-Path $d 'W.out') -ErrorAction SilentlyContinue) -join ' | ') + " | stderr=" + ((Get-Content (Join-Path $d 'W.err') -ErrorAction SilentlyContinue) -join ' | ')
    Lock @('release', 'W') | Out-Null
}
if ($Tests -contains 'R3') {
    "===== R3: lock lost while running (a stray 'release <owner>' by name, then job Y acquires) -> runner stops its job"
    $d = Fresh 'R3'
    $r = Runner $d @('-JobSeconds', '120', '-BeatSeconds', '2')
    $null = WaitFor { Test-Path (Join-Path $d 'maven.alive') } 40
    $mvn = Kids $r.Id | Where-Object { $_.CommandLine -match 'maven.alive' }
    "stray release: " + (Lock @('release', 'customwf-test'))
    "job Y acquire: " + (Lock @('acquire', 'Y', 'other heavy job'))
    $null = WaitFor { $r.HasExited } 40
    "runner exited=$($r.HasExited); its job child alive = " + [bool](Get-Process -Id $mvn.ProcessId -ErrorAction SilentlyContinue) + "; owner now: $(Owner $d)"
    ShowLog $d
    Lock @('release', 'Y') | Out-Null
}
if ($Tests -contains 'R4') {
    "===== R4: runner killed while QUEUED (Job Object on): no orphan acquire may take the lock"
    $d = Fresh 'R4'
    "blocker H: " + (Lock @('acquire', 'H', 'blocker'))
    $r = Runner $d @('-JobSeconds', '30', '-BeatSeconds', '2')
    $null = WaitFor { Get-ChildItem (Join-Path $d 'heavy-build.lock.queue') -ErrorAction SilentlyContinue | Where-Object Name -like '*-customwf-test-*' } 40
    $acq = Kids $r.Id | Where-Object { $_.CommandLine -match 'acquire' }
    Stop-Process -Id $r.Id -Force
    Start-Sleep -Seconds 1
    "after killing the queued runner: its acquire child $($acq.ProcessId) alive = " + [bool](Get-Process -Id $acq.ProcessId -ErrorAction SilentlyContinue)
    "release H: " + (Lock @('release', 'H'))
    Start-Sleep -Seconds 3
    "owner after 3 s: $(Owner $d)  (none = no orphan grant)"
}

if ($Tests -contains 'R5') {
    "===== R5: watchdog (job would run 60 s; MaxSeconds 6; beat 2 s)"
    $d = Fresh 'R5'
    $r = Runner $d @('-JobSeconds', '60', '-BeatSeconds', '2', '-MaxSeconds', '6')
    $null = WaitFor { $r.HasExited } 60
    "runner exited=$($r.HasExited); owner after: $(Owner $d)"
    ShowLog $d
}

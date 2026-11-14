# Review-only driver (HLV2-AD-04): README PowerShell recipe, holder host killed abruptly; does the Start-Job heartbeat
# outlive the holder and keep its lock? COPY of heavy_lock v2 + temp lock folders under hlv2-refute-ad04 only.
param([string[]]$Kills = @('host-only', 'tree'), [int]$WatchSeconds = 30)
$B = 'C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\hlv2-refute-ad04'
$CODE = "$B\code\heavy_lock.py"
$PY = 'C:\Python313\python.exe'
if ($B -match 'ff9f2caf') { throw 'live' }

function Fresh([string]$name) {
    $d = "$B\locks\$name"
    if ($d -match 'ff9f2caf') { throw 'live dir' }
    if (Test-Path $d) { Remove-Item $d -Recurse -Force }
    New-Item -ItemType Directory -Force $d | Out-Null
    return $d
}
function Rec([string]$d) { try { Get-Content "$d\heavy-build.lock" -Raw -ErrorAction Stop | ConvertFrom-Json } catch { $null } }
function Now { [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() / 1000.0 }
function Alive([int]$id) { [bool](Get-Process -Id $id -ErrorAction SilentlyContinue) }

foreach ($kill in $Kills) {
    "===== E3 [$kill]: README PS recipe, beat 1 s, lease 3 s; holder host killed abruptly"
    $d = Fresh "e3-$kill"
    $env:SHARED_LOCK_DIR = $d; $env:SHARED_LOCK_POLL_SECONDS = '0.2'
    $r = Start-Process pwsh -ArgumentList '-NoProfile', '-File', "$B\runner_readme.ps1", '-Lock', $CODE, '-Dir', $d -PassThru -WindowStyle Hidden
    # wait until the lock is held by the runner and the job has renewed the lease at least twice
    $t = Get-Date; $seen = @()
    while (((Get-Date) - $t).TotalSeconds -lt 40) {
        $x = Rec $d
        if ($x -and $x.owner -eq 'readme-owner') { if ($seen -notcontains $x.lease_until) { $seen += $x.lease_until } }
        if ($seen.Count -ge 3) { break }
        Start-Sleep -Milliseconds 250
    }
    $x = Rec $d
    "host pid $($r.Id); record holder_pid=$($x.holder_pid) owner=$($x.owner); distinct lease_until values before kill: $($seen.Count)"
    $kids = @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$($r.Id)")
    $job = $kids | Where-Object { $_.Name -eq 'pwsh.exe' -and $_.CommandLine -match '\s-s(\s|$)' } | Select-Object -First 1
    "children of host: " + (($kids | ForEach-Object { "$($_.ProcessId):$($_.Name) [$($_.CommandLine)]" }) -join ' ; ')
    if (-not $job) { "NO Start-Job child found; aborting this case"; Stop-Process -Id $r.Id -Force -ErrorAction SilentlyContinue; continue }
    "Start-Job child pid $($job.ProcessId)"

    if ($kill -eq 'tree') { & taskkill /T /F /PID $r.Id 2>&1 | ForEach-Object { "  taskkill: $_" } }
    else { Stop-Process -Id $r.Id -Force }
    $tk = Now
    Start-Sleep -Milliseconds 300
    "killed host $($r.Id) ($kill): host alive=$(Alive $r.Id), Start-Job child alive=$(Alive $job.ProcessId)"

    $w = Start-Process $PY -ArgumentList '-I', "`"$CODE`"", 'acquire', 'W', '"next job"' -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput "$d\W.out" -RedirectStandardError "$d\W.err"
    $granted = $null
    for ($i = 1; $i -le $WatchSeconds; $i++) {
        Start-Sleep -Seconds 1
        $x = Rec $d
        $left = if ($x -and $x.owner -eq 'readme-owner') { [math]::Round($x.lease_until - (Now), 1) } else { 'n/a' }
        $line = "  t+{0,2}s  job child alive={1}  owner={2}  lease left={3}  waiter exited={4}" -f $i, (Alive $job.ProcessId), $(if ($x) { $x.owner } else { '<none>' }), $left, $w.HasExited
        if ($i -le 5 -or $i % 5 -eq 0 -or ($w.HasExited -and -not $granted)) { $line }
        if ($w.HasExited -and -not $granted) { $granted = [math]::Round((Now) - $tk, 1) }
        if ($granted) { break }
    }
    if ($granted) { "waiter granted $granted s after the kill: " + ((Get-Content "$d\W.out") -join ' | ') + ' || ' + ((Get-Content "$d\W.err") -join ' | ') }
    else {
        "waiter STILL WAITING $WatchSeconds s after the holder died (lease 3 s); job child alive=$(Alive $job.ProcessId)"
        $grand = @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$($job.ProcessId)")
        "  job child's own children: " + (($grand | ForEach-Object { "$($_.ProcessId):$($_.Name)" }) -join ', ')
        Stop-Process -Id $job.ProcessId -Force -ErrorAction SilentlyContinue
        $tj = Now
        $null = $w.WaitForExit(15000)
        if ($w.HasExited) { "after killing the orphaned Start-Job child: waiter granted $([math]::Round((Now) - $tj, 1)) s later: " + ((Get-Content "$d\W.out") -join ' | ') + ' || ' + ((Get-Content "$d\W.err") -join ' | ') }
        else { "waiter still waiting after the job child was killed"; Stop-Process -Id $w.Id -Force -ErrorAction SilentlyContinue }
    }
    & $PY -I $CODE release W 2>&1 | Out-Null
    foreach ($p in @($r.Id, $job.ProcessId, $w.Id)) { Stop-Process -Id $p -Force -ErrorAction SilentlyContinue }
}
Remove-Item Env:SHARED_LOCK_DIR, Env:SHARED_LOCK_POLL_SECONDS -ErrorAction SilentlyContinue

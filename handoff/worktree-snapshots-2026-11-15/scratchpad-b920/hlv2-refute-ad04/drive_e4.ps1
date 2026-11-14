# Review-only driver (HLV2-AD-04): README bash recipe under Git Bash; the runner bash is killed abruptly (TerminateProcess
# on its Windows pid). Does the background heartbeat subshell outlive it and keep the lock? COPY + temp folders only.
param([int]$WatchSeconds = 25, [switch]$UseWinPid, [string]$Lease = '3', [string]$Step = '300')
$env:LEASE = $Lease; $env:STEP = $Step
$B = 'C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\hlv2-refute-ad04'
$CODE = "$B\code\heavy_lock.py"
$PY = 'C:\Python313\python.exe'
$GB = 'C:\Program Files\Git\bin\bash.exe'
if ($B -match 'ff9f2caf') { throw 'live' }
function Rec([string]$d) { try { Get-Content "$d\heavy-build.lock" -Raw -ErrorAction Stop | ConvertFrom-Json } catch { $null } }
function Now { [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() / 1000.0 }
function Alive([int]$id) { [bool](Get-Process -Id $id -ErrorAction SilentlyContinue) }

$d = "$B\locks\e4" + $(if ($UseWinPid) { '-winpid' } else { '-msyspid' })
if (Test-Path $d) { Remove-Item $d -Recurse -Force }
New-Item -ItemType Directory -Force $d | Out-Null
$env:SHARED_LOCK_DIR = $d; $env:SHARED_LOCK_POLL_SECONDS = '0.2'
if ($UseWinPid) { $env:USE_WINPID = '1' } else { Remove-Item Env:USE_WINPID -ErrorAction SilentlyContinue }
$dU = $d -replace '\\', '/'; $codeU = $CODE -replace '\\', '/'; $shU = "$B\runner_readme.sh" -replace '\\', '/'
"===== E4 [$(if ($UseWinPid) { '--pid = Windows pid of the runner bash' } else { '--pid $$ as in the README (MSYS pid)' })]: README bash recipe (Git Bash), beat 1 s, lease 3 s; runner bash killed abruptly"
$launcher = Start-Process $GB -ArgumentList '-l', $shU, $dU, $codeU -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput "$d\bash.out" -RedirectStandardError "$d\bash.err"
Remove-Item Env:USE_WINPID -ErrorAction SilentlyContinue
$t = Get-Date; $seen = @()
while (((Get-Date) - $t).TotalSeconds -lt 40) {
    $x = Rec $d
    if ($x -and $x.owner -eq 'readme-owner' -and $seen -notcontains $x.lease_until) { $seen += $x.lease_until }
    if ($seen.Count -ge 3 -and (Test-Path "$d\hb.winpid") -and (Test-Path "$d\runner.winpid")) { break }
    Start-Sleep -Milliseconds 250
}
$runner = [int](Get-Content "$d\runner.winpid" -Raw).Trim()
$hb = [int](Get-Content "$d\hb.winpid" -Raw).Trim()
$x = Rec $d
"launcher pid $($launcher.Id); runner bash Windows pid $runner; heartbeat subshell Windows pid $hb"
"record holder_pid=$($x.holder_pid) (the MSYS `$`$`$ the recipe passes) owner=$($x.owner); distinct lease_until values before kill: $($seen.Count)"
"acquire stderr: " + ((Get-Content "$d\runner.acquire.err" -ErrorAction SilentlyContinue) -join ' | ')
$mine = @($runner, $hb) + @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$runner" | ForEach-Object ProcessId)
"runner alive=$(Alive $runner) subshell alive=$(Alive $hb) before the kill"

Stop-Process -Id $runner -Force
$tk = Now
Start-Sleep -Milliseconds 300
"killed runner bash $runner (TerminateProcess): runner alive=$(Alive $runner), heartbeat subshell alive=$(Alive $hb)"
$w = Start-Process $PY -ArgumentList '-I', "`"$CODE`"", 'acquire', 'W', '"next job"' -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput "$d\W.out" -RedirectStandardError "$d\W.err"
$granted = $null
for ($i = 1; $i -le $WatchSeconds; $i++) {
    Start-Sleep -Seconds 1
    $x = Rec $d
    $left = if ($x -and $x.owner -eq 'readme-owner') { [math]::Round($x.lease_until - (Now), 1) } else { 'n/a' }
    if ($i -le 4 -or $i % 5 -eq 0 -or $w.HasExited) {
        "  t+{0,2}s  subshell alive={1}  owner={2}  lease left={3}  waiter exited={4}" -f $i, (Alive $hb), $(if ($x) { $x.owner } else { '<none>' }), $left, $w.HasExited
    }
    if ($w.HasExited) { $granted = [math]::Round((Now) - $tk, 1); break }
}
if ($granted) { "waiter granted $granted s after the kill: " + ((Get-Content "$d\W.out") -join ' | ') }
else {
    "waiter STILL WAITING $WatchSeconds s after the runner died (lease 3 s); heartbeat subshell alive=$(Alive $hb)"
    Stop-Process -Id $hb -Force -ErrorAction SilentlyContinue
    $tj = Now
    $null = $w.WaitForExit(15000)
    if ($w.HasExited) { "after killing the orphaned heartbeat subshell: waiter granted $([math]::Round((Now) - $tj, 1)) s later: " + ((Get-Content "$d\W.out") -join ' | ') + ' || ' + ((Get-Content "$d\W.err") -join ' | ') }
    else { "waiter still waiting after the subshell was killed"; Stop-Process -Id $w.Id -Force -ErrorAction SilentlyContinue }
}
& $PY -I $CODE release W 2>&1 | Out-Null
$mine += @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$hb" | ForEach-Object ProcessId)
foreach ($p in ($mine + @($launcher.Id, $w.Id))) { Stop-Process -Id $p -Force -ErrorAction SilentlyContinue }
Remove-Item Env:SHARED_LOCK_DIR, Env:SHARED_LOCK_POLL_SECONDS -ErrorAction SilentlyContinue
"bash stderr tail: " + ((Get-Content "$d\bash.err" -ErrorAction SilentlyContinue | Select-Object -Last 3) -join ' | ')
# the stand-in heavy step (python sleeping $Step s, started by the killed runner) is orphaned too; stop only that exact one
Get-CimInstance Win32_Process -Filter "Name='python.exe'" | Where-Object { $_.CommandLine -match [regex]::Escape("time.sleep($Step)") } |
    ForEach-Object { "stopping orphaned stand-in step pid $($_.ProcessId)"; Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Remove-Item Env:LEASE, Env:STEP -ErrorAction SilentlyContinue

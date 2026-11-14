# Review-only driver (COPY of heavy_lock v2 + temp lock folders under hlv2-refute-ad01; never the live ff9f2caf folder).
# Mode plainkill: runner killed with Stop-Process -Force (TerminateProcess on the runner only).
# Mode treekill : runner killed with taskkill /T /F (control).
# Mode normal   : runner not killed (control: finally releases, waiter gets the lock after the heavy step).
param(
    [Parameter(Mandatory = $true)][ValidateSet("plainkill", "treekill", "normal")][string]$Mode,
    [ValidateSet("start-process", "wmi")][string]$Launch = "start-process",
    [int]$Lease = 8, [int]$Beat = 2, [int]$Build = 75
)
$B = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\hlv2-refute-ad01"
$CODE = "$B\code\heavy_lock.py"
$PY = "C:\Python313\python.exe"
$PWSH = "C:\Program Files\PowerShell\7\pwsh.exe"
if ($CODE -like "*ff9f2caf*") { throw "live path" }
function Now { (Get-Date).ToUniversalTime().ToString("HH:mm:ss.fff") }
function Alive([int]$id) { [bool](Get-Process -Id $id -ErrorAction SilentlyContinue) }
function Desc([int]$root) {
    $all = @(Get-CimInstance Win32_Process | Select-Object ProcessId, ParentProcessId, Name, CommandLine)
    $out = @(); $front = @($root)
    while ($front.Count -gt 0) {
        $next = @()
        foreach ($f in $front) { foreach ($p in ($all | Where-Object { $_.ParentProcessId -eq $f -and $_.ProcessId -ne $f })) { $out += $p; $next += $p.ProcessId } }
        $front = $next
    }
    return $out
}
function Lines([string]$f) { if (Test-Path $f) { @(Get-Content $f).Count } else { 0 } }
function WaitFor([scriptblock]$c, [int]$sec) { $t = [DateTime]::UtcNow.AddSeconds($sec); while ([DateTime]::UtcNow -lt $t) { if (& $c) { return $true }; Start-Sleep -Milliseconds 200 }; return $false }
function St([string]$d) { $env:SHARED_LOCK_DIR = $d; $s = & $PY -I $CODE status 2>&1; Remove-Item Env:SHARED_LOCK_DIR; $s | ForEach-Object { "    status| $_" } }

$d = "$B\locks\$Mode-$Launch"
if (Test-Path $d) { Remove-Item -Recurse -Force $d }
New-Item -ItemType Directory $d | Out-Null
$log = "$d\runner.log"; $prog = "$d\maven-progress.txt"
$argline = "-NoProfile -NonInteractive -ExecutionPolicy Bypass -File $B\runner_readme.ps1 -Lock $CODE -Dir $d -Log $log -Progress $prog -Lease $Lease -Beat $Beat -BuildSeconds $Build"
if ($Launch -eq "wmi") {
    $startup = New-CimInstance -Namespace root/cimv2 -ClassName Win32_ProcessStartup -Property @{ ShowWindow = [uint16]0 } -ClientOnly
    $r = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = "`"$PWSH`" $argline"; ProcessStartupInformation = $startup }
    $rp = [int]$r.ProcessId
} else {
    $p = Start-Process $PWSH -ArgumentList $argline -PassThru -WindowStyle Hidden
    $rp = $p.Id
}
"$(Now) mode=$Mode launch=$Launch runner pid=$rp lease=${Lease}s beat=${Beat}s build=${Build}s"
if (-not (WaitFor { (Lines $prog) -ge 3 -and (Lines "$log.hb") -ge 1 } 90)) { "runner did not reach the heavy step"; Get-Content $log, "$log.acquire" -ErrorAction SilentlyContinue; if (Alive $rp) { & taskkill /T /F /PID $rp | Out-Null }; exit 1 }
# a waiter already queued behind the runner (the 'next job')
$env:SHARED_LOCK_DIR = $d; $env:SHARED_LOCK_POLL_SECONDS = "0.2"
$w = Start-Process $PY -ArgumentList '-I', $CODE, 'acquire', 'W', 'next-heavy-job' -PassThru -WindowStyle Hidden -RedirectStandardOutput "$d\W.out" -RedirectStandardError "$d\W.err"
Remove-Item Env:SHARED_LOCK_DIR, Env:SHARED_LOCK_POLL_SECONDS
Start-Sleep -Milliseconds 800
$kids = @(Desc $rp)
"$(Now) runner descendants before the kill:"; $kids | ForEach-Object { "    {0,6} {1,-12} {2}" -f $_.ProcessId, $_.Name, ("" + $_.CommandLine).Substring(0, [Math]::Min(110, ("" + $_.CommandLine).Length)) }
$java = ($kids | Where-Object Name -eq "java.exe" | Select-Object -First 1).ProcessId
$cmd = ($kids | Where-Object Name -eq "cmd.exe" | Select-Object -First 1).ProcessId
$hbjob = ($kids | Where-Object { $_.Name -eq "pwsh.exe" -and $_.CommandLine -match " -s " } | Select-Object -First 1).ProcessId
St $d | Select-Object -First 3
$tKill = [DateTime]::UtcNow
$hbAtKill = Lines "$log.hb"
if ($Mode -eq "plainkill") { "$(Now) Stop-Process -Id $rp -Force   (runner only)"; Stop-Process -Id $rp -Force }
elseif ($Mode -eq "treekill") { "$(Now) taskkill /T /F /PID $rp"; & taskkill /T /F /PID $rp | Out-Null }
else { "$(Now) no kill; waiting for the runner to finish" }
Start-Sleep -Milliseconds 1500
"$(Now) 1.5 s later: runner alive=$(Alive $rp)  cmd(fake mvnw) $cmd alive=$(if ($cmd) { Alive $cmd } else { 'n/a' })  java $java alive=$(if ($java) { Alive $java } else { 'n/a' })  hb-job $hbjob alive=$(if ($hbjob) { Alive $hbjob } else { 'n/a' })"
$ok = WaitFor { -not (Alive $w.Id) } ($Build + 60)
$tAcq = [DateTime]::UtcNow
$p1 = Lines $prog
$javaAtAcq = if ($java) { Alive $java } else { $false }
Start-Sleep -Seconds 2
$p2 = Lines $prog
"$(Now) waiter W: " + $(if ($ok) { "exited rc=$($w.ExitCode) {0:N1} s after the kill" -f ($tAcq - $tKill).TotalSeconds } else { "STILL WAITING" })
Get-Content "$d\W.out" -ErrorAction SilentlyContinue | ForEach-Object { "    W.out| $_" }
Get-Content "$d\W.err" -ErrorAction SilentlyContinue | ForEach-Object { "    W.err| $_" }
"    at W's grant: java $java alive=$javaAtAcq; maven progress lines $p1 -> $p2 over the next 2 s (still working: $($p2 -gt $p1))"
"    last progress line: " + (Get-Content $prog -Tail 1)
"    heartbeat-job lines: $hbAtKill at the kill, $(Lines "$log.hb") now"
Get-Content "$d\heavy-build.lock.served.log" | ForEach-Object { "    served| $_" }
St $d | Select-Object -First 3
Get-Content $log -ErrorAction SilentlyContinue | ForEach-Object { "    runner.log| $_" }
# cleanup: only processes this driver started (by pid), then the temp lock
foreach ($k in $kids) { if (Alive $k.ProcessId) { Stop-Process -Id $k.ProcessId -Force -ErrorAction SilentlyContinue } }
if (Alive $rp) { & taskkill /T /F /PID $rp | Out-Null }
if (Alive $w.Id) { Stop-Process -Id $w.Id -Force -ErrorAction SilentlyContinue }
$env:SHARED_LOCK_DIR = $d; & $PY -I $CODE release W | Out-Null; Remove-Item Env:SHARED_LOCK_DIR
Start-Sleep -Milliseconds 500
"$(Now) cleanup: leftover alive = " + (@($kids | Where-Object { Alive $_.ProcessId } | ForEach-Object { "$($_.ProcessId) $($_.Name)" }) -join ", ")

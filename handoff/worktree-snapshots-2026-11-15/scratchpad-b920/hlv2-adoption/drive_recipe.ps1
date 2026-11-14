# Review-only driver for runner_recipe.ps1 (copy + temp lock folders). Launch styles: Start-Process, or
# Win32_Process.Create (like a401-launch.ps1: outside the caller's job object, no handle kept).
param([Parameter(Mandatory = $true)][ValidateSet("start-process", "wmi")][string]$Launch)
$B = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\hlv2-adoption"
$CODE = "$B\code\heavy_lock.py"
$PY = "C:\Python313\python.exe"
$PWSH = "C:\Program Files\PowerShell\7\pwsh.exe"
function Now { (Get-Date).ToUniversalTime().ToString("HH:mm:ss.fff") }
function Fresh([string]$tag) { $d = "$B\locks\$tag"; if (Test-Path $d) { Remove-Item $d -Recurse -Force }; New-Item -ItemType Directory $d | Out-Null; $d }
function Alive([int]$id) { [bool](Get-Process -Id $id -ErrorAction SilentlyContinue) }
function Kids([int]$id) { @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$id" | Select-Object ProcessId, Name) }
function Launch([string]$d, [string]$log, [int]$lease, [int]$beat, [int]$jobSec) {
    $args2 = "-NoProfile -NonInteractive -ExecutionPolicy Bypass -File $B\runner_recipe.ps1 -Lock $CODE -Dir $d -Log $log -Lease $lease -BeatSeconds $beat -JobSeconds $jobSec"
    if ($Launch -eq "wmi") {
        $r = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = "`"$PWSH`" $args2" }
        return [int]$r.ProcessId
    }
    $p = Start-Process $PWSH -ArgumentList $args2 -PassThru -WindowStyle Hidden
    $id = $p.Id; $p.Dispose(); return $id
}
function WaitFor([scriptblock]$cond, [int]$sec) { $t = [DateTime]::UtcNow.AddSeconds($sec); while ([DateTime]::UtcNow -lt $t) { if (& $cond) { return $true }; Start-Sleep -Milliseconds 200 }; return $false }
function Waiter([string]$d) {
    $env:SHARED_LOCK_DIR = $d; $env:SHARED_LOCK_POLL_SECONDS = "0.2"
    $w = Start-Process $PY -ArgumentList '-I', $CODE, 'acquire', 'W', 'next-heavy-job' -PassThru -WindowStyle Hidden -RedirectStandardOutput "$d\W.out" -RedirectStandardError "$d\W.err"
    Remove-Item Env:SHARED_LOCK_DIR, Env:SHARED_LOCK_POLL_SECONDS; return $w
}

"===== launch=$Launch  R1: normal run (lease 6 s, beat 2 s, job 7 s)"
$d = Fresh "r1-$Launch"; $log = "$d\runner.log"
$rp = Launch $d $log 6 2 7
WaitFor { (Test-Path $log) -and (Select-String -Path $log -Pattern "RUNNER-END|not running" -Quiet) } 60 | Out-Null
Get-Content $log | ForEach-Object { "    log| $_" }

"===== launch=$Launch  R2: runner killed (Stop-Process, no tree) while the heavy step runs"
$d = Fresh "r2-$Launch"; $log = "$d\runner.log"
$rp = Launch $d $log 6 2 60
WaitFor { (Test-Path $log) -and (Select-String -Path $log -Pattern "heartbeat rc=0" -Quiet) } 60 | Out-Null
$heavy = [int]((Select-String -Path $log -Pattern "heavy step pid=(\d+)").Matches[0].Groups[1].Value)
"$(Now) runner $rp, heavy step $heavy alive=$(Alive $heavy); Stop-Process -Force runner"
Stop-Process -Id $rp -Force
Start-Sleep -Seconds 1
"$(Now) heavy step alive=$(Alive $heavy)   <- kill-on-close job"
$w = Waiter $d
WaitFor { -not (Alive $w.Id) } 20 | Out-Null
"$(Now) waiter: " + $(if (Alive $w.Id) { "still waiting" } else { "exit $($w.ExitCode)" }) + "; heavy step alive=$(Alive $heavy)"
Get-Content "$d\W.err" | Select-Object -Last 1 | ForEach-Object { "    W.err| $_" }
if (Alive $w.Id) { Stop-Process -Id $w.Id -Force }; if (Alive $heavy) { Stop-Process -Id $heavy -Force }

"===== launch=$Launch  R3: runner killed (Stop-Process, no tree) while QUEUED"
$d = Fresh "r3-$Launch"; $log = "$d\runner.log"
$env:SHARED_LOCK_DIR = $d; & $PY -I $CODE acquire H "blocking holder" | Out-Null; Remove-Item Env:SHARED_LOCK_DIR
$rp = Launch $d $log 6 2 30
WaitFor { Get-ChildItem "$d\heavy-build.lock.queue" -ErrorAction SilentlyContinue | Where-Object Name -like "*-customwf-recipe-*" } 60 | Out-Null
$acq = (Kids $rp | Where-Object Name -eq "python.exe" | Select-Object -First 1).ProcessId
"$(Now) queued runner $rp, its acquire child $acq; Stop-Process -Force runner"
Stop-Process -Id $rp -Force
Start-Sleep -Seconds 1
"$(Now) acquire child alive=$(Alive $acq)   <- no orphaned acquire"
$env:SHARED_LOCK_DIR = $d; & $PY -I $CODE release H | Out-Null
Start-Sleep -Seconds 1
"$(Now) after H released, lock file present: $(Test-Path "$d\heavy-build.lock")  (ticket of the killed acquire expires after 90 s)"
& $PY -I $CODE release W 2>$null | Out-Null; Remove-Item Env:SHARED_LOCK_DIR
if ($acq -and (Alive $acq)) { Stop-Process -Id $acq -Force }

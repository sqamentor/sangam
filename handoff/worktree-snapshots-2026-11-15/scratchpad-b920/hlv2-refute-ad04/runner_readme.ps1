# Review-only copy of the README.md:84-100 PowerShell recipe (HLV2-AD-04 refutation), pointed at a COPY of heavy_lock v2
# and a temp lock folder. Differences from the README, test plumbing only: lock path = the copy, SHARED_LOCK_DIR = temp
# folder (also set inside the job), beat 1 s instead of 600 s, --lease 3, owner name, Start-Sleep stands in for the step.
param([string]$Lock, [string]$Dir, [int]$Beat = 1, [string]$Lease = "3", [int]$StepSeconds = 300)
if ($Dir -match 'ff9f2caf' -or $Lock -match 'ff9f2caf') { throw "refusing the live lock" }
foreach ($v in 'SHARED_LOCK_DIR', 'SHARED_LOCK_NO_AUTORELEASE', 'SHARED_LOCK_POLL_SECONDS', 'SHARED_LOCK_STALE_SECONDS', 'HEAVY_LOCK_PID') {
    Remove-Item "Env:$v" -ErrorAction SilentlyContinue
}
$env:SHARED_LOCK_DIR = $Dir; $env:SHARED_LOCK_POLL_SECONDS = "0.2"   # TEST ONLY

$lock  = $Lock
$owner = "readme-owner"
python -I $lock acquire $owner "Trial 2 retest" --pid $PID --lease $Lease
if ($LASTEXITCODE -ne 0) { throw "heavy lock not acquired (rc=$LASTEXITCODE)" }
$hb = Start-Job -ArgumentList $lock, $owner, $Beat, $Dir -ScriptBlock {
    param($lock, $owner, $beat, $dir)
    $env:SHARED_LOCK_DIR = $dir                                       # TEST ONLY (README relies on the default folder)
    while ($true) { Start-Sleep -Seconds $beat; python -I $lock heartbeat $owner | Out-Null }
}
try {
    Start-Sleep -Seconds $StepSeconds   # ... the heavy step ...
}
finally {
    Stop-Job $hb -ErrorAction SilentlyContinue; Remove-Job $hb -Force -ErrorAction SilentlyContinue
    python -I $lock release $owner
}

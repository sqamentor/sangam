param([string]$Lock, [string]$What)
# The README "Runner usage" PowerShell recipe (README.md:84-100), verbatim except: the lock path, the what text and
# the heavy step are parameters/markers. SHARED_LOCK_DIR comes from the driver's env (a temp folder).
if (-not $env:SHARED_LOCK_DIR -or $env:SHARED_LOCK_DIR -notlike '*hlv2-refute-ad06*') { throw "unsafe SHARED_LOCK_DIR" }
$lock  = $Lock
$owner = "customwf-enc"
python -I $lock acquire $owner $What --pid $PID
if ($LASTEXITCODE -ne 0) { throw "heavy lock not acquired (rc=$LASTEXITCODE)" }
$hb = Start-Job -ArgumentList $lock, $owner -ScriptBlock {
    param($lock, $owner)
    while ($true) { Start-Sleep -Seconds 600; python -I $lock heartbeat $owner | Out-Null }
}
try {
    Set-Content -LiteralPath (Join-Path $env:SHARED_LOCK_DIR "heavy-step-ran.txt") -Value "yes"
}
finally {
    Stop-Job $hb -ErrorAction SilentlyContinue; Remove-Job $hb -Force -ErrorAction SilentlyContinue
    python -I $lock release $owner
    Set-Content -LiteralPath (Join-Path $env:SHARED_LOCK_DIR "release-ran.txt") -Value "yes"
}

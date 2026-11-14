param($lock, $owner, $out)
# README.md:87-99 pattern; differences: --lease 6, Start-Sleep 2 instead of 600, heartbeat output kept in beats.log
$env:SHARED_LOCK_DIR = "$out\locks"
python -I $lock acquire $owner "Trial 2 retest" --pid $PID --lease 6 *>> "$out\acquire.log"
if ($LASTEXITCODE -ne 0) { throw "heavy lock not acquired (rc=$LASTEXITCODE)" }
$hb = Start-Job -ArgumentList $lock, $owner, $out -ScriptBlock {
    param($lock, $owner, $out)
    $env:SHARED_LOCK_DIR = "$out\locks"
    while ($true) { Start-Sleep -Seconds 2; python -I $lock heartbeat $owner *>> "$out\beats.log" }
}
"host=$PID" | Set-Content "$out\host.info"
try {
    Start-Sleep -Seconds 600
}
finally {
    Stop-Job $hb -ErrorAction SilentlyContinue; Remove-Job $hb -Force -ErrorAction SilentlyContinue
    python -I $lock release $owner *>> "$out\finally.log"
}

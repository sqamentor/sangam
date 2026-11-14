# Test-only stand-in for a Sangam detached runner that is still QUEUED in acquire. Temp lock dir only.
param([string]$Lock, [string]$Dir, [string]$Log, [string]$Mode)
if ($Dir -match 'ff9f2caf') { throw "refusing to touch the live lock dir" }
$env:SHARED_LOCK_DIR = $Dir
$env:SHARED_LOCK_POLL_SECONDS = "0.2"
"runner pid=$PID mode=$Mode" | Add-Content $Log
if ($Mode -eq 'redirect') {
    & 'C:\Python313\python.exe' -I $Lock acquire R "queued runner" --pid $PID *>> $Log          # a401-pg-runner.ps1 style
} else {
    & 'C:\Python313\python.exe' -I $Lock acquire R "queued runner" --pid $PID 2>&1 | ForEach-Object { Add-Content $Log "lock: $_" }   # sl45-runner.ps1 style
}
"acquire rc=$LASTEXITCODE" | Add-Content $Log
Start-Sleep -Seconds 300

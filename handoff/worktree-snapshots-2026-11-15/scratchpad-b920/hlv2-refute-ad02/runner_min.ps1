# Review-only: README runner recipe reduced to its acquire step (COPY of the tool, private lock folder).
param([Parameter(Mandatory = $true)][string]$Lock, [Parameter(Mandatory = $true)][string]$Dir,
      [Parameter(Mandatory = $true)][string]$Log, [string]$Owner = "customwf-s4", [int]$Lease = 8)
if ($Lock -like "*ff9f2caf*" -or $Dir -like "*ff9f2caf*") { throw "refusing to touch the live lock tool or folder" }
$env:SHARED_LOCK_DIR = $Dir
$env:SHARED_LOCK_POLL_SECONDS = "0.2"
Add-Content -Path $Log -Value "runner start pid=$PID"
& C:\Python313\python.exe -I $Lock acquire $Owner "queued job" --pid $PID --lease $Lease *>> $Log
Add-Content -Path $Log -Value "acquire rc=$LASTEXITCODE"
try { Start-Sleep -Seconds 30 } finally {
    & C:\Python313\python.exe -I $Lock release $Owner *>> $Log
    Add-Content -Path $Log -Value "RUNNER-END"
}

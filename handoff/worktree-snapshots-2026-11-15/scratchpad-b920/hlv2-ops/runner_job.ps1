# Test-only stand-in for a Sangam detached runner HOLDING the lock and running a long child ("Maven"). Temp lock dir only.
param([string]$Lock, [string]$Dir, [string]$Log, [string]$Lease = "3")
if ($Dir -match 'ff9f2caf') { throw "refusing to touch the live lock dir" }
$env:SHARED_LOCK_DIR = $Dir
$env:SHARED_LOCK_POLL_SECONDS = "0.2"
"runner pid=$PID" | Add-Content $Log
& 'C:\Python313\python.exe' -I $Lock acquire R5 "runner job" --pid $PID --lease $Lease *>> $Log
if ($LASTEXITCODE -ne 0) { "ACQUIRE FAILED $LASTEXITCODE" | Add-Content $Log; exit 2 }
try {
    "job start" | Add-Content $Log
    # stand-in for `& .\mvnw.cmd ... verify`: a child process the runner waits for
    & 'C:\Python313\python.exe' -I -c "import time; open(r'$Dir\maven.alive','w').write('x'); time.sleep(120)"
    "job end $LASTEXITCODE" | Add-Content $Log
} finally {
    & 'C:\Python313\python.exe' -I $Lock release R5 *>> $Log
    "released rc=$LASTEXITCODE" | Add-Content $Log
}

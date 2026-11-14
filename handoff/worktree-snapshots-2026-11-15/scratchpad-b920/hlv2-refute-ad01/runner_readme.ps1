# Review-only runner: README.md:84-100 PowerShell recipe, run against a COPY of heavy_lock v2 and a temp lock folder.
# Differences from the README text (test-only): --lease $Lease and a heartbeat every $Beat s instead of 1800/600,
# the job also gets the temp lock folder, the job logs to its own file, python by full path, and the heavy step is
# fake-mvnw.cmd (cmd.exe -> java.exe, like mvnw.cmd), run inline as the Sangam runners run mvnw.cmd.
param(
    [Parameter(Mandatory = $true)][string]$Lock,
    [Parameter(Mandatory = $true)][string]$Dir,
    [Parameter(Mandatory = $true)][string]$Log,
    [Parameter(Mandatory = $true)][string]$Progress,
    [string]$Owner = "sangam",
    [int]$Lease = 8,
    [int]$Beat = 2,
    [int]$BuildSeconds = 75
)
if ($Lock -like "*ff9f2caf*" -or $Dir -like "*ff9f2caf*") { throw "refusing to touch the live lock tool or folder" }
$env:SHARED_LOCK_DIR = $Dir             # test only
$env:SHARED_LOCK_POLL_SECONDS = "0.2"   # test only
$B = Split-Path -Parent $PSCommandPath
$env:STAND_JAVA = 'C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot\bin\java.exe'
$env:STAND_CP = "$B\classes"
$python = 'C:\Python313\python.exe'
function L([string]$m) { Add-Content -Encoding utf8 -Path $Log -Value ("{0} [runner {1}] {2}" -f (Get-Date).ToUniversalTime().ToString("HH:mm:ss.fff"), $PID, $m) }

# ---- README.md:84-100 ----
$lock  = $Lock
$owner = $Owner
& $python -I $lock acquire $owner "Trial 2 retest" --pid $PID --lease $Lease *>> "$Log.acquire"
if ($LASTEXITCODE -ne 0) { throw "heavy lock not acquired (rc=$LASTEXITCODE)" }
$hb = Start-Job -ArgumentList $lock, $owner, $Dir, $Beat, "$Log.hb", $python -ScriptBlock {
    param($lock, $owner, $dir, $beat, $hblog, $python)
    $env:SHARED_LOCK_DIR = $dir
    while ($true) {
        Start-Sleep -Seconds $beat
        $o = & $python -I $lock heartbeat $owner 2>&1
        Add-Content -Encoding utf8 -Path $hblog -Value ("{0} [hbjob {1}] rc={2} {3}" -f (Get-Date).ToUniversalTime().ToString("HH:mm:ss.fff"), $PID, $LASTEXITCODE, ($o -join ' '))
    }
}
L "acquired; heartbeat job $($hb.Id) started; heavy step start"
try {
    & "$B\fake-mvnw.cmd" $Progress $BuildSeconds *> "$Log.maven"
    L "heavy step exit $LASTEXITCODE"
}
finally {
    Stop-Job $hb -ErrorAction SilentlyContinue; Remove-Job $hb -Force -ErrorAction SilentlyContinue
    & $python -I $lock release $owner *>> "$Log.acquire"
    L "RUNNER-END"
}

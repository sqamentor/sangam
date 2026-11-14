# Review-only test runner (heavy_lock v2 COPY, temp lock folder). Shaped like the Sangam detached runners:
# acquire --pid $PID -> "Maven" stand-in via Start-Process -PassThru -> release in finally.
# -Heartbeat job    = README recipe (Start-Job loop, Stop-Job before release)
# -Heartbeat inproc = heartbeat from the runner itself while it waits for the child
param(
    [Parameter(Mandatory = $true)][string]$Lock,
    [Parameter(Mandatory = $true)][string]$Dir,
    [Parameter(Mandatory = $true)][string]$Log,
    [string]$Owner = "customwf-t",
    [ValidateSet("job", "inproc", "none")][string]$Heartbeat = "inproc",
    [int]$Lease = 6,
    [int]$Every = 2,
    [int]$JobSeconds = 40
)
if ($Lock -like "*ff9f2caf*" -or $Dir -like "*ff9f2caf*") { throw "refusing to touch the live lock tool or folder" }
$env:SHARED_LOCK_DIR = $Dir
$env:SHARED_LOCK_POLL_SECONDS = "0.2"
$PY = "C:\Python313\python.exe"
function Log([string]$m) {
    Add-Content -Path $Log -Encoding utf8 -Value ("{0} [{1}] {2}" -f (Get-Date).ToUniversalTime().ToString("HH:mm:ss.fff"), $PID, $m)
}
Log "runner start heartbeat=$Heartbeat lease=$Lease every=$Every job=${JobSeconds}s"
& $PY -I $Lock acquire $Owner "test job ($Heartbeat)" --pid $PID --lease $Lease *>> $Log
$rc = $LASTEXITCODE
Log "acquire rc=$rc"
if ($rc -ne 0) { Log "RUNNER-END (not acquired)"; exit 2 }
$hb = $null
if ($Heartbeat -eq "job") {
    $hb = Start-Job -ArgumentList $PY, $Lock, $Owner, $Dir, $Every, $Log -ScriptBlock {
        param($PY, $Lock, $Owner, $Dir, $Every, $Log)
        $env:SHARED_LOCK_DIR = $Dir
        while ($true) {
            Start-Sleep -Seconds $Every
            $o = & $PY -I $Lock heartbeat $Owner 2>&1
            Add-Content -Path $Log -Encoding utf8 -Value ("{0} [job {1}] hb rc={2} {3}" -f (Get-Date).ToUniversalTime().ToString("HH:mm:ss.fff"), $PID, $LASTEXITCODE, ($o -join ' '))
        }
    }
    Log "heartbeat job started (job id $($hb.Id))"
}
try {
    $mvn = Start-Process -FilePath $PY -ArgumentList '-I', '-c', "`"import time; time.sleep($JobSeconds)`"" -NoNewWindow -PassThru
    $null = $mvn.Handle
    Log "maven stand-in pid=$($mvn.Id)"
    if ($Heartbeat -eq "inproc") {
        while (-not $mvn.WaitForExit($Every * 1000)) {
            $o = & $PY -I $Lock heartbeat $Owner 2>&1
            Log "hb rc=$LASTEXITCODE $($o -join ' ')"
        }
    } else {
        $mvn.WaitForExit()
    }
    Log "maven exit=$($mvn.ExitCode)"
} finally {
    if ($hb) { Stop-Job $hb -ErrorAction SilentlyContinue; Remove-Job $hb -Force -ErrorAction SilentlyContinue }
    $o = & $PY -I $Lock release $Owner 2>&1
    Log "release rc=$LASTEXITCODE $($o -join ' ')"
    Log "RUNNER-END"
}

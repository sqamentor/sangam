# Review-only: does v2's new stderr line on a SUCCESSFUL --pid acquire break a runner that sets
# $ErrorActionPreference = 'Stop' and captures the tool's output with 2>&1 (the sl45 runner pattern)?
param([Parameter(Mandatory = $true)][string]$Dir)
$B = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\hlv2-adoption"
$CODE = "$B\code\heavy_lock.py"
$PY = "C:\Python313\python.exe"
$env:SHARED_LOCK_DIR = $Dir
$ErrorActionPreference = 'Stop'
$h = "PS $($PSVersionTable.PSVersion)"
try {
    & $PY -I $CODE acquire customwf-eap "eap test" --pid $PID 2>&1 | ForEach-Object { "$h  out| $_" }
    "$h  reached the line after acquire, rc=$LASTEXITCODE"
} catch {
    "$h  THREW after acquire: $($_.Exception.Message)"
}
$ErrorActionPreference = 'Continue'
"$h  lock owner now: " + ((& $PY -I $CODE status | Select-Object -First 1) -replace '^(.{0,60}).*', '$1')
& $PY -I $CODE release customwf-eap | ForEach-Object { "$h  $_" }

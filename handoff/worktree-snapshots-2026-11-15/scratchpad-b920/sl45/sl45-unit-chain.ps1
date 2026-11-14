# SL-45: the unit phases one after another in one detached process (one Maven job at a time; no heavy lock, H2 only).
# Each phase restores the "fix" toggle in its own finally. Started only after the PostgreSQL runners have ended.
$S = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\sl45"
$log = "$S\unit-chain.log"
function Log([string]$m) { Add-Content -Path $log -Encoding utf8 -Value ("{0} {1}" -f (Get-Date).ToUniversalTime().ToString("o"), $m) }
Log "chain start pid=$PID"
foreach ($step in @(@("engine-red", "25"), @("suites-green", "25"), @("suites-green", "21"))) {
    Log "step $($step[0]) jdk $($step[1]) start"
    & "C:\Program Files\PowerShell\7\pwsh.exe" -NoProfile -ExecutionPolicy Bypass -File "$S\sl45-unit.ps1" -Phase $step[0] -Jdk $step[1]
    Log "step $($step[0]) jdk $($step[1]) end (exit $LASTEXITCODE)"
}
Log "CHAIN-END"

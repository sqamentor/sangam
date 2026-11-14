param([string]$Log = "ifmswf-unit1.log")
# Unit suites of sangam-packs and sangam-flowable-ports (with -am: sangam-core too) from the private worktree. No lock:
# these are H2 / plain unit tests (no Testcontainers, no failsafe).
$out = Join-Path $PSScriptRoot $Log
$wt = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-ifmswf\sangam"
"[run] $(Get-Date -Format o) unit suites packs + flowable-ports (-am)" | Set-Content $out
Set-Location $wt
& .\mvnw.cmd -o "-Dsangam.target=target-ifmswf" -pl sangam-packs,sangam-flowable-ports -am clean test *>> $out
"EXIT $LASTEXITCODE" | Add-Content $out
"[run] $(Get-Date -Format o) done" | Add-Content $out

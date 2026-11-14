# Red runs, corrected (H2/unit only, no heavy lock). Invoked by a401-jdk25.ps1 -Phase redseq after the first red run,
# whose build kept the fixed classes of modules whose only changed sources were the reverted ones (Copy-Item keeps the
# old LastWriteTime: "Nothing to compile" in sangam-packs). So this script runs:
#  1. a401-red.ps1 again (all fixes reverted, files touched, clean build) as tag <Tag>-all;
#  2. attribution of the two fixes that run 1 masks, also touched + clean:
#     - ServerWebProblemHandler removed, with the token-context fixes IN PLACE: the handler itself is needed (the
#       onboarding approve problem must reach the caller as application/problem+json with its code and instance);
#     - PortsEventBridge reverted, with the Jackson fix IN PLACE: the 422 "startUniqueReference ... is not a
#       correlation parameter" (in run 1 the 400 MALFORMED_BODY of the Jackson default hides it).
# Every file is restored byte for byte (SHA-256 checked) and touched, so the next build recompiles it.
param([string]$Tag = "a401-jdk25-red2")
$S = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad"
$W = "C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-auth401\sangam"
$I = "D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam"
$K = "$S\$Tag-keep"
$R = "$S\$Tag.runner.log"
function Log([string]$m) {
    ((Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ") + " " + $m) | Add-Content -Encoding utf8 $R
}
Log "red2 runner start pid=${PID}; corrected run 1 first"
& "C:\Program Files\PowerShell\7\pwsh.exe" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$S\a401-red.ps1" -Tag "$Tag-all"
Log "corrected run 1 done"
$removed = @(
    "sangam-server\src\main\java\org\sangam\server\web\ServerWebProblemHandler.java",
    "sangam-server\src\test\java\org\sangam\server\web\ServerWebProblemHandlerTest.java")
$reverted = @(
    "sangam-server\src\main\java\org\sangam\server\bridge\PortsEventBridge.java")
$before = @{}
foreach ($f in $removed + $reverted) {
    $before[$f] = (Get-FileHash "$W\$f").Hash
    New-Item -ItemType Directory -Force (Split-Path "$K\$f") | Out-Null
    Copy-Item "$W\$f" "$K\$f" -Force
}
try {
    foreach ($f in $removed) { Remove-Item "$W\$f" }
    foreach ($f in $reverted) { Copy-Item "$I\$f" "$W\$f" -Force; (Get-Item "$W\$f").LastWriteTime = Get-Date }
    Log "handler removed, bridge reverted (touched); maven clean test start"
    Set-Location $W
    & .\mvnw.cmd "-Dsangam.target=target-auth401" -pl sangam-server -am clean test "-Dtest=RequestStatusContractH2Test" `
        "-Dsurefire.failIfNoSpecifiedTests=false" "-Dmaven.test.failure.ignore=true" *> "$S\$Tag.log"
    Log "maven exit $LASTEXITCODE (red expected: test failures, not compile errors)"
} finally {
    foreach ($f in $removed + $reverted) { Copy-Item "$K\$f" "$W\$f" -Force; (Get-Item "$W\$f").LastWriteTime = Get-Date }
    $bad = 0
    foreach ($f in $removed + $reverted) {
        if ((Get-FileHash "$W\$f").Hash -ne $before[$f]) { Log "RESTORE MISMATCH $f"; $bad++ }
    }
    # the green run that follows (no clean) must recompile every module of the change set: touch all of it
    $mine = python -c "import sys; sys.path.insert(0, r'$S'); from a401_files import MODIFIED, NEW; print('\n'.join(MODIFIED + NEW))"
    foreach ($r in $mine) { if ($r) { $p = Join-Path $W $r; if (Test-Path $p) { (Get-Item $p).LastWriteTime = Get-Date } } }
    Log "restored $(($removed + $reverted).Count) files, mismatches=$bad; change set touched for the green run"
    Log "RUNNER DONE"
}

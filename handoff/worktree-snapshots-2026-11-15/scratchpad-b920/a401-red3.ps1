# Red run 3 + final green (H2/unit only, no heavy lock), JDK 25 from the caller's environment.
# Red 3: the two fixes the first red run could not show on the server, each with every other fix IN PLACE:
#  - ServerWebProblemHandler removed (the token filters fixed): the onboarding approve problem must still reach the
#    caller as application/problem+json with its code and instance;
#  - application.yaml reverted (fresh copy: the file is touched): Jackson 3's default refuses an absent primitive.
# Every reverted/restored file is touched, so the incremental compiler and the resources plugin take it (Copy-Item
# keeps a file's old LastWriteTime). Restore is SHA-256 checked. Then the final green run on the restored content.
param([string]$Tag = "a401-jdk25-red3")
$S = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad"
$W = "C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-auth401\sangam"
$I = "D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam"
$K = "$S\$Tag-keep"
$R = "$S\$Tag.runner.log"
function Log([string]$m) {
    ((Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ") + " " + $m) | Add-Content -Encoding utf8 $R
}
$env:JAVA_HOME = "D:\GITHUB  Project\.tools\OpenJDK25U-jdk_x64_windows_hotspot_25.0.5_8\jdk-25.0.5+8"
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path
$removed = @(
    "sangam-server\src\main\java\org\sangam\server\web\ServerWebProblemHandler.java",
    "sangam-server\src\test\java\org\sangam\server\web\ServerWebProblemHandlerTest.java")
$reverted = @(
    "sangam-server\src\main\resources\application.yaml")
Log "red3 runner start pid=${PID} JAVA_HOME=$env:JAVA_HOME"
$before = @{}
foreach ($f in $removed + $reverted) {
    $before[$f] = (Get-FileHash "$W\$f").Hash
    New-Item -ItemType Directory -Force (Split-Path "$K\$f") | Out-Null
    Copy-Item "$W\$f" "$K\$f" -Force
}
try {
    foreach ($f in $removed) { Remove-Item "$W\$f" }
    foreach ($f in $reverted) { Copy-Item "$I\$f" "$W\$f" -Force; (Get-Item "$W\$f").LastWriteTime = Get-Date }
    Log "handler removed, application.yaml reverted (touched); maven test start"
    Set-Location $W
    & .\mvnw.cmd "-Dsangam.target=target-auth401" -pl sangam-server -am test "-Dtest=RequestStatusContractH2Test" `
        "-Dsurefire.failIfNoSpecifiedTests=false" "-Dmaven.test.failure.ignore=true" *> "$S\$Tag.log"
    Log "red3 maven exit $LASTEXITCODE (red expected: test failures, not compile errors)"
} finally {
    foreach ($f in $removed + $reverted) { Copy-Item "$K\$f" "$W\$f" -Force; (Get-Item "$W\$f").LastWriteTime = Get-Date }
    $bad = 0
    foreach ($f in $removed + $reverted) {
        if ((Get-FileHash "$W\$f").Hash -ne $before[$f]) { Log "RESTORE MISMATCH $f"; $bad++ }
    }
    Log "restored $(($removed + $reverted).Count) files (touched), mismatches=$bad"
}
if ($bad -eq 0) {
    Log "green-final start"
    Set-Location $W
    & .\mvnw.cmd "-Dsangam.target=target-auth401" -pl sangam-config,sangam-packs,sangam-server -am test `
        "-Dtest=BillConnectorContractTest,ConnectorInputRenameTest,RequestStatusContractH2Test,ServerWebProblemHandlerTest,DecisionCitationGuardTest,QuotaPoolDeadlockTtl30sH2Test,QuotaPoolDeadlockTtl0H2Test" `
        "-DreuseForks=false" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dmaven.test.failure.ignore=true" *> "$S\$Tag-green.log"
    Log "green-final exit $LASTEXITCODE"
}
Log "RUNNER DONE"

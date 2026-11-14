# SL-76 red then green (H2/unit only, no heavy lock), JDK 25, detached.
# Red: the fix reverted (WebhookInboundEndpoint, its unit test of the new entry, PortsEventBridge = integration-tree
#      versions); the server regression test SignedChannelPublishH2Test stays and must fail (403 on the signed channel).
# Green: the fix restored; ports ingress tests, the new tests, the request-status contract and the citation guard.
# Reverted/restored files are touched (Copy-Item keeps old timestamps; the incremental build must recompile them).
param([string]$Tag = "a401-sl76")
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
$reverted = @(
    "sangam-flowable-ports\src\main\java\org\sangam\ports\events\channel\WebhookInboundEndpoint.java",
    "sangam-flowable-ports\src\test\java\org\sangam\ports\events\channel\WebhookInboundEndpointTest.java",
    "sangam-server\src\main\java\org\sangam\server\bridge\PortsEventBridge.java")
Log "runner start pid=${PID} JAVA_HOME=$env:JAVA_HOME"
$before = @{}
foreach ($f in $reverted) {
    $before[$f] = (Get-FileHash "$W\$f").Hash
    New-Item -ItemType Directory -Force (Split-Path "$K\$f") | Out-Null
    Copy-Item "$W\$f" "$K\$f" -Force
}
$bad = 0
try {
    foreach ($f in $reverted) { Copy-Item "$I\$f" "$W\$f" -Force; (Get-Item "$W\$f").LastWriteTime = Get-Date }
    Log "fix reverted (touched); red maven start"
    Set-Location $W
    & .\mvnw.cmd "-Dsangam.target=target-auth401" -pl sangam-server -am test "-Dtest=SignedChannelPublishH2Test" `
        "-Dsurefire.failIfNoSpecifiedTests=false" "-Dmaven.test.failure.ignore=true" *> "$S\$Tag-red.log"
    Log "red maven exit $LASTEXITCODE (expected: test failure, not a compile error)"
} finally {
    foreach ($f in $reverted) { Copy-Item "$K\$f" "$W\$f" -Force; (Get-Item "$W\$f").LastWriteTime = Get-Date }
    foreach ($f in $reverted) { if ((Get-FileHash "$W\$f").Hash -ne $before[$f]) { Log "RESTORE MISMATCH $f"; $bad++ } }
    Log "restored $($reverted.Count) files (touched), mismatches=$bad"
}
if ($bad -eq 0) {
    Log "green maven start"
    Set-Location $W
    & .\mvnw.cmd "-Dsangam.target=target-auth401" -pl sangam-flowable-ports,sangam-server -am test `
        "-Dtest=WebhookInboundEndpointTest,FwtChannelGuardsTest,SignedChannelPublishH2Test,RequestStatusContractH2Test,DecisionCitationGuardTest" `
        "-DreuseForks=false" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dmaven.test.failure.ignore=true" *> "$S\$Tag-green.log"
    Log "green maven exit $LASTEXITCODE"
}
Log "RUNNER DONE"

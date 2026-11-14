# Red run (H2/unit only, no heavy lock): the regression tests against the integration-tree versions of every fixed
# place. ServerWebProblemHandler (and its unit test, which needs the class) is removed; the other fixed files get their
# integration-tree content; the tests stay. Every file is restored byte for byte afterwards (SHA-256 checked).
# Build validity: Copy-Item keeps a file's old LastWriteTime, so the incremental compiler and the resources plugin
# would keep the classes and resources of the fixed version ("Nothing to compile"). Hence: every reverted and every
# restored file is touched, and the red build is a clean build of every -am module.
param([string]$Tag = "a401-red1")
$S = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad"
$W = "C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-auth401\sangam"
$I = "D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam"
$K = "$S\$Tag-keep"
$R = "$S\$Tag.runner.log"
function Log([string]$m) {
    ((Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ") + " " + $m) | Add-Content -Encoding utf8 $R
}
$removed = @(
    "sangam-server\src\main\java\org\sangam\server\web\ServerWebProblemHandler.java",
    "sangam-server\src\test\java\org\sangam\server\web\ServerWebProblemHandlerTest.java")
$reverted = @(
    "sangam-server\src\main\java\org\sangam\server\security\DevTokenFilter.java",
    "sangam-security\src\main\java\org\sangam\security\oidc\TokenAuthenticationFilters.java",
    "sangam-server\src\main\resources\application.yaml",
    "sangam-server\src\main\java\org\sangam\server\bridge\PortsEventBridge.java",
    "sangam-config\src\main\java\org\sangam\config\api\ConfigConnector.java",
    "sangam-packs\src\main\java\org\sangam\packs\core\Vars.java",
    "sangam-packs\src\main\java\org\sangam\packs\core\ConnectorCallDelegate.java",
    "sangam-packs\src\main\java\org\sangam\packs\sim\SimulatorConnectorGateway.java",
    "sangam-packs\src\main\resources\packs\ifms-bill\pack.json",
    "sangam-packs\src\main\resources\packs\ifms-bill\sangam-ifms-bill.bpmn",
    "sangam-packs\src\main\resources\packs\ifms-pao-chain\sangam-ifms-pao-chain.bpmn",
    "sangam-packs\src\main\resources\packs\ifms-receipts\sangam-ifms-receipt.bpmn")
Log "red runner start pid=$PID"
$before = @{}
foreach ($f in $removed + $reverted) {
    $before[$f] = (Get-FileHash "$W\$f").Hash
    New-Item -ItemType Directory -Force (Split-Path "$K\$f") | Out-Null
    Copy-Item "$W\$f" "$K\$f" -Force
}
try {
    foreach ($f in $removed) { Remove-Item "$W\$f" }
    foreach ($f in $reverted) { Copy-Item "$I\$f" "$W\$f" -Force; (Get-Item "$W\$f").LastWriteTime = Get-Date }
    Log "fixes reverted (touched); maven clean test start"
    Set-Location $W
    & .\mvnw.cmd "-Dsangam.target=target-auth401" -pl sangam-config,sangam-packs,sangam-server -am clean test `
        "-Dtest=BillConnectorContractTest,ConnectorInputRenameTest,RequestStatusContractH2Test,QuotaPoolDeadlockTtl30sH2Test" `
        "-DreuseForks=false" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dmaven.test.failure.ignore=true" *> "$S\$Tag.log"
    Log "maven exit $LASTEXITCODE (red expected: test failures, not compile errors)"
} finally {
    foreach ($f in $removed + $reverted) { Copy-Item "$K\$f" "$W\$f" -Force; (Get-Item "$W\$f").LastWriteTime = Get-Date }
    $bad = 0
    foreach ($f in $removed + $reverted) {
        if ((Get-FileHash "$W\$f").Hash -ne $before[$f]) { Log "RESTORE MISMATCH $f"; $bad++ }
    }
    Log "restored $(($removed + $reverted).Count) files (touched), mismatches=$bad"
    Log "RUNNER DONE"
}

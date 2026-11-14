# APPROVE-401 light gates on JDK 25 (SL-63 rule): no heavy lock (no failsafe, no Testcontainers). Detached runner.
#   -Phase compile : clean test-compile of sangam-server and every module it depends on (-am), skip tests
#   -Phase unit    : the touched modules' unit suites (surefire, H2), -am
#   -Phase red     : delegated to a401-red.ps1 with JDK 25 in the environment
param([Parameter(Mandatory = $true)][string]$Phase, [string]$Tag = "")
$S = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad"
$W = "C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-auth401\sangam"
if ($Tag -eq "") { $Tag = "a401-jdk25-$Phase" }
$R = "$S\$Tag.runner.log"
function Log([string]$m) {
    ((Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ") + " " + $m) | Add-Content -Encoding utf8 $R
}
$env:JAVA_HOME = "D:\GITHUB  Project\.tools\OpenJDK25U-jdk_x64_windows_hotspot_25.0.5_8\jdk-25.0.5+8"
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path
Log "runner start pid=$PID phase=$Phase JAVA_HOME=$env:JAVA_HOME"
try {
    & "$env:JAVA_HOME\bin\java.exe" -version *>> $R
    Set-Location $W
    switch ($Phase) {
        "compile" {
            & .\mvnw.cmd "-Dsangam.target=target-auth401" -pl sangam-server -am clean test-compile -DskipTests *> "$S\$Tag.log"
        }
        "unit" {
            & .\mvnw.cmd "-Dsangam.target=target-auth401" -pl sangam-config,sangam-packs,sangam-security,sangam-onboarding,sangam-server -am test `
                "-Dtest=org.sangam.config.**.*Test,org.sangam.packs.**.*Test,org.sangam.security.**.*Test,org.sangam.onboarding.**.*Test,org.sangam.server.**.*Test" `
                "-Dsurefire.failIfNoSpecifiedTests=false" "-Dmaven.test.failure.ignore=true" *> "$S\$Tag.log"
        }
        "server" {
            # One JVM per test class: BulkIngestRestH2Test, OutboxRepairDeadLetterTest, RollbackGuardSpringTxTest and
            # NoClassPathAutoDeploymentTest have no @DirtiesContext, so a cached context keeps its engine alive and the
            # next engine in a shared JVM trips the D-10 one-engine guard (pre-existing; seen in a401-jdk25-unit.log).
            & .\mvnw.cmd "-Dsangam.target=target-auth401" -pl sangam-server -am test "-Dtest=org.sangam.server.**.*Test" `
                "-DreuseForks=false" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dmaven.test.failure.ignore=true" *> "$S\$Tag.log"
        }
        "red" {
            & "C:\Program Files\PowerShell\7\pwsh.exe" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$S\a401-red.ps1" -Tag $Tag
        }
        "red2" {
            & "C:\Program Files\PowerShell\7\pwsh.exe" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$S\a401-red2.ps1" -Tag $Tag
        }
        "redseq" {
            # both red runs back to back (they edit the worktree, so never in parallel with anything else)
            & "C:\Program Files\PowerShell\7\pwsh.exe" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$S\a401-red.ps1" -Tag "$Tag-1"
            & "C:\Program Files\PowerShell\7\pwsh.exe" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$S\a401-red2.ps1" -Tag "$Tag-2"
            # then green on the final content: the regression tests, the citation guard, and the SL-43 pool test that
            # failed once in the isolated server run (timing of 'nobody left waiting'), with and without my change
            Log "green-final start"
            Set-Location $W
            & .\mvnw.cmd "-Dsangam.target=target-auth401" -pl sangam-config,sangam-packs,sangam-server -am test `
                "-Dtest=BillConnectorContractTest,ConnectorInputRenameTest,RequestStatusContractH2Test,ServerWebProblemHandlerTest,DecisionCitationGuardTest,QuotaPoolDeadlockTtl30sH2Test,QuotaPoolDeadlockTtl0H2Test" `
                "-DreuseForks=false" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dmaven.test.failure.ignore=true" *> "$S\$Tag-green.log"
            Log "green-final exit $LASTEXITCODE"
        }
        default { Log "unknown phase $Phase" }
    }
    Log "exit $LASTEXITCODE"
} finally {
    Log "RUNNER DONE"
}

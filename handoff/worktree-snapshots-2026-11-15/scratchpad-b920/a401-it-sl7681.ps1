param([string]$Tag = "a401-it-sl7681")
$S = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad"
$I = "D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam"
$R = "$S\$Tag.runner.log"
function Log([string]$m) { ((Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ") + " " + $m) | Add-Content -Encoding utf8 $R }
$env:JAVA_HOME = "D:\GITHUB  Project\.tools\OpenJDK25U-jdk_x64_windows_hotspot_25.0.5_8\jdk-25.0.5+8"; $env:Path = "$env:JAVA_HOME\bin;" + $env:Path
Log "start pid=${PID}"
Set-Location $I
& .\mvnw.cmd "-Dsangam.target=target-auth401" -pl sangam-server -am clean test-compile -DskipTests *> "$S\$Tag-compile.log"
Log "compile exit $LASTEXITCODE"
& .\mvnw.cmd "-Dsangam.target=target-auth401" -pl sangam-flowable-ports,sangam-server -am test "-Dtest=WebhookInboundEndpointTest,FwtChannelGuardsTest,OpenApiCompatibilityTest,SignedChannelPublishH2Test,RequestStatusContractH2Test,ServerWebProblemHandlerTest,DecisionCitationGuardTest" "-DreuseForks=false" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dmaven.test.failure.ignore=true" *> "$S\$Tag-tests.log"
Log "tests exit $LASTEXITCODE"
Log "RUNNER DONE"

# Unit suites (surefire, H2 only, no heavy lock) of the modules APPROVE-401 touches, with -am, detached.
param([string]$Tag = "a401-unit1")
$S = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad"
$W = "C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-auth401\sangam"
$R = "$S\$Tag.runner.log"
function Log([string]$m) {
    ((Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ") + " " + $m) | Add-Content -Encoding utf8 $R
}
Log "unit runner start pid=$PID"
try {
    Set-Location $W
    & .\mvnw.cmd "-Dsangam.target=target-auth401" -pl sangam-config,sangam-packs,sangam-security,sangam-onboarding,sangam-server -am test `
        "-Dtest=org.sangam.config.**.*Test,org.sangam.packs.**.*Test,org.sangam.security.**.*Test,org.sangam.onboarding.**.*Test,org.sangam.server.**.*Test" `
        "-Dsurefire.failIfNoSpecifiedTests=false" "-Dmaven.test.failure.ignore=true" *> "$S\$Tag.log"
    Log "maven exit $LASTEXITCODE"
} finally {
    Log "RUNNER DONE"
}

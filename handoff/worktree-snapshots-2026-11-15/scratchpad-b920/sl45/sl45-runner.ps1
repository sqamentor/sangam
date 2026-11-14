# SL-45 detached heavy-lock runner (owner customwf-iso). One phase per run:
#   red   = corrected CrossTenantIsolationIT + product WITHOUT the SL-45 fixes (toggle nofix)
#   green = corrected CrossTenantIsolationIT + product WITH the SL-45 fixes (toggle fix)
# acquire (abort unless exit 0) -> Maven clean verify -> release, in this script's own try/finally; the product toggle is
# restored to "fix" in the same finally. Started outside the agent's job object (Win32_Process.Create), so an agent or
# session death cannot kill it before its finally runs. JDK 25 by default (SL-63 gate: CI compiles on 21.0.13/25/27);
# "clean" so every class is compiled by that JDK.
param([Parameter(Mandatory = $true)][ValidateSet("red", "green")][string]$Phase,
      [ValidateSet("21", "25")][string]$Jdk = "25")

$L = "C:\Users\Lokendra Singh\AppData\Local\Temp\claude\D--GITHUB--Project-IFMS\ff9f2caf-1246-4228-ae70-82a16cf209ae\scratchpad\heavy_lock.py"
$PY = "C:\Python313\python.exe"
$S = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\sl45"
$WT = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-iso\sangam"
$tag = "$Phase-jdk$Jdk"
$log = "$S\runner-$tag.log"
$env:JAVA_HOME = if ($Jdk -eq "25") { "D:\GITHUB  Project\.tools\OpenJDK25U-jdk_x64_windows_hotspot_25.0.5_8\jdk-25.0.5+8" } else { "C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot" }

function Log([string]$m) { Add-Content -Path $log -Encoding utf8 -Value ("{0} {1}" -f (Get-Date).ToUniversalTime().ToString("o"), $m) }

Log "runner start phase=$Phase jdk=$Jdk pid=$PID JAVA_HOME=$env:JAVA_HOME"
& $PY $L acquire customwf-iso "SL-45 CrossTenantIsolationIT postgres $Phase JDK$Jdk (engine TenantScopedCreation), detached runner pid $PID" 2>&1 |
        ForEach-Object { Log "lock: $_" }
$acquired = $LASTEXITCODE
if ($acquired -ne 0) {
    Log "ACQUIRE FAILED ($acquired): not running"
    Log "RUNNER-END"
    exit 2
}
try {
    Log "lock acquired"
    & "$env:JAVA_HOME\bin\java.exe" -version 2>&1 | ForEach-Object { Log "java: $_" }
    $mode = if ($Phase -eq "red") { "nofix" } else { "fix" }
    & "$S\toggle-fix.ps1" -Mode $mode 2>&1 | ForEach-Object { Log "toggle $mode`: $_" }
    Push-Location $WT
    try {
        & .\mvnw.cmd -B "-Dsangam.target=target-iso" -Psangam-db-it "-Dsangam.db=postgres" -pl sangam-server -am clean verify `
            "-Dtest=NoSuchTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dit.test=CrossTenantIsolationIT" `
            "-Dfailsafe.failIfNoSpecifiedTests=false" *> "$S\it-$tag.log"
        Log "mvn exit $LASTEXITCODE"
    } finally {
        Pop-Location
    }
    $rep = "$WT\sangam-server\target-iso\failsafe-reports"
    $dst = "$S\it-$tag-reports"
    if (Test-Path $dst) { Remove-Item "$dst\*" -Recurse -Force -ErrorAction SilentlyContinue }
    New-Item -ItemType Directory -Force $dst | Out-Null
    if (Test-Path $rep) { Copy-Item "$rep\*" $dst -Force }
    Log "reports copied to $dst"
} finally {
    & "$S\toggle-fix.ps1" -Mode fix 2>&1 | ForEach-Object { Log "toggle back: $_" }
    & $PY $L release customwf-iso 2>&1 | ForEach-Object { Log "lock: $_" }
    Log "release exit $LASTEXITCODE"
    Log "RUNNER-END"
}

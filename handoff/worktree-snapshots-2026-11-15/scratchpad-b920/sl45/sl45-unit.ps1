# SL-45 detached unit runner (H2 only, no Testcontainers, so no heavy lock). One phase per run:
#   engine-red   = TenantScopedCreationH2Test with the product WITHOUT the SL-45 fixes (toggle nofix)
#   suites-green = every unit test of sangam-engine, sangam-rest and sangam-server WITH the fixes (toggle fix), -am
# "clean", so every class is compiled by the chosen JDK (SL-63 gate: 25 as well as 21). The product toggle is restored
# to "fix" in this script's own finally. Started outside the agent's job object (Win32_Process.Create), like the
# PostgreSQL runner, so a session death cannot leave the worktree toggled. Never runs while a PostgreSQL runner of this
# workstream is queued or running (one Maven job at a time).
#   server-forked = every unit test of sangam-server WITH the fixes, one JVM per test class (-DreuseForks=false):
#                   Operaton's static clock allows one Sangam engine per JVM (D-10) and a cached Spring context keeps
#                   its engine, so classes with different contexts cannot share a JVM (register: known, not fixed)
param([Parameter(Mandatory = $true)][ValidateSet("engine-red", "suites-green", "server-forked")][string]$Phase,
      [ValidateSet("21", "25")][string]$Jdk = "25")

$S = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\sl45"
$WT = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-iso\sangam"
$tag = "$Phase-jdk$Jdk"
$log = "$S\unit-$tag.log"
$env:JAVA_HOME = if ($Jdk -eq "25") { "D:\GITHUB  Project\.tools\OpenJDK25U-jdk_x64_windows_hotspot_25.0.5_8\jdk-25.0.5+8" } else { "C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot" }

function Log([string]$m) { Add-Content -Path $log -Encoding utf8 -Value ("{0} {1}" -f (Get-Date).ToUniversalTime().ToString("o"), $m) }

Log "unit runner start phase=$Phase jdk=$Jdk pid=$PID JAVA_HOME=$env:JAVA_HOME"
try {
    & "$env:JAVA_HOME\bin\java.exe" -version 2>&1 | ForEach-Object { Log "java: $_" }
    $mode = if ($Phase -eq "engine-red") { "nofix" } else { "fix" }
    & "$S\toggle-fix.ps1" -Mode $mode 2>&1 | ForEach-Object { Log "toggle $mode`: $_" }
    $modules = @("sangam-engine", "sangam-rest", "sangam-server")
    foreach ($m in $modules) {
        $old = "$WT\$m\target-iso\surefire-reports"
        if (Test-Path $old) { Remove-Item "$old\*" -Recurse -Force -ErrorAction SilentlyContinue }
    }
    Push-Location $WT
    try {
        if ($Phase -eq "engine-red") {
            & .\mvnw.cmd -B "-Dsangam.target=target-iso" -pl sangam-engine -am clean test "-Dtest=TenantScopedCreationH2Test" `
                "-Dsurefire.failIfNoSpecifiedTests=false" *> "$S\unit-$tag-mvn.log"
        } elseif ($Phase -eq "server-forked") {
            & .\mvnw.cmd -B "-Dsangam.target=target-iso" -pl sangam-server -am test `
                "-Dtest=org/sangam/server/**/*Test,org/sangam/server/**/*Tests" "-DreuseForks=false" `
                "-Dsurefire.failIfNoSpecifiedTests=false" *> "$S\unit-$tag-mvn.log"
        } else {
            & .\mvnw.cmd -B "-Dsangam.target=target-iso" -pl sangam-engine,sangam-rest,sangam-server -am clean test `
                "-Dtest=org/sangam/engine/**/*Test,org/sangam/rest/**/*Test,org/sangam/server/**/*Test,org/sangam/server/**/*Tests" `
                "-Dsurefire.failIfNoSpecifiedTests=false" *> "$S\unit-$tag-mvn.log"
        }
        Log "mvn exit $LASTEXITCODE"
    } finally {
        Pop-Location
    }
    foreach ($m in $modules) {
        $rep = "$WT\$m\target-iso\surefire-reports"
        $dst = "$S\unit-$tag-reports\$m"
        if (Test-Path $dst) { Remove-Item "$dst\*" -Recurse -Force -ErrorAction SilentlyContinue }
        New-Item -ItemType Directory -Force $dst | Out-Null
        if (Test-Path $rep) { Copy-Item "$rep\*" $dst -Force }
    }
    Log "reports copied to $S\unit-$tag-reports"
} finally {
    & "$S\toggle-fix.ps1" -Mode fix 2>&1 | ForEach-Object { Log "toggle back: $_" }
    Log "RUNNER-END"
}

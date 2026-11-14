# SL-45 apply gate: unit suites of the touched modules (sangam-engine, sangam-rest, sangam-server) in the INTEGRATION
# TREE on JDK 25 (SL-63 gate). H2 only, no heavy lock. Never edits or toggles a source file. Detached
# (Win32_Process.Create). sangam-server runs one JVM per test class (-DreuseForks=false): a cached Spring context keeps
# its engine and Operaton's static clock allows one Sangam engine per JVM (D-10; the shared-JVM cascade is SL-67).
$S = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\sl45"
$INT = "D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam"
$log = "$S\int-unit.log"
$env:JAVA_HOME = "D:\GITHUB  Project\.tools\OpenJDK25U-jdk_x64_windows_hotspot_25.0.5_8\jdk-25.0.5+8"
function Log([string]$m) { Add-Content -Path $log -Encoding utf8 -Value ("{0} {1}" -f (Get-Date).ToUniversalTime().ToString("o"), $m) }

Log "int-unit start pid=$PID JAVA_HOME=$env:JAVA_HOME"
& "$env:JAVA_HOME\bin\java.exe" -version 2>&1 | ForEach-Object { Log "java: $_" }
Push-Location $INT
try {
    & .\mvnw.cmd -B "-Dsangam.target=target-iso" -pl sangam-engine,sangam-rest -am clean test `
        "-Dtest=org/sangam/engine/**/*Test,org/sangam/rest/**/*Test" "-Dsurefire.failIfNoSpecifiedTests=false" `
        *> "$S\int-unit-engine-rest-mvn.log"
    Log "engine+rest mvn exit $LASTEXITCODE"
    & .\mvnw.cmd -B "-Dsangam.target=target-iso" -pl sangam-server -am test `
        "-Dtest=org/sangam/server/**/*Test,org/sangam/server/**/*Tests" "-DreuseForks=false" `
        "-Dsurefire.failIfNoSpecifiedTests=false" *> "$S\int-unit-server-mvn.log"
    Log "server mvn exit $LASTEXITCODE"
} finally {
    Pop-Location
    foreach ($m in @("sangam-engine", "sangam-rest", "sangam-server")) {
        $rep = "$INT\$m\target-iso\surefire-reports"
        $dst = "$S\int-unit-reports\$m"
        if (Test-Path $dst) { Remove-Item "$dst\*" -Recurse -Force -ErrorAction SilentlyContinue }
        New-Item -ItemType Directory -Force $dst | Out-Null
        if (Test-Path $rep) { Copy-Item "$rep\*" $dst -Force }
    }
    Log "reports copied to $S\int-unit-reports"
    Log "RUNNER-END"
}

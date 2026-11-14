# APPROVE-401 after application: compile gate in the INTEGRATION tree on JDK 25 (SL-63 rule), own build folder
# target-auth401 so no other agent's build output is touched. No tests, no lock (compile only).
param([string]$Tag = "a401-itcompile")
$S = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad"
$I = "D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam"
$R = "$S\$Tag.runner.log"
function Log([string]$m) {
    ((Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ") + " " + $m) | Add-Content -Encoding utf8 $R
}
$env:JAVA_HOME = "D:\GITHUB  Project\.tools\OpenJDK25U-jdk_x64_windows_hotspot_25.0.5_8\jdk-25.0.5+8"
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path
Log "runner start pid=${PID} JAVA_HOME=$env:JAVA_HOME"
try {
    Set-Location $I
    & .\mvnw.cmd "-Dsangam.target=target-auth401" -pl sangam-server -am clean test-compile -DskipTests *> "$S\$Tag.log"
    Log "maven exit $LASTEXITCODE"
} finally {
    Log "RUNNER DONE"
}

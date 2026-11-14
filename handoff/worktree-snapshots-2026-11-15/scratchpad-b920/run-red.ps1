# Red runs for SL-53: base (integration-tree) engine code + the reworked DstFoldTimerH2Test, in a Berlin JVM and in a UTC JVM.
$W = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-dst\sangam"
$B = "D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam"
$S = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad"
$bak = "$S\dst-fix-bak"
$eng = "sangam-engine\src\main\java\org\sangam\engine"
$files = @("pom.xml", "$eng\SangamEngineSettings.java", "$eng\SangamEngineComponents.java", "$eng\SangamEngineConfigurer.java",
           "$eng\timer\TimerGuard.java", "$eng\timer\DstSafeCycleBusinessCalendar.java", "$eng\timer\CycleExpression.java",
           "$eng\timer\TaggedDurationBusinessCalendar.java", "sangam-engine\src\test\java\org\sangam\engine\EngineInternalsPinTest.java")
Remove-Item $bak -Recurse -Force -ErrorAction SilentlyContinue
foreach ($f in $files + @("$eng\timer\ZonedCron.java")) { $d = Split-Path "$bak\$f"; New-Item -ItemType Directory -Force $d | Out-Null; Copy-Item "$W\$f" "$bak\$f" -Force }
function RunIt($zone, $log) {
    $env:JAVA_TOOL_OPTIONS = "-Duser.timezone=$zone"
    Push-Location $W
    & .\mvnw.cmd -o "-Dsangam.target=target-dst" -pl sangam-engine -am clean test "-Dtest=DstFoldTimerH2Test" "-Dsurefire.failIfNoSpecifiedTests=false" *> "$S\$log"
    "EXIT $LASTEXITCODE" | Add-Content "$S\$log"
    Pop-Location
    Remove-Item Env:\JAVA_TOOL_OPTIONS
}
try {
    foreach ($f in $files) { Copy-Item "$B\$f" "$W\$f" -Force }
    Remove-Item "$W\$eng\timer\ZonedCron.java" -Force
    RunIt "Europe/Berlin" "dst-red-base-berlin-jvm.log"
    RunIt "UTC" "dst-red-base-utc-jvm.log"
} finally {
    foreach ($f in $files + @("$eng\timer\ZonedCron.java")) { Copy-Item "$bak\$f" "$W\$f" -Force; (Get-Item "$W\$f").LastWriteTime = Get-Date }
}
foreach ($l in "dst-red-base-berlin-jvm.log", "dst-red-base-utc-jvm.log") { "== $l"; Select-String "$S\$l" -Pattern "Picked up|Tests run:.*DstFold|^\[ERROR\]   |EXIT" | % Line }

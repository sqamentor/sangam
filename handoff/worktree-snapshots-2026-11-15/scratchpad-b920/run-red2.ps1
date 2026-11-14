# Red runs for SL-53 on the FINAL test file: the committed 1105d9c engine code (no SL-53 change) + the reworked
# DstFoldTimerH2Test, in a Berlin JVM and in a UTC JVM. sangam-core keeps EngineTimeZone (the test calls the predicate).
$W = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-dst\sangam"
$S = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad"
$B = "$S\base-1105d9c\sangam"
$bak = "$S\dst-fix-bak2"
$eng = "sangam-engine\src\main\java\org\sangam\engine"
$files = @("pom.xml", "$eng\SangamEngineSettings.java", "$eng\SangamEngineComponents.java", "$eng\SangamEngineConfigurer.java",
           "$eng\timer\TimerGuard.java", "$eng\timer\DstSafeCycleBusinessCalendar.java", "$eng\timer\CycleExpression.java",
           "$eng\timer\TaggedDurationBusinessCalendar.java", "sangam-engine\src\test\java\org\sangam\engine\EngineInternalsPinTest.java",
           "sangam-engine\src\test\java\org\sangam\engine\timer\CycleExpressionTest.java")
$new = @("$eng\timer\ZonedCron.java")
if (Test-Path $bak) { Get-ChildItem $bak -Recurse -File | ForEach-Object { $_.Delete() } }
foreach ($f in $files + $new) { $d = Split-Path "$bak\$f"; New-Item -ItemType Directory -Force $d | Out-Null; Copy-Item "$W\$f" "$bak\$f" -Force }
function RunIt($zone, $log) {
    $env:JAVA_TOOL_OPTIONS = "-Duser.timezone=$zone"
    Push-Location $W
    & .\mvnw.cmd -o "-Dsangam.target=target-dst" -pl sangam-engine -am clean test "-Dtest=DstFoldTimerH2Test" "-Dsurefire.failIfNoSpecifiedTests=false" *> "$S\$log"
    "EXIT $LASTEXITCODE" | Add-Content "$S\$log"
    Pop-Location
    [Environment]::SetEnvironmentVariable("JAVA_TOOL_OPTIONS", $null)
}
try {
    foreach ($f in $files) { Copy-Item "$B\$f" "$W\$f" -Force }
    foreach ($f in $new) { [IO.File]::Delete("$W\$f") }
    RunIt "Europe/Berlin" "dst-red2-base-berlin-jvm.log"
    RunIt "UTC" "dst-red2-base-utc-jvm.log"
} finally {
    foreach ($f in $files + $new) { Copy-Item "$bak\$f" "$W\$f" -Force; (Get-Item "$W\$f").LastWriteTime = Get-Date }
}
foreach ($l in "dst-red2-base-berlin-jvm.log", "dst-red2-base-utc-jvm.log") { "== $l"; Select-String "$S\$l" -Pattern "Picked up|Tests run:.*DstFold|^\[ERROR\]   |EXIT" | % Line }

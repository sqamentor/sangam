param([string]$Log = "sl57-unit.log")
# SL-57 unit gate in the private worktree (no lock: H2 / plain unit tests and compiles).
$out = Join-Path $PSScriptRoot $Log
$wt = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-ifmswf\sangam"
$jdk25 = "D:\GITHUB  Project\.tools\OpenJDK25U-jdk_x64_windows_hotspot_25.0.5_8\jdk-25.0.5+8"
Set-Location $wt
"== packgen --check" | Set-Content $out
python sangam-packs/tools/packgen/main.py --check *>> $out
"CHECK_EXIT $LASTEXITCODE" | Add-Content $out
"== unit JDK 21: config, packs, flowable-ports (-am)" | Add-Content $out
& .\mvnw.cmd -o "-Dsangam.target=target-ifmswf" "-DreuseForks=false" -pl sangam-config,sangam-packs,sangam-flowable-ports -am clean test *>> $out
"UNIT21_EXIT $LASTEXITCODE" | Add-Content $out
"== JDK 25 gate: sangam-server -am test-compile" | Add-Content $out
$env:JAVA_HOME = $jdk25
& .\mvnw.cmd -o -q "-Dsangam.target=target-ifmswf25" -pl sangam-server -am -DskipTests test-compile *>> $out
"COMPILE25_EXIT $LASTEXITCODE" | Add-Content $out

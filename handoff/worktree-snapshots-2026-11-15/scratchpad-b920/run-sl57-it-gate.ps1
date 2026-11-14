param([string]$Log = "sl57-it-gate.log")
# SL-57 apply gate in the INTEGRATION tree (no lock): JDK 25 compile gate, then packs and config suites (-am).
$out = Join-Path $PSScriptRoot $Log
$it = "D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam"
Set-Location $it
"== packgen --check" | Set-Content $out
python sangam-packs/tools/packgen/main.py --check *>> $out
"CHECK_EXIT $LASTEXITCODE" | Add-Content $out
$jdk21 = $env:JAVA_HOME
$env:JAVA_HOME = "D:\GITHUB  Project\.tools\OpenJDK25U-jdk_x64_windows_hotspot_25.0.5_8\jdk-25.0.5+8"
"== JDK 25: -pl sangam-server -am -DskipTests test-compile" | Add-Content $out
& .\mvnw.cmd -o -q "-Dsangam.target=target-ifmswf25" -pl sangam-server -am -DskipTests test-compile *>> $out
"COMPILE25_EXIT $LASTEXITCODE" | Add-Content $out
"== JDK 25: packs and config suites (-am, -DreuseForks=false)" | Add-Content $out
& .\mvnw.cmd -o "-Dsangam.target=target-ifmswf25" "-DreuseForks=false" -pl sangam-packs,sangam-config -am test *>> $out
"UNIT25_EXIT $LASTEXITCODE" | Add-Content $out
$env:JAVA_HOME = $jdk21

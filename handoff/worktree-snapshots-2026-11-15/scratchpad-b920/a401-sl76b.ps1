param([string]$Tag = "a401-sl76b")
$S = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad"
$W = "C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-auth401\sangam"
$env:JAVA_HOME = "D:\GITHUB  Project\.tools\OpenJDK25U-jdk_x64_windows_hotspot_25.0.5_8\jdk-25.0.5+8"; $env:Path = "$env:JAVA_HOME\bin;" + $env:Path
Set-Location $W
& .\mvnw.cmd "-Dsangam.target=target-auth401" -pl sangam-server -am test "-Dtest=SignedChannelPublishH2Test" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dmaven.test.failure.ignore=true" *> "$S\$Tag.log"
"RUNNER DONE exit $LASTEXITCODE" | Add-Content "$S\$Tag.runner.log"

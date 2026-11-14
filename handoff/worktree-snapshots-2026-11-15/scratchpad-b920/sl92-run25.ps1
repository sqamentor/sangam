$env:JAVA_HOME='D:\GITHUB  Project\.tools\OpenJDK25U-jdk_x64_windows_hotspot_25.0.5_8\jdk-25.0.5+8'; $env:Path="$env:JAVA_HOME\bin;$env:Path"; $env:JAVA_TOOL_OPTIONS='-Duser.timezone=UTC'
$w='C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad'
Set-Location "$w\wt-sl92\sangam"
.\mvnw.cmd -o -B clean test -pl 'sangam-core,sangam-flowable-ports,sangam-resources' -am '-Dsangam.target=target-sl92' '-Dtest=org.sangam.core.**.*Test,org.sangam.ports.**.*Test,org.sangam.resources.**.*Test' '-Dsurefire.failIfNoSpecifiedTests=false' > "$w\sl92-green2-jdk25.log" 2>&1
"green exit $LASTEXITCODE"
.\mvnw.cmd -o -B test-compile -pl sangam-server -am -DskipTests '-Dsangam.target=target-sl92' > "$w\sl92-gate-jdk25.log" 2>&1
"gate exit $LASTEXITCODE"

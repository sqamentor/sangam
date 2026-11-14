$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot'; $env:Path="$env:JAVA_HOME\bin;$env:Path"; $env:JAVA_TOOL_OPTIONS='-Duser.timezone=UTC'
$w='C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad'
Set-Location "$w\wt-sl92\sangam"
java -version 2>&1 | Select-Object -First 1
.\mvnw.cmd -o -B clean test -pl 'sangam-core,sangam-flowable-ports,sangam-resources' -am '-Dsangam.target=target-sl92-jdk21' '-Dtest=JacksonModuleDiscoveryGuardTest,SqlJsonTest,QueueNestedSettingsH2Test,JsonModulesTest' '-Dsurefire.failIfNoSpecifiedTests=false' > "$w\sl92-green-jdk21.log" 2>&1
"green21 exit $LASTEXITCODE"
.\mvnw.cmd -o -B test-compile -pl sangam-server -am -DskipTests '-Dsangam.target=target-sl92-jdk21' > "$w\sl92-gate-jdk21.log" 2>&1
"gate21 exit $LASTEXITCODE"

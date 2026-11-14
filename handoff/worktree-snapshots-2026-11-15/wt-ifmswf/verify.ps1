$w = Join-Path $PSScriptRoot "sangam"
$out = Join-Path $PSScriptRoot "verify.log"
Set-Location $w
"== regen 1" | Set-Content $out
python sangam-packs/tools/packgen/main.py *>> $out
"== check (second run)" | Add-Content $out
python sangam-packs/tools/packgen/main.py --check *>> $out
"CHECK $LASTEXITCODE" | Add-Content $out
"== unit" | Add-Content $out
& .\mvnw.cmd -o "-Dsangam.target=target-ifmswf" -pl sangam-flowable-ports,sangam-packs -am clean test `
    "-Dtest=SangamModelValidatorTest,CrossPackCallBindingTest,AllPacksSmokeTest,PilotScenarioTest,PackDeploymentTest,XorRoutingModelToolsTest" `
    "-Dsurefire.failIfNoSpecifiedTests=false" *>> $out
"UNIT $LASTEXITCODE" | Add-Content $out
& .\mvnw.cmd -o -q "-Dsangam.target=target-ifmswf" -pl sangam-server -am -DskipTests test-compile *>> $out
"COMPILE $LASTEXITCODE" | Add-Content $out

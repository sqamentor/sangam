$ErrorActionPreference = 'Continue'
$wt = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-fwtd"
$out = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\fwtd-revert"
New-Item -ItemType Directory -Force $out | Out-Null
$p = "sangam/sangam-flowable-ports/src/main/java/org/sangam/ports/"
$cases = [ordered]@{
  "0-baseline"       = @(@(), "FwtEventRegistryTest,EventDispatcherH2Test,FwtInjectionTest,DynamicInjectionServiceH2Test,FwtJobsTest,FilteredAcquireJobsCmdH2Test,FwtAdHocImportTest,VariablesPortsH2Test,EngineTxH2Test,FwtServicesTest,EventRegistryH2Test,FwtChannelGuardsTest")
  "1-msgstart"       = @(@("events/EventDispatcher.java"), "FwtEventRegistryTest,EventDispatcherH2Test")
  "2-injection"      = @(@("dynamic/DynamicInjectionService.java"), "FwtInjectionTest,DynamicInjectionServiceH2Test")
  "3-acquire"        = @(@("jobs/FilteredAcquireJobsCmd.java"), "FwtJobsTest,FilteredAcquireJobsCmdH2Test")
  "4-handoff"        = @(@("jobs/AsyncJobHandOffInterceptor.java"), "FwtJobsTest")
  "5-varlistener"    = @(@("bpmn/BpmnExtensionsParseListener.java"), "FwtAdHocImportTest,VariablesPortsH2Test")
  "6-enginetx"       = @(@("support/EngineTx.java"), "EngineTxH2Test,FwtServicesTest")
  "7-channeldeploy"  = @(@("events/EventRegistry.java"), "FwtEventRegistryTest,EventRegistryH2Test,FwtChannelGuardsTest")
}
foreach ($k in $cases.Keys) {
  $files = $cases[$k][0]; $tests = $cases[$k][1]
  foreach ($f in $files) { Copy-Item "$wt/$p$f" "$out/$k.bak"; git -C $wt show "HEAD:$p$f" | Set-Content -Encoding utf8 "$wt/$p$f" }
  try {
    Push-Location "$wt/sangam"
    & .\mvnw.cmd -o -pl sangam-flowable-ports -am "-Dsangam.target=target-fwtd" "-Dtest=$tests" "-Dsurefire.failIfNoSpecifiedTests=false" test *> "$out/$k.log"
    "$k exit=$LASTEXITCODE"
  } finally {
    Pop-Location
    foreach ($f in $files) { Copy-Item "$out/$k.bak" "$wt/$p$f" -Force }
  }
}
"DONE"

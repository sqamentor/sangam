param([string]$Label = "probe", [int]$Warmup = 0, [int]$Measure = 1, [int]$Probe = 30, [string]$Extra = "", [string]$Tree = "wt-perf", [string]$Its = "TScaleLoadIT")
$lock = "C:\Users\Lokendra Singh\AppData\Local\Temp\claude\D--GITHUB--Project-IFMS\ff9f2caf-1246-4228-ae70-82a16cf209ae\scratchpad\heavy_lock.py"
$wt = "C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\$Tree\sangam"
$out = "C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\tscale-$Label"
python $lock acquire customwf-perf "SG-15 T-SCALE $Label (TScaleLoadIT, PostgreSQL Testcontainers)"
if ($LASTEXITCODE -ne 0) { Write-Output "LOCK ACQUIRE FAILED - aborting"; exit 2 }
try {
    Set-Location $wt
    $args2 = @("-o", "-pl", "sangam-server", "-am","-Dsangam.target=target-perf", "-Dsangam.db=postgres", "-Dsangam.tscale=true",
        "-Dit.test=$Its", "-Dtest=none", "-Dsurefire.failIfNoSpecifiedTests=false", "-Dfailsafe.failIfNoSpecifiedTests=false",
        "-Dsangam.tscale.warmupSeconds=$Warmup", "-Dsangam.tscale.measureSeconds=$Measure", "-Dsangam.tscale.probe=$Probe",
        "-Dsangam.tscale.out=$out", "-Djacoco.skip=true", "-Dspotbugs.skip=true", "-Dcheckstyle.skip=true", "-Denforcer.skip=true")
    if ($Extra) { $args2 += $Extra.Split(' ') }
    $args2 += "verify"
    & .\mvnw.cmd @args2 2>&1 | Select-String -Pattern "tscale|Tests run|BUILD|ERROR|FAIL|round_trips|^\s+[0-9]" | Select-Object -Last 150
} finally {
    python $lock release customwf-perf
}

# SL-53 PostgreSQL proof: E8DstTimerIT with the JVM in UTC (root pom argLine) and the timer zone set per run.
# The sources are compiled before (the full unit run, target-dst), so the heavy lock covers only the failsafe runs.
param([string[]]$Zones = @("Europe/Berlin", "Asia/Kolkata"))
$L = "C:\Users\Lokendra Singh\AppData\Local\Temp\claude\D--GITHUB--Project-IFMS\ff9f2caf-1246-4228-ae70-82a16cf209ae\scratchpad\heavy_lock.py"
$W = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-dst\sangam"
$S = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad"
[Environment]::SetEnvironmentVariable("JAVA_TOOL_OPTIONS", $null)
python $L acquire customwf-dst "SL-53 E8DstTimerIT PostgreSQL, JVM UTC, timer zones $($Zones -join ',')"
if ($LASTEXITCODE -ne 0) { "ACQUIRE FAILED ($LASTEXITCODE): not running"; exit 2 }
try {
    Push-Location $W
    foreach ($z in $Zones) {
        $log = "$S\pg-e8-utcjvm-" + ($z -replace '/', '-') + ".log"
        & .\mvnw.cmd -o "-Dsangam.target=target-dst" -Psangam-db-it "-Dsangam.db=postgres" -pl sangam-server -am verify `
            "-Dtest=none" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dit.test=E8DstTimerIT" "-Dfailsafe.failIfNoSpecifiedTests=false" `
            "-Dsangam.engine.timer-zone=$z" *> $log
        "EXIT $LASTEXITCODE" | Add-Content $log
    }
    Pop-Location
} finally {
    python $L release customwf-dst
}
foreach ($z in $Zones) { $log = "$S\pg-e8-utcjvm-" + ($z -replace '/', '-') + ".log"; "== $log"; Select-String $log -Pattern "Picked up|\[acceptance\] E8|Tests run:.*E8|^\[ERROR\]   |EXIT" | % Line }

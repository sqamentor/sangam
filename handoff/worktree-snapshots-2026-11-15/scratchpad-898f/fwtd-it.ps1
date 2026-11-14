# I-45: failsafe/Testcontainers only after a successful acquire; abort otherwise; release right after.
param([string]$Tests = "FwtWorkerCompatIT", [string]$Log = "fwtd-it.log")
$L = "C:\Users\Lokendra Singh\AppData\Local\Temp\claude\D--GITHUB--Project-IFMS\ff9f2caf-1246-4228-ae70-82a16cf209ae\scratchpad\heavy_lock.py"
$S = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad"
Set-Location "$S\wt-fwtd\sangam"
python $L acquire customwf-fwtd "PG $Tests"
if ($LASTEXITCODE -ne 0) { "ACQUIRE FAILED ($LASTEXITCODE): not running"; exit 2 }
try {
    .\mvnw.cmd "-Dsangam.target=target-fwtd" -Psangam-db-it "-Dsangam.db=postgres" -pl sangam-server -am clean verify `
        "-Dtest=OpenApiCompatibilityTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dit.test=$Tests" "-Dfailsafe.failIfNoSpecifiedTests=false" *> "$S\$Log"
    "mvn exit $LASTEXITCODE"
} finally {
    python $L release customwf-fwtd
}
Select-String "$S\$Log" -Pattern "Tests run:|BUILD" | Select -Last 8 | % Line


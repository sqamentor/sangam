$lock = "C:\Users\Lokendra Singh\AppData\Local\Temp\claude\D--GITHUB--Project-IFMS\ff9f2caf-1246-4228-ae70-82a16cf209ae\scratchpad\heavy_lock.py"
python $lock acquire customwf-comp "GP-13 / D-TENDER / GP-21 ITs on PostgreSQL (wt-comp)"
if ($LASTEXITCODE -ne 0) { "LOCK-FAILED"; exit 2 }
try {
    Set-Location "C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-comp\sangam"
    .\mvnw.cmd -o -pl sangam-server -am "-Dsangam.target=target-comp" "-Dsangam.db=postgres" "-Dtest=none" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dit.test=E16aPatternsIT#gp13_refundViaCompensation+E17aDomainsIT#d3_tender+E16bPatternsIT#gp21_adHocCase" "-Dfailsafe.failIfNoSpecifiedTests=false" verify 2>&1 | Select-String -Pattern "Tests run:|<<<|expected|got |GP-|D-TENDER|BUILD|ERROR\]" | Select-Object -Last 60
    "MVN-EXIT $LASTEXITCODE"
} finally {
    python $lock release customwf-comp
    "LOCK-RELEASED"
}

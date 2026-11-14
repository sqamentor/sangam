$lock = "C:\Users\Lokendra Singh\AppData\Local\Temp\claude\D--GITHUB--Project-IFMS\ff9f2caf-1246-4228-ae70-82a16cf209ae\scratchpad\heavy_lock.py"
python $lock acquire customwf-comp "E16bPatternsIT GP-21 on PostgreSQL (SL-12)"
if ($LASTEXITCODE -ne 0) { "LOCK-FAILED"; exit 2 }
try {
    Set-Location "D:\GITHUB  Project\Custom WorkFlow\sangam"
    .\mvnw.cmd -o -pl sangam-server -am "-Dsangam.target=target-cmmn" "-Dsangam.db=postgres" "-Dtest=none" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dit.test=E16bPatternsIT#gp21_adHocCase" "-Dfailsafe.failIfNoSpecifiedTests=false" verify 2>&1 | Select-String -Pattern "Tests run:|<<<|expected|GP-21|BUILD|ERROR\]" | Select-Object -Last 40
    "MVN-EXIT $LASTEXITCODE"
} finally {
    python $lock release customwf-comp
    "LOCK-RELEASED"
}

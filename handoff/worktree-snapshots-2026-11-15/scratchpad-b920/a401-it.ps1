# approve-401 / apply-order step 2: gated PostgreSQL acceptance run.
# I-45: failsafe/Testcontainers only after a successful acquire; abort otherwise; release in finally.
param([string]$Tests = "PlugAndPlayIT,E17bDomainIT,T35RestoreIT,IfmsPacksIT", [string]$Log = "a401-it1.log")
$L = "C:\Users\Lokendra Singh\AppData\Local\Temp\claude\D--GITHUB--Project-IFMS\ff9f2caf-1246-4228-ae70-82a16cf209ae\scratchpad\heavy_lock.py"
$S = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad"
$W = "C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-auth401\sangam"
Set-Location $W
python $L acquire customwf-auth401 "approve-401 step 2: PG $Tests"
if ($LASTEXITCODE -ne 0) { "ACQUIRE FAILED ($LASTEXITCODE): not running"; exit 2 }
try {
    "started " + (Get-Date).ToUniversalTime().ToString("u")
    .\mvnw.cmd "-Dsangam.target=target-auth401" -Psangam-db-it "-Dsangam.db=postgres" -pl sangam-server -am clean verify `
        "-Dtest=NoSuchTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dit.test=$Tests" "-Dfailsafe.failIfNoSpecifiedTests=false" *> "$S\$Log"
    "mvn exit $LASTEXITCODE"
} finally {
    python $L release customwf-auth401
    "finished " + (Get-Date).ToUniversalTime().ToString("u")
}
Select-String "$S\$Log" -Pattern "Tests run:|BUILD" | Select-Object -Last 12 | ForEach-Object { $_.Line }

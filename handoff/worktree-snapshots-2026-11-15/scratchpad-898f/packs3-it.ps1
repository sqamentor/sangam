# I-45: run the IT only after a successful acquire; abort otherwise; release right after.
param([string]$Tests = "IfmsPacksIT")
$L = "C:\Users\Lokendra Singh\AppData\Local\Temp\claude\D--GITHUB--Project-IFMS\ff9f2caf-1246-4228-ae70-82a16cf209ae\scratchpad\heavy_lock.py"
$out = Join-Path $PSScriptRoot "p3-it.log"
python $L acquire customwf-packs3 "packs3 $Tests postgres"
if ($LASTEXITCODE -ne 0) { "ABORT: heavy_lock acquire failed ($LASTEXITCODE); IT not run" | Set-Content $out; exit 1 }
try {
    Set-Location (Join-Path $PSScriptRoot "wt-packs3\sangam")
    & .\mvnw.cmd -o "-Dsangam.target=target-packs3" "-Dsangam.db=postgres" -pl sangam-server -am clean verify `
        "-Dtest=none" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dit.test=$Tests" "-Dfailsafe.failIfNoSpecifiedTests=false" *> $out
    "EXIT $LASTEXITCODE" | Add-Content $out
} finally {
    python $L release customwf-packs3
}

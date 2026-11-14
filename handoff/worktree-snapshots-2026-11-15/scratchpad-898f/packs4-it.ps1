# I-45: run the IT only after a successful acquire; abort otherwise; release right after.
param([string]$Tests = "IfmsPacksIT")
$L = "C:\Users\Lokendra Singh\AppData\Local\Temp\claude\D--GITHUB--Project-IFMS\ff9f2caf-1246-4228-ae70-82a16cf209ae\scratchpad\heavy_lock.py"
$out = Join-Path $PSScriptRoot "p4-it.log"
python $L acquire customwf-packs4 "packs4 $Tests postgres"
if ($LASTEXITCODE -ne 0) { "ABORT: heavy_lock acquire failed ($LASTEXITCODE); IT not run" | Set-Content $out; exit 1 }
try {
    Set-Location (Join-Path $PSScriptRoot "wt-packs4\sangam")
    & .\mvnw.cmd -o "-Dsangam.target=target-packs4" "-Dsangam.db=postgres" -pl sangam-server -am clean verify `
        "-Dtest=none" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dit.test=$Tests" "-Dfailsafe.failIfNoSpecifiedTests=false" *> $out
    "EXIT $LASTEXITCODE" | Add-Content $out
} finally {
    python $L release customwf-packs4
}


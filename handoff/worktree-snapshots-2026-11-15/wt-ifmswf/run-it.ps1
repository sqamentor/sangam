param([string]$Tests = "E46ConformanceIT,IfmsPacksIT", [string]$Log = "it.log")
$L = "C:\Users\Lokendra Singh\AppData\Local\Temp\claude\D--GITHUB--Project-IFMS\ff9f2caf-1246-4228-ae70-82a16cf209ae\scratchpad\heavy_lock.py"
$out = Join-Path $PSScriptRoot $Log
python $L acquire customwf-ifmswf "ifmswf postgres $Tests"
try {
    Set-Location (Join-Path $PSScriptRoot "sangam")
    & .\mvnw.cmd -o "-Dsangam.target=target-ifmswf" "-Dsangam.db=postgres" -pl sangam-server -am clean verify `
        "-Dtest=none" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dit.test=$Tests" "-Dfailsafe.failIfNoSpecifiedTests=false" `
        *> $out
    "EXIT $LASTEXITCODE" | Add-Content $out
} finally {
    python $L release customwf-ifmswf
}

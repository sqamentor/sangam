# SL-45 gated PostgreSQL red/green pair of CrossTenantIsolationIT (owner customwf-iso).
# Red: corrected test + product WITHOUT the engine fix. Green: corrected test + product WITH the fix.
$L = "C:\Users\Lokendra Singh\AppData\Local\Temp\claude\D--GITHUB--Project-IFMS\ff9f2caf-1246-4228-ae70-82a16cf209ae\scratchpad\heavy_lock.py"
$S = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\sl45"
$W = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-iso\sangam"
python $L acquire customwf-iso "SL-45 CrossTenantIsolationIT postgres red/green (engine TenantScopedCreation)"
if ($LASTEXITCODE -ne 0) { "ACQUIRE FAILED ($LASTEXITCODE): not running"; exit 2 }
function RunIt($label) {
    Push-Location $W
    .\mvnw.cmd -B "-Dsangam.target=target-iso" -Psangam-db-it "-Dsangam.db=postgres" -pl sangam-server -am verify `
        "-Dtest=NoSuchTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dit.test=CrossTenantIsolationIT" `
        "-Dfailsafe.failIfNoSpecifiedTests=false" *> "$S\it-$label.log"
    "$label mvn exit $LASTEXITCODE"
    Pop-Location
    $rep = "$W\sangam-server\target-iso\failsafe-reports"
    $dst = "$S\it-$label-reports"; New-Item -ItemType Directory -Force $dst | Out-Null
    if (Test-Path $rep) { Copy-Item "$rep\*" $dst -Force }
}
try {
    & "$S\toggle-fix.ps1" -Mode nofix
    RunIt "red"
    & "$S\toggle-fix.ps1" -Mode fix
    RunIt "green"
} finally {
    & "$S\toggle-fix.ps1" -Mode fix
    python $L release customwf-iso
}
foreach ($f in "it-red.log", "it-green.log") { "== $f"; Select-String "$S\$f" -Pattern "Tests run:.*CrossTenant|^\[ERROR\]   \w|BUILD" | % { $_.Line.Substring(0, [Math]::Min(400, $_.Line.Length)) } }

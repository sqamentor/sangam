param([string]$Tests = "IfmsPacksIT", [string]$Log = "ifmswf-pg.log")
# DETACHED runner (started with Start-Process): acquire -> run -> release in its own try/finally. The agent only polls
# the log. Owner customwf-ifmswf, JDK 25, PostgreSQL, private worktree.
$L = "C:\Users\Lokendra Singh\AppData\Local\Temp\claude\D--GITHUB--Project-IFMS\ff9f2caf-1246-4228-ae70-82a16cf209ae\scratchpad\heavy_lock.py"
$out = Join-Path $PSScriptRoot $Log
$wt = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-ifmswf\sangam"
"[run] $(Get-Date -Format o) acquiring heavy lock for: $Tests" | Set-Content $out
python $L acquire customwf-ifmswf "ifmswf postgres JDK25 $Tests (detached)" *>> $out
$acq = $LASTEXITCODE
if ($acq -ne 0) {
    "[run] LOCK NOT ACQUIRED (exit $acq); run aborted" | Add-Content $out
    "EXIT 2" | Add-Content $out
    exit 2
}
try {
    "[run] $(Get-Date -Format o) lock acquired" | Add-Content $out
    $env:JAVA_HOME = "D:\GITHUB  Project\.tools\OpenJDK25U-jdk_x64_windows_hotspot_25.0.5_8\jdk-25.0.5+8"
    Set-Location $wt
    & .\mvnw.cmd -o "-Dsangam.target=target-ifmswf25" "-Dsangam.db=postgres" -pl sangam-server -am clean verify `
        "-Dtest=none" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dit.test=$Tests" "-Dfailsafe.failIfNoSpecifiedTests=false" `
        *>> $out
    "EXIT $LASTEXITCODE" | Add-Content $out
} finally {
    python $L release customwf-ifmswf *>> $out
    "[run] $(Get-Date -Format o) lock released" | Add-Content $out
}

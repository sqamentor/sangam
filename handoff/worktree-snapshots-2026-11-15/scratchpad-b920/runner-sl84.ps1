# SL-84 detached runner (Start-Process only): acquire customwf-sl84 -> Oracle 23 acceptance AuditChainIT on wt-sl84 -> release in finally.
$L = "C:\Users\Lokendra Singh\AppData\Local\Temp\claude\D--GITHUB--Project-IFMS\ff9f2caf-1246-4228-ae70-82a16cf209ae\scratchpad\heavy_lock.py"
$S = (Get-Item "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad").FullName
$W = "$S\wt-sl84\sangam"
$R = "$S\runner-sl84.log"
$log = "$S\sl84-oracle-it.log"
function Log([string]$m) { ("{0} {1}" -f (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ"), $m) | Add-Content $R }
$env:JAVA_HOME = "D:\GITHUB  Project\.tools\OpenJDK25U-jdk_x64_windows_hotspot_25.0.5_8\jdk-25.0.5+8"
$env:PATH = "$env:JAVA_HOME\bin;" + $env:PATH
$env:JAVA_TOOL_OPTIONS = "-Duser.timezone=UTC"
Log "runner start pid=$PID"
& python $L acquire customwf-sl84 "SL-84 Oracle 23 AuditChainIT (wt-sl84, JDK 25, UTC JVM), detached runner pid $PID" *>> $R
if ($LASTEXITCODE -ne 0) { Log "ACQUIRE FAILED ($LASTEXITCODE): not running"; exit 2 }
Log "lock acquired"
try {
    $mvnArgs = "-B `"-Dsangam.target=target-sl84`" -Psangam-db-it `"-Dsangam.db=oracle`" -pl sangam-server -am verify " +
               "`"-Dtest=none`" `"-Dsurefire.failIfNoSpecifiedTests=false`" `"-Dit.test=AuditChainIT`" `"-Dfailsafe.failIfNoSpecifiedTests=false`""
    $p = Start-Process -FilePath "$W\mvnw.cmd" -ArgumentList $mvnArgs -WorkingDirectory $W -NoNewWindow -PassThru `
            -RedirectStandardOutput $log -RedirectStandardError "$log.err"
    $null = $p.Handle
    if (-not $p.WaitForExit(90 * 60 * 1000)) { Log "TIMEOUT: killing $($p.Id)"; & taskkill /T /F /PID $p.Id *>> $R; $p.WaitForExit() }
    Log "maven end exit=$($p.ExitCode)"
} finally {
    & python $L release customwf-sl84 *>> $R
    Log "lock released (exit $LASTEXITCODE)"
    $env:JAVA_TOOL_OPTIONS = $null
}
Log "runner end"

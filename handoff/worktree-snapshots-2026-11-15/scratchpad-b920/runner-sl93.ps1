# SL-93 detached runner (Start-Process only): acquire customwf-sl93 -> DialectSchemaIT on postgres, sqlserver, mysql (wt-sl93) -> release in finally.
# Oracle left out: blocked by SL-84 (context load fails on CFG_MASTER_CODE.PARENT_CODE) until that fix lands.
$L = "C:\Users\Lokendra Singh\AppData\Local\Temp\claude\D--GITHUB--Project-IFMS\ff9f2caf-1246-4228-ae70-82a16cf209ae\scratchpad\heavy_lock.py"
$S = (Get-Item "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad").FullName
$W = "$S\wt-sl93\sangam"
$R = "$S\runner-sl93.log"
function Log([string]$m) { ("{0} {1}" -f (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ"), $m) | Add-Content $R }
$env:JAVA_HOME = "D:\GITHUB  Project\.tools\OpenJDK25U-jdk_x64_windows_hotspot_25.0.5_8\jdk-25.0.5+8"
$env:PATH = "$env:JAVA_HOME\bin;" + $env:PATH
$env:JAVA_TOOL_OPTIONS = "-Duser.timezone=UTC"
Log "runner start pid=$PID"
& python $L acquire customwf-sl93 "SL-93 DialectSchemaIT on postgres, sqlserver, mysql (wt-sl93, JDK 25, UTC JVM), detached runner pid $PID" *>> $R
if ($LASTEXITCODE -ne 0) { Log "ACQUIRE FAILED ($LASTEXITCODE): not running"; exit 2 }
Log "lock acquired"
try {
    foreach ($db in @("postgres", "sqlserver", "mysql")) {
        $log = "$S\sl93-$db-it.log"
        Log "lane $db start"
        $mvnArgs = "-B `"-Dsangam.target=target-sl93`" -Psangam-db-it `"-Dsangam.db=$db`" -pl sangam-server -am verify " +
                   "`"-Dtest=none`" `"-Dsurefire.failIfNoSpecifiedTests=false`" `"-Dit.test=DialectSchemaIT`" `"-Dfailsafe.failIfNoSpecifiedTests=false`""
        $p = Start-Process -FilePath "$W\mvnw.cmd" -ArgumentList $mvnArgs -WorkingDirectory $W -NoNewWindow -PassThru `
                -RedirectStandardOutput $log -RedirectStandardError "$log.err"
        $null = $p.Handle
        if (-not $p.WaitForExit(90 * 60 * 1000)) { Log "TIMEOUT: killing $($p.Id)"; & taskkill /T /F /PID $p.Id *>> $R; $p.WaitForExit() }
        Log "lane $db maven end exit=$($p.ExitCode)"
    }
} finally {
    & python $L release customwf-sl93 *>> $R
    Log "lock released (exit $LASTEXITCODE)"
    $env:JAVA_TOOL_OPTIONS = $null
}
Log "runner end"

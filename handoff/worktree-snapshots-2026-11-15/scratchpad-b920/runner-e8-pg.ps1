# SL-53 detached runner (start with Start-Process; never from the agent's own shell): heavy lock acquire -> E8DstTimerIT on
# PostgreSQL (JVM UTC from the root pom argLine, timer zone per run) -> release in finally. A watchdog kills a Maven run that
# exceeds $TimeoutMinutes so the lock can never stay held by a hung build. verify -am compiles into $Target (own folder: clean, JDK of the run).
param([string]$Zones = "Europe/Berlin,Asia/Kolkata", [int]$TimeoutMinutes = 60, [string]$JavaHome = "C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot", [string]$Tag = "", [string]$Target = "target-dst")
$L = "C:\Users\Lokendra Singh\AppData\Local\Temp\claude\D--GITHUB--Project-IFMS\ff9f2caf-1246-4228-ae70-82a16cf209ae\scratchpad\heavy_lock.py"
# long path: with the 8.3 short form as working directory Maven cannot match -pl sangam-server to its module directory
$W = (Get-Item "C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-dst\sangam").FullName
$S = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad"
$runlog = "$S\runner-e8-pg$Tag.log"
function Log([string]$m) { ("{0} {1}" -f (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ"), $m) | Add-Content $runlog }
$zoneList = $Zones.Split(",")
Log "runner start pid=$PID zones=$Zones timeout=${TimeoutMinutes}m java=$JavaHome target=$Target"
[Environment]::SetEnvironmentVariable("JAVA_TOOL_OPTIONS", $null)
# the production runtime (server image: JRE 21); the JVM zone comes from the root pom argLine (-Duser.timezone=UTC)
$env:JAVA_HOME = $JavaHome
$env:PATH = "$env:JAVA_HOME\bin;" + $env:PATH
& python $L acquire customwf-dst "SL-53 E8DstTimerIT PostgreSQL (JVM UTC, timer zones $Zones), detached runner pid $PID" *>> $runlog
if ($LASTEXITCODE -ne 0) { Log "ACQUIRE FAILED ($LASTEXITCODE): not running"; exit 2 }
Log "lock acquired"
try {
    foreach ($z in $zoneList) {
        $log = "$S\pg-e8-utcjvm$Tag-" + ($z -replace '/', '-') + ".log"
        $mvnArgs = "-o `"-Dsangam.target=$Target`" -Psangam-db-it `"-Dsangam.db=postgres`" -pl sangam-server -am verify " +
                "`"-Dtest=none`" `"-Dsurefire.failIfNoSpecifiedTests=false`" `"-Dit.test=E8DstTimerIT`" " +
                "`"-Dfailsafe.failIfNoSpecifiedTests=false`" `"-Dsangam.engine.timer-zone=$z`""
        Log "maven start zone=$z log=$log"
        $p = Start-Process -FilePath "$W\mvnw.cmd" -ArgumentList $mvnArgs -WorkingDirectory $W -NoNewWindow -PassThru `
                -RedirectStandardOutput $log -RedirectStandardError "$log.err"
        $null = $p.Handle # keep the handle so ExitCode is available after exit
        if (-not $p.WaitForExit($TimeoutMinutes * 60 * 1000)) {
            Log "TIMEOUT zone=${z}: killing process tree $($p.Id)"
            & taskkill /T /F /PID $p.Id *>> $runlog
            $p.WaitForExit()
        }
        Log "maven end zone=$z exit=$($p.ExitCode)"
        "EXIT $($p.ExitCode)" | Add-Content $log
    }
} finally {
    & python $L release customwf-dst *>> $runlog
    Log "lock released (exit $LASTEXITCODE)"
}
Log "runner end"

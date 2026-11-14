# Sangam DB-matrix detached runner (start with Start-Process; never from an agent's own shell).
# 1. snapshot the integration tree's sangam/ into wt-matrix (build outputs excluded), so later applies cannot change the run
# 2. heavy lock acquire customwf-matrix (abort unless exit 0)
# 3. full acceptance suite (failsafe profile sangam-db-it) per database, JDK 25, JVM in UTC, watchdog per database
# 4. release in finally
param([string]$Dbs = "oracle,sqlserver2025,mysql,sqlserver", [int]$TimeoutMinutes = 180,
      [string]$JavaHome = "D:\GITHUB  Project\.tools\OpenJDK25U-jdk_x64_windows_hotspot_25.0.5_8\jdk-25.0.5+8")
$L  = "C:\Users\Lokendra Singh\AppData\Local\Temp\claude\D--GITHUB--Project-IFMS\ff9f2caf-1246-4228-ae70-82a16cf209ae\scratchpad\heavy_lock.py"
$IT = "D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524"
$S  = (Get-Item "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad").FullName
$W  = "$S\wt-matrix\sangam"
$runlog = "$S\runner-matrix.log"
function Log([string]$m) { ("{0} {1}" -f (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ"), $m) | Add-Content $runlog }
Log "runner start pid=$PID dbs=$Dbs timeout=${TimeoutMinutes}m java=$JavaHome"
# snapshot: robocopy mirror, excluding only build outputs (target-*, module-root target, node_modules, dist)
New-Item -ItemType Directory -Force "$S\wt-matrix" | Out-Null
& robocopy "$IT\sangam" $W /MIR /NFL /NDL /NJH /NP /XD node_modules dist "target-*" /R:1 /W:1 *>> $runlog
# module-root target folders only (never sangam-programme/.../programme/target, which is source)
Get-ChildItem $W -Directory -Recurse -Filter target | ? { Test-Path (Join-Path $_.Parent.FullName "pom.xml") } | Remove-Item -Recurse -Force
$diffHash = (git -C $IT diff | Out-String | ForEach-Object { [BitConverter]::ToString((New-Object Security.Cryptography.SHA256Managed).ComputeHash([Text.Encoding]::UTF8.GetBytes($_))).Replace('-','').Substring(0,16) })
Log "snapshot taken from $IT (HEAD $(git -C $IT rev-parse --short HEAD), uncommitted diff sha256 $diffHash)"
$env:JAVA_HOME = $JavaHome
$env:PATH = "$env:JAVA_HOME\bin;" + $env:PATH
$env:JAVA_TOOL_OPTIONS = "-Duser.timezone=UTC"
& python $L acquire customwf-matrix "Sangam DB matrix: full acceptance on $Dbs (JDK 25, UTC JVM, snapshot $diffHash), detached runner pid $PID" *>> $runlog
if ($LASTEXITCODE -ne 0) { Log "ACQUIRE FAILED ($LASTEXITCODE): not running"; exit 2 }
Log "lock acquired"
try {
    foreach ($db in $Dbs.Split(",")) {
        $log = "$S\matrix-$db.log"
        $mvnArgs = "-B `"-Dsangam.target=target-matrix`" -Psangam-db-it `"-Dsangam.db=$db`" -pl sangam-server -am verify " +
                   "`"-Dtest=none`" `"-Dsurefire.failIfNoSpecifiedTests=false`" -fae"
        Log "maven start db=$db log=$log"
        $p = Start-Process -FilePath "$W\mvnw.cmd" -ArgumentList $mvnArgs -WorkingDirectory $W -NoNewWindow -PassThru `
                -RedirectStandardOutput $log -RedirectStandardError "$log.err"
        $null = $p.Handle
        if (-not $p.WaitForExit($TimeoutMinutes * 60 * 1000)) {
            Log "TIMEOUT db=${db}: killing process tree $($p.Id)"
            & taskkill /T /F /PID $p.Id *>> $runlog
            $p.WaitForExit()
        }
        Log "maven end db=$db exit=$($p.ExitCode)"
        "EXIT $($p.ExitCode)" | Add-Content $log
    }
} finally {
    & python $L release customwf-matrix *>> $runlog
    Log "lock released (exit $LASTEXITCODE)"
    $env:JAVA_TOOL_OPTIONS = $null
}
Log "runner end"

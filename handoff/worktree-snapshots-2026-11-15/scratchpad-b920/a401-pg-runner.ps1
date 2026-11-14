# APPROVE-401 / apply-order step 2: DETACHED gated PostgreSQL runner (integrator rule 2026-11-09 18:30 UTC).
# Started with Start-Process; never holds the heavy lock from an agent shell. acquire (abort unless exit 0) ->
# Maven -> release in this script's own finally. Progress in <Tag>.runner.log, Maven output in <Tag>.log.
param([string]$Tests = "PlugAndPlayIT,E17bDomainIT,T35RestoreIT,IfmsPacksIT", [string]$Tag = "a401-it1", [string]$Jdk = "",
      [string]$Worktree = "C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-auth401\sangam",
      [switch]$NoClean)
if ($Jdk -ne "") {
    $env:JAVA_HOME = $Jdk
    $env:Path = "$Jdk\bin;" + $env:Path
}
$L = "C:\Users\Lokendra Singh\AppData\Local\Temp\claude\D--GITHUB--Project-IFMS\ff9f2caf-1246-4228-ae70-82a16cf209ae\scratchpad\heavy_lock.py"
$S = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad"
$W = $Worktree
$R = "$S\$Tag.runner.log"
function Log([string]$m) {
    ((Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ") + " " + $m) | Add-Content -Encoding utf8 $R
}
Log "runner start pid=$PID tests=$Tests JAVA_HOME=$env:JAVA_HOME worktree=$W"
Set-Location $W
& python $L acquire customwf-auth401 "APPROVE-401 step 2: PG $Tests (detached runner pid $PID)" *>> $R
$rc = $LASTEXITCODE
if ($rc -ne 0) {
    Log "ACQUIRE FAILED rc=${rc}; nothing run"
    Log "RUNNER DONE"
    exit 2
}
try {
    # -NoClean: the same sources as the previous run in this worktree (unchanged since), so the incremental build is
    # valid and the lock is not held for a full recompile
    # always an array (a one-element 'if' result would unroll to a string, and @string splats its characters)
    [string[]]$goals = @(if ($NoClean) { "verify" } else { "clean"; "verify" })
    Log "lock acquired; maven start ($($goals -join ' '))"
    & .\mvnw.cmd "-Dsangam.target=target-auth401" -Psangam-db-it "-Dsangam.db=postgres" -pl sangam-server -am @goals `
        "-Dtest=NoSuchTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dit.test=$Tests" "-Dfailsafe.failIfNoSpecifiedTests=false" *> "$S\$Tag.log"
    Log "maven exit $LASTEXITCODE"
} finally {
    & python $L release customwf-auth401 *>> $R
    Log "release rc=$LASTEXITCODE"
    Log "RUNNER DONE"
}

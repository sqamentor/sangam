# Sequential surefire iterations (H2), one Maven run at a time.
# usage: run-green.ps1 -Root <sangam dir> -Logs <dir> [-From 1] [-To 3] [-Prefix green] [-Pkgs a,b] [-Modules m1,m2]
#                      [-IsolatedForks] [-CleanFirst]
param(
    [Parameter(Mandatory = $true)][string]$Root,
    [Parameter(Mandatory = $true)][string]$Logs,
    [int]$From = 1,
    [int]$To = 3,
    [string]$Prefix = 'green',
    [string[]]$Pkgs = @('security', 'ports', 'ops', 'ai', 'rest', 'server'),
    [string[]]$Modules = @('sangam-security', 'sangam-flowable-ports', 'sangam-ops', 'sangam-ai', 'sangam-rest', 'sangam-server'),
    [switch]$IsolatedForks,
    [switch]$CleanFirst,
    [int]$ForkTimeout = 0
)
# surefire's default includes are **/Test*.java, **/*Test.java, **/*Tests.java (and *TestCase); keep all of them
$pats = foreach ($p in $Pkgs) { "org.sangam.$p.**.*Test"; "org.sangam.$p.**.*Tests"; "org.sangam.$p.**.Test*" }
$test = '-Dtest=' + ($pats -join ',')
New-Item -ItemType Directory -Force $Logs | Out-Null
Set-Location $Root
foreach ($i in $From..$To) {
    $log = Join-Path $Logs "$Prefix-$i.log"
    $goals = @(if ($CleanFirst -and $i -eq $From) { 'clean'; 'test' } else { 'test' })
    # surefire user properties: reuseForks (not surefire.reuseForks), surefire.timeout (forkedProcessTimeoutInSeconds)
    $extra = @(if ($IsolatedForks) { '-DreuseForks=false' }) + @(if ($ForkTimeout -gt 0) { "-Dsurefire.timeout=$ForkTimeout" })
    $started = Get-Date
    & .\mvnw.cmd -v 2>&1 | Out-File $log -Encoding utf8
    & .\mvnw.cmd -o -pl ($Modules -join ',') -am '-Dsangam.target=target-uip2' $test '-Dsurefire.failIfNoSpecifiedTests=false' `
        '-Dmaven.test.failure.ignore=true' @extra @goals 2>&1 | Out-File $log -Encoding utf8 -Append
    $code = $LASTEXITCODE
    $mins = [math]::Round(((Get-Date) - $started).TotalMinutes, 1)
    "iteration $i exit $code ($mins min) -> $log"
    Select-String -Path $log -Pattern '^\[(INFO|WARNING|ERROR)\] Tests run: \d+, Failures: \d+, Errors: \d+, Skipped: \d+$|<<< (FAILURE|ERROR)!? -- in|BUILD (SUCCESS|FAILURE)|There are test failures' |
        ForEach-Object { '  ' + $_.Line }
}

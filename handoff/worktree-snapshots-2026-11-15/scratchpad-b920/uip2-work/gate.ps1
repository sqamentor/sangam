# Gate on one JDK: compile gate (-pl sangam-server -am -DskipTests clean <compile|test-compile>), then N isolated-fork
# surefire iterations of the touched suites (security, ports, ops, ai, rest, server). One Maven run at a time.
# Surefire on H2 only (no failsafe), so no heavy lock is needed. Exits 1 when the compile gate fails.
param(
    [Parameter(Mandatory = $true)][string]$Root,
    [Parameter(Mandatory = $true)][string]$Logs,
    [Parameter(Mandatory = $true)][string]$Prefix,
    [string]$JavaHome = '',
    [ValidateSet('compile', 'test-compile')][string]$CompileGoal = 'compile',
    [int]$Iterations = 1
)
if ($JavaHome) {
    $env:JAVA_HOME = $JavaHome
    $env:Path = (Join-Path $JavaHome 'bin') + ';' + $env:Path
}
New-Item -ItemType Directory -Force $Logs | Out-Null
Set-Location $Root
$log = Join-Path $Logs "$Prefix-$CompileGoal.log"
$t = Get-Date
& .\mvnw.cmd -v 2>&1 | Out-File $log -Encoding utf8
& .\mvnw.cmd -o -pl sangam-server -am -DskipTests '-Dsangam.target=target-uip2' clean $CompileGoal 2>&1 |
    Out-File $log -Encoding utf8 -Append
$code = $LASTEXITCODE
$jdk = (Select-String -Path $log -Pattern '^Java version: (\S+)' | Select-Object -First 1).Matches.Groups[1].Value
"[$Prefix] $CompileGoal gate on JDK $jdk exit $code ($([math]::Round(((Get-Date) - $t).TotalMinutes, 1)) min) -> $log"
Select-String -Path $log -Pattern 'BUILD (SUCCESS|FAILURE)|\[ERROR\] .*\.java' | Select-Object -First 10 | ForEach-Object { '  ' + $_.Line }
if ($code -ne 0) { exit 1 }
if ($Iterations -gt 0) {
    & (Join-Path $PSScriptRoot 'run-green.ps1') -Root $Root -Logs $Logs -Prefix "$Prefix-isolated" -From 1 -To $Iterations `
        -IsolatedForks -ForkTimeout 3600
}

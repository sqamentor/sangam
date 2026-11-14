# SL-67 (wt-d10) runner: the CI "Maven build + unit tests" command, limited to the sangam-server suite (-am builds
# every upstream module; their tests are not selected). One surefire JVM, as the pom configures it (forkCount 1,
# reuseForks true). CI zone emulated with -Duser.timezone=UTC.
param(
    [Parameter(Mandatory = $true)][ValidateSet('21', '25', '27')][string]$Jdk,
    [Parameter(Mandatory = $true)][string]$Label,
    [string]$RunOrder = '',
    [string]$Seed = '',
    [string]$Goals = 'clean verify',
    [string]$Test = 'org/sangam/server/**/*Test,org/sangam/server/**/*Tests',
    [string]$Extra = '',
    [string]$Pl = 'sangam-server'
)
$ErrorActionPreference = 'Stop'
$scratch = 'C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad'
$wt = Join-Path $scratch 'wt-d10\sangam'
$logs = Join-Path $scratch 'd10-logs'
New-Item -ItemType Directory -Force $logs | Out-Null
$log = Join-Path $logs ($Label + '.log')
switch ($Jdk) {
    '21' { $env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot' }
    '25' { $env:JAVA_HOME = 'D:\GITHUB  Project\.tools\OpenJDK25U-jdk_x64_windows_hotspot_25.0.5_8\jdk-25.0.5+8' }
    '27' { $env:JAVA_HOME = 'C:\Users\Lokendra Singh\tools\jdk27\jdk-27+35' }
}
$env:Path = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:Path
$env:JAVA_TOOL_OPTIONS = '-Duser.timezone=UTC'
$env:TZ = 'UTC'
$args2 = @('-B', '-ntp', '-Dsangam.db=h2', '-DskipITs', '-Dsangam.it.skip=true', '-Dsangam.target=target-d10',
    '-pl', $Pl, '-am')
$args2 += $Goals.Split(' ')
if ($Test -ne '') { $args2 += @("-Dtest=$Test", '-Dsurefire.failIfNoSpecifiedTests=false') }
if ($RunOrder -ne '') { $args2 += "-Dsurefire.runOrder=$RunOrder" }
if ($Seed -ne '') { $args2 += "-Dsurefire.runOrder.random.seed=$Seed" }
if ($Extra -ne '') { $args2 += $Extra.Split(' ') }
Set-Location $wt
"[$((Get-Date).ToUniversalTime().ToString('u'))] JAVA_HOME=$env:JAVA_HOME JAVA_TOOL_OPTIONS=$env:JAVA_TOOL_OPTIONS" | Out-File -Encoding utf8 $log
"mvnw.cmd $($args2 -join ' ')" | Out-File -Encoding utf8 -Append $log
& .\mvnw.cmd @args2 *>&1 | Out-File -Encoding utf8 -Append $log
$code = $LASTEXITCODE
"[$((Get-Date).ToUniversalTime().ToString('u'))] EXIT $code" | Out-File -Encoding utf8 -Append $log
# keep this run's surefire reports (the next run cleans the target folder)
foreach ($m in @('sangam-server', 'sangam-integration')) {
    $rep = Join-Path $wt ($m + '\target-d10\surefire-reports')
    if (Test-Path $rep) { Copy-Item -Recurse -Force $rep (Join-Path $logs ($Label + '-' + $m + '-surefire-reports')) }
}
exit $code

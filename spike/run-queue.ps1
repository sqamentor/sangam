<#
.SYNOPSIS
  Runs the queued heavy spike work (Q1..Q7) in order, each step inside the shared heavy-build lock.

.DESCRIPTION
  powershell -File spike\run-queue.ps1                 # everything, in order
  powershell -File spike\run-queue.ps1 -Only Q2,Q4     # selected steps (still in queue order)

  - Every step: python heavy_lock.py acquire customwf "<step>" ... release customwf (in finally). The script waits for
    the lock and never breaks it. If acquire fails, the step is skipped and recorded.
  - Log: spike\results\run-queue.log (timestamps, exit codes). Maven output per step: spike\results\run-queue\<step>.out.log
    and .err.log. A failing step is recorded and the queue continues.
  - One container at a time: after every step the script waits until the Testcontainers containers started during
    that step are gone (JVM shutdown hooks / Ryuk) and removes leftovers of that step only.
  - JDK 25 (Q5) is set through JAVA_HOME for the child Maven processes of that step only; it is restored afterwards and
    never written system-wide.
  See spike\RUN-QUEUE.md.
#>
param(
    [string[]]$Only = @()
)

$ErrorActionPreference = 'Continue'
$Spike = Split-Path -Parent $MyInvocation.MyCommand.Path
$Results = Join-Path $Spike 'results'
$StepLogs = Join-Path $Results 'run-queue'
$Log = Join-Path $Results 'run-queue.log'
$Mvnw = Join-Path $Spike 'mvnw.cmd'
$LockScript = $(if ($env:SPIKE_HEAVY_LOCK) { $env:SPIKE_HEAVY_LOCK } else { Join-Path $env:TEMP 'heavy_lock.py' })
$Owner = 'customwf'
$Jdk25 = $(if ($env:SPIKE_JDK25) { $env:SPIKE_JDK25 } else { Join-Path $env:USERPROFILE 'tools\jdk25\jdk-25.0.5.0.2+1' })
$AllDbs = @('postgres', 'oracle', 'sqlserver', 'mysql')
$NewDbs = @('oracle', 'sqlserver', 'mysql')          # PostgreSQL ran in Stage B
$Summary = New-Object System.Collections.Generic.List[object]

New-Item -ItemType Directory -Force -Path $StepLogs | Out-Null

function Write-Log([string]$Message) {
    $line = '{0:yyyy-MM-dd HH:mm:ss} {1}' -f (Get-Date), $Message
    Add-Content -Path $Log -Value $line -Encoding UTF8
    Write-Host $line
}

function Test-Selected([string]$Id) {
    if ($Only.Count -eq 0) { return $true }
    $major = $Id.Split('.')[0]
    return ($Only -contains $major) -or ($Only -contains $Id)
}

function Quote([string]$s) {
    if ($s -match '[\s"]') { return '"' + $s.Replace('"', '\"') + '"' }
    return $s
}

# Runs one Maven invocation; output to <name>.out.log / <name>.err.log. Returns the exit code.
function Invoke-Mvn([string]$Name, [string[]]$MvnArgs, [hashtable]$EnvVars = @{}) {
    $out = Join-Path $StepLogs ($Name + '.out.log')
    $err = Join-Path $StepLogs ($Name + '.err.log')
    $argLine = (@('-B') + $MvnArgs | ForEach-Object { Quote $_ }) -join ' '
    Write-Log ("  mvnw {0}   (logs: results\run-queue\{1}.*.log)" -f $argLine, $Name)
    $saved = @{}
    foreach ($k in $EnvVars.Keys) {
        $saved[$k] = [Environment]::GetEnvironmentVariable($k, 'Process')
        [Environment]::SetEnvironmentVariable($k, $EnvVars[$k], 'Process')
    }
    try {
        $p = Start-Process -FilePath $Mvnw -ArgumentList $argLine -WorkingDirectory $Spike -NoNewWindow -Wait -PassThru `
            -RedirectStandardOutput $out -RedirectStandardError $err
        $code = $p.ExitCode
    } catch {
        Write-Log ("  could not start Maven: {0}" -f $_.Exception.Message)
        $code = -1
    } finally {
        foreach ($k in $saved.Keys) { [Environment]::SetEnvironmentVariable($k, $saved[$k], 'Process') }
    }
    Write-Log ("  exit {0}: {1}" -f $code, $Name)
    return $code
}

function Get-TcContainers {
    $ids = & docker ps -aq --filter 'label=org.testcontainers=true' 2>$null
    if ($LASTEXITCODE -ne 0 -or $null -eq $ids) { return @() }
    return @($ids | Where-Object { $_ })
}

# Waits until the Testcontainers containers that appeared during the step are gone; removes leftovers of the step only.
function Wait-StepContainers([string[]]$Before) {
    $deadline = (Get-Date).AddSeconds(120)
    do {
        $new = @(Get-TcContainers | Where-Object { $Before -notcontains $_ })
        if ($new.Count -eq 0) { return }
        Start-Sleep -Seconds 5
    } while ((Get-Date) -lt $deadline)
    Write-Log ("  removing {0} leftover container(s) of this step: {1}" -f $new.Count, ($new -join ','))
    foreach ($id in $new) { & docker rm -f $id 2>&1 | Out-Null }
}

# One queue step inside the shared lock. $Body returns an exit code (or a string status); $ArgList is passed to it
# (no closures: a GetNewClosure block could not see this script's functions).
function Invoke-Step([string]$Id, [string]$What, [scriptblock]$Body, [object[]]$ArgList = @()) {
    if (-not (Test-Selected $Id)) { return }
    Write-Log ("=== {0} waiting for lock: {1}" -f $Id, $What)
    $acq = & python $LockScript acquire $Owner ("{0} {1}" -f $Id, $What) 2>&1
    $acqCode = $LASTEXITCODE
    Write-Log ("  lock: {0}" -f (($acq | Out-String).Trim()))
    if ($acqCode -ne 0) {
        Write-Log ("=== {0} SKIPPED: lock acquire failed (exit {1})" -f $Id, $acqCode)
        $Summary.Add([pscustomobject]@{ Step = $Id; What = $What; Result = "lock-failed($acqCode)" })
        return
    }
    $before = Get-TcContainers
    $t0 = Get-Date
    $result = $null
    try {
        Write-Log ("=== {0} START: {1}" -f $Id, $What)
        $result = & $Body @ArgList
        if ($result -is [array]) { $result = $result[-1] }
    } catch {
        Write-Log ("  exception: {0}" -f $_.Exception.Message)
        $result = 'exception'
    } finally {
        Wait-StepContainers $before
        $rel = & python $LockScript release $Owner 2>&1
        Write-Log ("  lock: {0}" -f (($rel | Out-String).Trim()))
    }
    $min = [math]::Round(((Get-Date) - $t0).TotalMinutes, 1)
    Write-Log ("=== {0} END: result={1} ({2} min)" -f $Id, $result, $min)
    $Summary.Add([pscustomobject]@{ Step = $Id; What = $What; Result = "$result"; Minutes = $min })
}

function Skip-Step([string]$Id, [string]$What, [string]$Reason) {
    if (-not (Test-Selected $Id)) { return }
    Write-Log ("=== {0} SKIPPED: {1}: {2}" -f $Id, $What, $Reason)
    $Summary.Add([pscustomobject]@{ Step = $Id; What = $What; Result = "skipped: $Reason" })
}

# Q1 skip rule: the adapter's own agent may have verified it already. A database counts as done when one line of the
# patch ledger names it together with "verify"/"verified" and "pass"/"passed"/"green".
function Test-LedgerVerified([string]$Ledger, [string[]]$Dbs) {
    if (-not (Test-Path $Ledger)) { return $false }
    $lines = Get-Content -Path $Ledger -Encoding UTF8
    foreach ($db in $Dbs) {
        $pat = switch ($db) {
            'postgres' { 'postgres' }
            'oracle' { 'oracle' }
            'sqlserver' { 'sql ?server|mssql' }
            'mysql' { 'mysql' }
        }
        $hit = $lines | Where-Object { $_ -match "(?i)($pat)" -and $_ -match '(?i)\bverif(y|ied)\b' -and $_ -match '(?i)\b(pass|passes|passed|green)\b' }
        if (-not $hit) { return $false }
    }
    return $true
}

# Average total CPU over $Seconds (Get-Counter, 5 s samples); falls back to Win32_Processor.LoadPercentage.
function Get-AverageCpu([int]$Seconds = 120) {
    $samples = [math]::Max(1, [int]($Seconds / 5))
    try {
        $c = Get-Counter -Counter '\Processor(_Total)\% Processor Time' -SampleInterval 5 -MaxSamples $samples -ErrorAction Stop
        return [math]::Round((($c.CounterSamples | Measure-Object -Property CookedValue -Average).Average), 1)
    } catch {
        $vals = for ($i = 0; $i -lt $samples; $i++) {
            (Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average).Average
            Start-Sleep -Seconds 5
        }
        return [math]::Round((($vals | Measure-Object -Average).Average), 1)
    }
}

# Polls until the 2-minute average CPU is below $Threshold; gives up at $Deadline. Returns the last average or $null.
function Wait-QuietCpu([datetime]$Deadline, [double]$Threshold = 25) {
    while ((Get-Date) -lt $Deadline) {
        $avg = Get-AverageCpu 120
        Write-Log ("  CPU 2-min average: {0} %" -f $avg)
        if ($avg -lt $Threshold) { return $avg }
    }
    return $null
}

Write-Log ('##### run-queue start (JDK default: {0}; only: {1})' -f $env:JAVA_HOME, ($Only -join ','))

# ---------------------------------------------------------------- Q1 adapters on every Tier-1 database
# adapter-operaton first, then adapter-flowable (its parity fixes land in a separate session).
$adapters = @(
    @{ Module = 'adapter-operaton'; Ledger = 'patch-ledger-operaton.md'; Prop = 'operaton.dbs' },
    @{ Module = 'adapter-flowable'; Ledger = 'patch-ledger-flowable.md'; Prop = 'flowable.dbs' }
)
foreach ($a in $adapters) {
    $id = 'Q1.' + $a.Module
    $ledger = Join-Path $Results $a.Ledger
    if (Test-LedgerVerified $ledger $AllDbs) {
        Skip-Step $id ("{0} verify on {1}" -f $a.Module, ($AllDbs -join ',')) ("already verified per results\{0}" -f $a.Ledger)
        continue
    }
    Invoke-Step $id ("{0} verify on {1}" -f $a.Module, ($AllDbs -join ',')) {
        param($mod, $prop)
        Invoke-Mvn ('q1-' + $mod) @('-pl', $mod, 'verify', ('-D{0}={1}' -f $prop, ($AllDbs -join ',')),
            ('-Dspike.dbs={0}' -f ($AllDbs -join ',')), '-DSPIKE_DB_ALL=true')
    } @($a.Module, $a.Prop)
}

# ---------------------------------------------------------------- Q2 E1 on Oracle, SQL Server, MySQL (both engines)
foreach ($db in $NewDbs) {
    Invoke-Step ('Q2.' + $db) ("E1 on {0}, Operaton + Flowable" -f $db) {
        param($d)
        Invoke-Mvn ('q2-e1-' + $d) @('-pl', 'experiments', 'verify', "-Dspike.db=$d", '-Dspike.engines=operaton,flowable',
            '-Dtest=E1CorpusCoverageTest', '-Dsurefire.failIfNoSpecifiedTests=false',
            '-Dskip.flowable.e8=true', '-Dskip.operaton.e8=true')
    } @($db)
}
Invoke-Step 'Q2.mysql-mariadb' 'E1 on MySQL with the MariaDB Connector/J (LIC-4), Operaton + Flowable' {
    Invoke-Mvn 'q2-e1-mysql-mariadb' @('-pl', 'experiments', 'verify', '-Dspike.db=mysql', '-Dspike.mysql.driver=mariadb',
        '-Dspike.engines=operaton,flowable', '-Dtest=E1CorpusCoverageTest', '-Dsurefire.failIfNoSpecifiedTests=false',
        '-Dskip.flowable.e8=true', '-Dskip.operaton.e8=true')
}

# ---------------------------------------------------------------- Q3 E2-E5, E7 on Oracle, SQL Server, MySQL (both engines)
foreach ($db in $NewDbs) {
    Invoke-Step ('Q3.' + $db) ("E2-E5, E7 on {0}, Operaton + Flowable" -f $db) {
        param($d)
        Invoke-Mvn ('q3-e2e7-' + $d) @('-pl', 'experiments-integration', 'verify', "-Dspike.db=$d",
            '-Dspike.engines=operaton,flowable', '-Dtest=E2*Test,E3*Test,E4*Test,E5*Test,E7*Test',
            '-Dsurefire.failIfNoSpecifiedTests=false')
    } @($db)
}

# ---------------------------------------------------------------- Q4 T08 + T35 on PostgreSQL (both engines)
Invoke-Step 'Q4' 'T08 poison outbox + T35 restore on PostgreSQL, Operaton + Flowable' {
    Invoke-Mvn 'q4-t08-t35' @('-pl', 'experiments-integration', 'verify', '-Dspike.db=postgres',
        '-Dspike.engines=operaton,flowable', '-Dtest=T08*Test,T35*Test', '-Dsurefire.failIfNoSpecifiedTests=false')
}

# ---------------------------------------------------------------- Q5 JDK 25 pass (JAVA_HOME for these processes only)
if (Test-Selected 'Q5') {
    if (-not (Test-Path (Join-Path $Jdk25 'bin\java.exe'))) {
        Skip-Step 'Q5' 'JDK 25 pass' ("JDK 25 not found at {0}" -f $Jdk25)
    } else {
        Invoke-Step 'Q5' 'JDK 25: install + verify per module (PostgreSQL)' {
            $envJdk = @{ JAVA_HOME = $Jdk25; PATH = (Join-Path $Jdk25 'bin') + ';' + $env:PATH }
            $modules = @(
                @{ M = 'product-api'; A = @() },
                @{ M = 'corpus'; A = @() },
                @{ M = 'product-layer'; A = @() },
                @{ M = 'simulators'; A = @() },
                @{ M = 'adapter-operaton'; A = @('-Doperaton.dbs=postgres') },
                @{ M = 'adapter-flowable'; A = @('-Dflowable.dbs=postgres', '-Dspike.dbs=postgres') },
                @{ M = 'experiments'; A = @('-Dspike.db=postgres', '-Dspike.engines=operaton,flowable') }
            )
            $codes = @()
            foreach ($m in $modules) {
                # install runs the whole lifecycle up to verify (tests included), then installs
                $c = Invoke-Mvn ('q5-jdk25-' + $m.M) (@('-pl', $m.M, 'install') + $m.A) $envJdk
                $status = 'FAIL'
                if ($c -eq 0) { $status = 'PASS' }
                Write-Log ("  JDK 25 {0}: {1} (exit {2})" -f $m.M, $status, $c)
                $codes += ("{0}={1}" -f $m.M, $status)
            }
            Write-Log ("  JDK 25 java -version: {0}" -f ((& (Join-Path $Jdk25 'bin\java.exe') -version 2>&1 | Select-Object -First 1) | Out-String).Trim())
            ($codes -join ' ')
        }
    }
}

# ---------------------------------------------------------------- Q6 E9 benchmark, both engines, on a quiet machine
if (Test-Selected 'Q6') {
    $deadline = (Get-Date).AddHours(3)
    $ran = $false
    while (-not $ran -and (Get-Date) -lt $deadline) {
        Write-Log '=== Q6 waiting for a quiet machine (2-min average CPU < 25 %, up to 3 h)'
        $quiet = Wait-QuietCpu $deadline
        if ($null -eq $quiet) { break }
        Invoke-Step 'Q6' 'E9 benchmark on PostgreSQL, Flowable then Operaton, back to back' {
            # re-check under the lock: someone may have started work between the check and the acquire
            $cpu0 = Get-AverageCpu 120
            Write-Log ("  CPU under the lock: {0} %" -f $cpu0)
            if ($cpu0 -ge 25) { return 'busy-after-lock' }
            $e9 = @('-De9.skip=false', '-De9.warmupSeconds=20', '-De9.steadySeconds=60', '-De9.levels=full,audit', '-De9.workflows=r1,r5')
            $out = @()
            foreach ($engine in @('flowable', 'operaton')) {
                $start = Get-AverageCpu 120
                if ($start -ge 25) {
                    Write-Log ("  {0}: CPU {1} % before start, waiting up to 30 min for < 25 %" -f $engine, $start)
                    $again = Wait-QuietCpu ((Get-Date).AddMinutes(30))
                    if ($null -eq $again) { $out += "$engine=not-quiet"; continue }
                    $start = $again
                }
                Write-Log ("  E9 {0}: CPU at start {1} %" -f $engine, $start)
                $c = Invoke-Mvn ('q6-e9-' + $engine) (@('-pl', 'experiments-load', 'test-compile', ('surefire:test@e9-' + $engine)) + $e9)
                $end = Get-AverageCpu 120
                Write-Log ("  E9 {0}: exit {1}; CPU at start {2} %, after end {3} %" -f $engine, $c, $start, $end)
                $out += ("{0}=exit{1},cpuStart={2},cpuEnd={3}" -f $engine, $c, $start, $end)
            }
            ($out -join ' ')
        }
        $last = $Summary | Where-Object { $_.Step -eq 'Q6' } | Select-Object -Last 1
        if ($null -ne $last -and $last.Result -ne 'busy-after-lock') { $ran = $true }
    }
    if (-not $ran) { Skip-Step 'Q6' 'E9 benchmark' 'machine not quiet (2-min average CPU >= 25 %) within 3 h' }
}

# ---------------------------------------------------------------- Q7 T28 upgrade with instances in flight (both engines)
$t28 = @(
    @{ Engine = 'operaton'; Old = '2.1.6'; New = '2.2.0' },
    @{ Engine = 'flowable'; Old = '7.2.0'; New = '8.0.0' }
)
foreach ($u in $t28) {
    Invoke-Step ('Q7.' + $u.Engine) ("T28 upgrade {0} {1} -> {2} with WF-1 in flight" -f $u.Engine, $u.Old, $u.New) {
        param($e, $old)
        $name = 'customwf-t28-pg'
        $port = 55432
        $state = Join-Path $StepLogs ("t28-{0}-state.properties" -f $e)
        if (Test-Path $state) { Remove-Item $state -Force }
        & docker rm -f $name 2>&1 | Out-Null
        $run = & docker run -d --name $name -e POSTGRES_USER=t28 -e POSTGRES_PASSWORD=t28 -e POSTGRES_DB=t28 `
            -p ("{0}:5432" -f $port) postgres:17-alpine 2>&1
        Write-Log ("  docker run {0}: {1}" -f $name, (($run | Out-String).Trim()))
        try {
            $ready = $false
            for ($i = 0; $i -lt 60 -and -not $ready; $i++) {
                Start-Sleep -Seconds 2
                & docker exec $name pg_isready -h 127.0.0.1 -U t28 -d t28 2>&1 | Out-Null
                if ($LASTEXITCODE -eq 0) { $ready = $true }
            }
            if (-not $ready) { return 'postgres-not-ready' }
            Start-Sleep -Seconds 2
            $jdbc = "jdbc:postgresql://localhost:$port/t28"
            $prop = ('-Dt28.{0}.version={1}' -f $e, $old)
            $c1 = Invoke-Mvn ('q7-t28-' + $e + '-old') @('-f', (Join-Path $Spike 'experiments-integration\t28-old\pom.xml'),
                'verify', "-Dt28.engine=$e", $prop, "-Dt28.jdbcUrl=$jdbc", '-Dt28.user=t28', '-Dt28.password=t28',
                "-Dt28.stateFile=$state")
            if (-not (Test-Path $state)) { return ("old-phase-failed(exit{0})" -f $c1) }
            $c2 = Invoke-Mvn ('q7-t28-' + $e + '-new') @('-pl', 'experiments-integration', 'verify', '-Dtest=T28UpgradeTest',
                '-Dsurefire.failIfNoSpecifiedTests=false', "-Dt28.stateFile=$state")
            ("old=exit{0} new=exit{1} (see results\T28.json)" -f $c1, $c2)
        } finally {
            & docker rm -f $name 2>&1 | Out-Null
            Write-Log ("  removed {0}" -f $name)
        }
    } @($u.Engine, $u.Old)
}

Write-Log '##### run-queue summary'
foreach ($s in $Summary) { Write-Log ("  {0,-22} {1}  [{2}]" -f $s.Step, $s.Result, $s.What) }
Write-Log '##### run-queue end'

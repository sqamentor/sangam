# Test copy of the PROPOSED Sangam runner recipe (v2 CLI), pointed at a TEMP lock dir. The live recipe differs only in $L
# and in not setting SHARED_LOCK_DIR / SHARED_LOCK_POLL_SECONDS.
param([string]$Lock, [string]$Dir, [string]$Log, [string]$Owner = "customwf-test", [int]$JobSeconds = 20,
      [int]$BeatSeconds = 600, [string]$Lease = "", [switch]$NoJobObject, [int]$MaxSeconds = 0)
if ($Dir -match 'ff9f2caf') { throw "refusing to touch the live lock dir" }
foreach ($v in 'SHARED_LOCK_DIR', 'SHARED_LOCK_NO_AUTORELEASE', 'SHARED_LOCK_POLL_SECONDS', 'SHARED_LOCK_STALE_SECONDS', 'HEAVY_LOCK_PID') {
    Remove-Item "Env:$v" -ErrorAction SilentlyContinue                # never inherit test overrides
}
$env:SHARED_LOCK_DIR = $Dir; $env:SHARED_LOCK_POLL_SECONDS = "0.2"   # TEST ONLY (temp dir, fast poll)
$PY = 'C:\Python313\python.exe'
function Log([string]$m) { Add-Content -Path $Log -Encoding utf8 -Value ("{0:o} {1}" -f (Get-Date).ToUniversalTime(), $m) }
function Lock([string[]]$a) { & $PY -I $Lock @a 2>&1 | ForEach-Object { Log "lock: $_" }; return $LASTEXITCODE }

if (-not $NoJobObject) {
    # Kill-on-close Job Object: if this runner dies for ANY reason, Windows kills every process it started
    # (Maven, forked JVMs, the python acquire/heartbeat children), so "holder pid dead" really means "job dead".
    Add-Type -Namespace SangamRunner -Name Job -MemberDefinition @'
[DllImport("kernel32.dll", CharSet = CharSet.Unicode)] public static extern IntPtr CreateJobObject(IntPtr a, string n);
[DllImport("kernel32.dll")] public static extern bool SetInformationJobObject(IntPtr h, int c, byte[] i, int l);
[DllImport("kernel32.dll")] public static extern bool AssignProcessToJobObject(IntPtr h, IntPtr p);
'@
    $job = [SangamRunner.Job]::CreateJobObject([IntPtr]::Zero, $null)
    $info = [byte[]]::new(144); [BitConverter]::GetBytes([uint32]0x2000).CopyTo($info, 16)   # KILL_ON_JOB_CLOSE
    if (-not ([SangamRunner.Job]::SetInformationJobObject($job, 9, $info, 144) -and
              [SangamRunner.Job]::AssignProcessToJobObject($job, [Diagnostics.Process]::GetCurrentProcess().Handle))) {
        Log "JOB OBJECT FAILED: not running"; exit 3
    }
}
Log "runner start pid=$PID owner=$Owner"
$acq = @('acquire', $Owner, "test job (runner pid $PID)", '--pid', "$PID")
if ($Lease) { $acq += @('--lease', $Lease) }
if ((Lock $acq) -ne 0) { Log "ACQUIRE FAILED: not running"; exit 2 }
$mvn = $null
try {
    # stand-in for: Start-Process -FilePath "$W\mvnw.cmd" -ArgumentList $mvnArgs -WorkingDirectory $W -NoNewWindow -PassThru ...
    $mvn = Start-Process -FilePath $PY -ArgumentList '-I', '-c', "`"import time; open(r'$Dir\maven.alive','w').write('x'); time.sleep($JobSeconds)`"" -NoNewWindow -PassThru
    $null = $mvn.Handle
    Log "job started pid=$($mvn.Id)"
    $deadline = if ($MaxSeconds -gt 0) { (Get-Date).AddSeconds($MaxSeconds) } else { [datetime]::MaxValue }
    while (-not $mvn.WaitForExit($BeatSeconds * 1000)) {
        $rc = Lock @('heartbeat', $Owner)
        if ($rc -eq 2) { Start-Sleep -Seconds 5; $rc = Lock @('heartbeat', $Owner) }
        if ($rc -eq 2) { Log "LOCK LOST (heartbeat refused twice): stopping the job"; break }
        if ((Get-Date) -gt $deadline) { Log "TIMEOUT: stopping the job"; break }
    }
} finally {
    if ($mvn -and -not $mvn.HasExited) { & taskkill /T /F /PID $mvn.Id 2>&1 | ForEach-Object { Log "kill: $_" }; $mvn.WaitForExit() }
    Log "job exit $(if ($mvn) { $mvn.ExitCode })"
    for ($i = 0; $i -lt 5; $i++) { if ((Lock @('release', $Owner)) -eq 0) { break }; Start-Sleep -Seconds 3 }
    Log "RUNNER DONE"
}

# Proposed Sangam heavy-lock v2 runner recipe, test build (COPY of the tool + temp lock folder; "Maven" = python sleep).
# Real use differs only in: $L = the live heavy_lock.py, no SHARED_LOCK_* variables, and the mvnw.cmd Start-Process line.
param(
    [Parameter(Mandatory = $true)][string]$Lock, [Parameter(Mandatory = $true)][string]$Dir, [Parameter(Mandatory = $true)][string]$Log,
    [string]$Owner = "customwf-recipe", [int]$Lease = 1800, [int]$BeatSeconds = 600, [int]$JobSeconds = 40, [int]$TimeoutMinutes = 120
)
if ($Lock -like "*ff9f2caf*" -or $Dir -like "*ff9f2caf*") { throw "test build must not touch the live lock" }
$env:SHARED_LOCK_DIR = $Dir; $env:SHARED_LOCK_POLL_SECONDS = "0.2"           # test only
$L = $Lock
$PY = "C:\Python313\python.exe"
function Log([string]$m) { Add-Content -Encoding utf8 -Path $Log -Value ("{0} [{1}] {2}" -f (Get-Date).ToUniversalTime().ToString("o"), $PID, $m) }

# 1. Kill-on-close job object around this runner: if the runner dies for any reason, Windows kills every process it
#    started (queued acquire, Maven, forked JVMs), so a dead runner can never leave heavy work running without the lock.
Add-Type -Namespace HL -Name Job -MemberDefinition @'
[DllImport("kernel32.dll", CharSet = CharSet.Unicode)] public static extern IntPtr CreateJobObject(IntPtr a, string n);
[DllImport("kernel32.dll")] public static extern bool SetInformationJobObject(IntPtr j, int c, byte[] i, uint n);
[DllImport("kernel32.dll")] public static extern bool AssignProcessToJobObject(IntPtr j, IntPtr p);
[DllImport("kernel32.dll")] public static extern IntPtr GetCurrentProcess();
'@
$job = [HL.Job]::CreateJobObject([IntPtr]::Zero, $null)
$info = [byte[]]::new(144)                                     # JOBOBJECT_EXTENDED_LIMIT_INFORMATION (x64)
[BitConverter]::GetBytes([uint32]0x2000).CopyTo($info, 16)     # LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
if (-not ($job -ne [IntPtr]::Zero -and [HL.Job]::SetInformationJobObject($job, 9, $info, 144) -and
          [HL.Job]::AssignProcessToJobObject($job, [HL.Job]::GetCurrentProcess()))) {
    Log "kill-on-close job object not set (error $([Runtime.InteropServices.Marshal]::GetLastWin32Error())); not running"; exit 3
}
Log "runner start; kill-on-close job set"

# 2. Acquire with THIS runner's pid. Tool output goes to the log file (never merged into PowerShell's error stream).
& $PY -I $L acquire $Owner "recipe test job, runner pid $PID" --pid $PID --lease $Lease *>> $Log
$rc = $LASTEXITCODE
if ($rc -ne 0) {
    $held = (& $PY -I $L status)[0] | ConvertFrom-Json
    if ($held -and $held.owner -eq $Owner -and $held.holder_pid -eq $PID) { & $PY -I $L release $Owner *>> $Log }
    Log "ACQUIRE FAILED rc=$rc; not running"; exit 2
}
try {
    # 3. Heavy step as a child we wait on; heartbeat from this runner every $BeatSeconds while it runs.
    $mvn = Start-Process -FilePath $PY -ArgumentList '-I', '-c', "`"import time; time.sleep($JobSeconds)`"" -NoNewWindow -PassThru
    $null = $mvn.Handle
    Log "heavy step pid=$($mvn.Id)"
    $deadline = (Get-Date).AddMinutes($TimeoutMinutes)
    while (-not $mvn.WaitForExit($BeatSeconds * 1000)) {
        if ((Get-Date) -gt $deadline) { Log "TIMEOUT: killing the heavy step tree"; & taskkill /T /F /PID $mvn.Id *>> $Log; $mvn.WaitForExit(); break }
        $hb = & $PY -I $L heartbeat $Owner 2>&1
        $hbrc = $LASTEXITCODE
        Log "heartbeat rc=$hbrc $hb"
        if ($hbrc -eq 2) { Log "LOCK LOST: stopping the heavy step"; & taskkill /T /F /PID $mvn.Id *>> $Log; $mvn.WaitForExit(); break }
    }
    Log "heavy step exit=$($mvn.ExitCode)"
} finally {
    $rel = & $PY -I $L release $Owner 2>&1
    Log "release rc=$LASTEXITCODE $rel"
    Log "RUNNER-END"
}

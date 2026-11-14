# Test-only: bind this process to a kill-on-close Job Object and report each step's result and Win32 error.
param([string]$Out)
Add-Type -Namespace SRProbe -Name Job -MemberDefinition @'
[DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] public static extern IntPtr CreateJobObject(IntPtr a, string n);
[DllImport("kernel32.dll", SetLastError = true)] public static extern bool SetInformationJobObject(IntPtr h, int c, byte[] i, int l);
[DllImport("kernel32.dll", SetLastError = true)] public static extern bool AssignProcessToJobObject(IntPtr h, IntPtr p);
[DllImport("kernel32.dll", SetLastError = true)] public static extern bool IsProcessInJob(IntPtr p, IntPtr j, out bool r);
'@
$inJob = $false; [void][SRProbe.Job]::IsProcessInJob([Diagnostics.Process]::GetCurrentProcess().Handle, [IntPtr]::Zero, [ref]$inJob)
$job = [SRProbe.Job]::CreateJobObject([IntPtr]::Zero, $null); $e1 = [Runtime.InteropServices.Marshal]::GetLastWin32Error()
$info = [byte[]]::new(144); [BitConverter]::GetBytes([uint32]0x2000).CopyTo($info, 16)
$s = [SRProbe.Job]::SetInformationJobObject($job, 9, $info, 144); $e2 = [Runtime.InteropServices.Marshal]::GetLastWin32Error()
$a = [SRProbe.Job]::AssignProcessToJobObject($job, [Diagnostics.Process]::GetCurrentProcess().Handle); $e3 = [Runtime.InteropServices.Marshal]::GetLastWin32Error()
"pid=$PID ppid=$((Get-CimInstance Win32_Process -Filter "ProcessId=$PID").ParentProcessId) alreadyInJob=$inJob create=$($job -ne [IntPtr]::Zero)/$e1 set=$s/$e2 assign=$a/$e3" | Add-Content $Out

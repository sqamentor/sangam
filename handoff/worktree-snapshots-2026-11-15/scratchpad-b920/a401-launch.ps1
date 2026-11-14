# Starts a runner script DETACHED and outside this shell's job object (Win32_Process.Create via CIM: the new process
# is a child of the WMI provider host), so it survives the agent session. Prints the new pid.
param([Parameter(Mandatory = $true)][string]$Script, [string]$Arguments = "")
$pwsh = "C:\Program Files\PowerShell\7\pwsh.exe"
$cmd = "`"$pwsh`" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File `"$Script`" $Arguments"
$r = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = $cmd }
"ReturnValue=$($r.ReturnValue) pid=$($r.ProcessId)"

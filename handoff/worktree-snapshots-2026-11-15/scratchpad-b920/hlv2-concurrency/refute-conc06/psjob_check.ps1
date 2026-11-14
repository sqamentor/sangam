# Does Stop-Job (README runner pattern, finally block) kill a python that the job started and that is still running?
$d = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\hlv2-concurrency\refute-conc06\psjob"
New-Item -ItemType Directory -Force $d | Out-Null
$marker = Join-Path $d "marker.txt"; $pidfile = Join-Path $d "pid.txt"
Remove-Item $marker, $pidfile -ErrorAction SilentlyContinue
"PowerShell $($PSVersionTable.PSVersion)"
$hb = Start-Job -ArgumentList $marker, $pidfile -ScriptBlock {
    param($m, $p)
    while ($true) {
        python -I -c "import os,sys,time; open(sys.argv[2],'w').write(str(os.getpid())); time.sleep(6); open(sys.argv[1],'w').write('done')" $m $p | Out-Null
    }
}
$t = 0; while (-not (Test-Path $pidfile) -and $t -lt 100) { Start-Sleep -Milliseconds 100; $t++ }
Start-Sleep -Seconds 1
$pyPid = [int](Get-Content $pidfile)
"python pid $pyPid alive before Stop-Job: $([bool](Get-Process -Id $pyPid -ErrorAction SilentlyContinue))"
Stop-Job $hb -ErrorAction SilentlyContinue; Remove-Job $hb -Force -ErrorAction SilentlyContinue
"python alive right after Stop-Job/Remove-Job: $([bool](Get-Process -Id $pyPid -ErrorAction SilentlyContinue))"
Start-Sleep -Seconds 7
"marker written (python finished its work): $(Test-Path $marker)"

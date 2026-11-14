# README runner pattern (lines 89-99): Start-Job heartbeat loop, then Stop-Job / Remove-Job just before release.
# Question: when a python heartbeat is in flight inside the job, is it gone when Stop-Job returns?
$d = Join-Path $PSScriptRoot "ps-markers"
Remove-Item $d -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory $d | Out-Null
$py = (Get-Command python).Source
$hbScript = Join-Path $PSScriptRoot "slow_hb.py"
$hb = Start-Job -ArgumentList $py, $hbScript, $d -ScriptBlock {
    param($py, $s, $d)
    while ($true) { Start-Sleep -Milliseconds 200; & $py -I $s $d | Out-Null }
}
$deadline = (Get-Date).AddSeconds(30)
while (-not (Get-ChildItem $d -Filter "start-*") -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 50 }
$start = Get-ChildItem $d -Filter "start-*" | Select-Object -First 1
if (-not $start) { "no heartbeat started"; exit 1 }
$hbPid = [int]($start.Name -replace "start-", "")
Start-Sleep -Milliseconds 300
"PS $($PSVersionTable.PSVersion): in-flight python pid $hbPid alive before Stop-Job: $([bool](Get-Process -Id $hbPid -ErrorAction SilentlyContinue))"
$sw = [Diagnostics.Stopwatch]::StartNew()
Stop-Job $hb -ErrorAction SilentlyContinue; Remove-Job $hb -Force -ErrorAction SilentlyContinue
$sw.Stop()
"Stop-Job+Remove-Job took $($sw.ElapsedMilliseconds) ms; python pid $hbPid alive right after: $([bool](Get-Process -Id $hbPid -ErrorAction SilentlyContinue))"
Start-Sleep -Seconds 4
"done marker written after Stop-Job (heartbeat would have completed its write): $([bool](Get-ChildItem $d -Filter "done-$hbPid"))"

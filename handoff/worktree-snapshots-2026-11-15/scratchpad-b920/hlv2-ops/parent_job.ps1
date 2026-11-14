# Test-only: does a Start-Job heartbeat loop (README recipe shape) outlive its parent when the parent is killed?
param([string]$Out)
$j = Start-Job -ArgumentList $Out -ScriptBlock {
    param($f)
    while ($true) { Add-Content -Path $f -Value ("{0} jobhost={1}" -f (Get-Date -Format o), $PID); Start-Sleep -Seconds 1 }
}
"parent pid=$PID job id=$($j.Id)" | Add-Content -Path "$Out.parent"
Start-Sleep -Seconds 300

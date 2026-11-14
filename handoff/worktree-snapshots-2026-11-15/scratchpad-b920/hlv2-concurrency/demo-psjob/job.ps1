param($out)
$PID | Set-Content "$out.pid"
$hb = Start-Job -ArgumentList $out -ScriptBlock { param($out) while ($true) { Start-Sleep -Seconds 1; Add-Content -Path $out -Value "beat" } }
Start-Sleep -Seconds 600

$d = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\quarkus-eval\test"
$m = "$env:USERPROFILE\.m2\repository"
$lic = @{}
foreach ($r in Get-Content "$d\licences.tsv") { $c = $r -split "`t"; $lic[$c[0]] = $c[4] }
$out = New-Object System.Collections.Generic.List[string]
$bad = 0
foreach ($line in Select-String "$d\resolve\resolve.log" -Pattern "Downloaded from central: https://repo.maven.apache.org/maven2/(\S+) \(") {
    $rel = $line.Matches[0].Groups[1].Value
    $parts = $rel -split "/"
    $file = $parts[-1]; $v = $parts[-2]; $a = $parts[-3]; $g = ($parts[0..($parts.Count - 4)] -join ".")
    $coord = "${g}:${a}:${v}"
    $path = Join-Path $m ($rel -replace "/", "\")
    $s1 = (Get-FileHash $path -Algorithm SHA1).Hash.ToLower()
    $c1 = ((Get-Content "$path.sha1" -Raw).Trim() -split "\s+")[0].ToLower()
    $rr = Get-Content (Join-Path (Split-Path $path) "_remote.repositories") | Select-String ([regex]::Escape($file) + ">central=")
    $verdict = if ($lic.ContainsKey($coord)) { $lic[$coord] } else { "NOT IN REVIEW" }
    $ok = ($s1 -eq $c1) -and $rr -and ($lic.ContainsKey($coord))
    if (-not $ok) { $bad++ }
    $out.Add("$coord`t$file`t$((Get-Item $path).Length)`t$((Get-FileHash $path).Hash)`tsha1 $(if ($s1 -eq $c1) {'MATCH'} else {'MISMATCH'})`t$(if ($rr) {'central'} else {'ORIGIN?'})`t$verdict")
}
$out | Set-Content "$d\resolve\verify.tsv"
"files $($out.Count) bad $bad"
$out | Where-Object { $_ -match "MISMATCH|ORIGIN\?|NOT IN REVIEW|UNKNOWN" }

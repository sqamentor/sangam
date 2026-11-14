param($root,$out)
$hosts=@('sangam-server/','sangam-spring-boot-starter/','sangam-quarkus-extension/','sangam-control-plane/','sangam-gateway/','sangam-rollup/',
'sangam-rest/src/main/java/org/sangam/rest/web/','sangam-rest/src/main/java/org/sangam/rest/config/','sangam-rest/src/main/java/org/sangam/rest/modules/',
'sangam-rest/src/main/java/org/sangam/rest/compliance/ComplianceController.java','sangam-rest/src/main/java/org/sangam/rest/compliance/ComplianceProblemAdvice.java',
'sangam-rest/src/main/java/org/sangam/rest/lifecycle/DeprecationHeadersFilter.java','sdks/java-spring-boot/','sdks/python-frameworks/','sdks/typescript-nestjs/')
$rx=[regex]'\b(?:org\.springframework|io\.quarkus|io\.micronaut|io\.helidon|jakarta\.enterprise|javax\.enterprise|jakarta\.inject|javax\.inject|com\.google\.inject|io\.dropwizard|jakarta\.ws\.rs|javax\.ws\.rs|org\.glassfish\.jersey)\.'
$res=@()
function walk($d){ foreach($c in Get-ChildItem -LiteralPath $d -Force){ if($c.PSIsContainer){ if($c.Name -like 'target*' -or $c.Name -in 'node_modules','.git'){continue}; walk $c.FullName } elseif($c.Name.EndsWith('.java')){ $c } } }
$all=@(); $hostHits=@()
foreach($f in walk $root){ $rel=$f.FullName.Substring($root.Length+1).Replace('\','/'); if($rel -notlike '*/src/main/*'){continue}
 $n=0; foreach($l in [IO.File]::ReadAllText($f.FullName).Split("`n")){ $t=$l.Trim(); if($t.StartsWith('*') -or $t.StartsWith('//') -or $t.StartsWith('/*')){continue}; if($rx.IsMatch($l)){$n++} }
 if($n -eq 0){continue}
 $isHost=$false; foreach($h in $hosts){ if($rel.StartsWith($h)){$isHost=$true} }
 if($isHost){ $hostHits+=$rel } else { $all+=$rel } }
$all | Sort-Object | Set-Content $out
"nonhost=$($all.Count) hostfiles=$($hostHits.Count)"
$hostHits | % { ($_ -split '/')[0] + $(if($_ -like 'sangam-rest/*'){'/'+($_ -split '/')[6]}else{''}) } | Group-Object | % { "  host $($_.Name): $($_.Count)" }
$all | % { ($_ -split '/')[0] } | Group-Object | % { "  ratchet $($_.Name): $($_.Count)" }


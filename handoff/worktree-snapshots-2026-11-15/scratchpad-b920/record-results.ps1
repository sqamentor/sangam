$d = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\quarkus-eval"
$st = "D:\GITHUB  Project\sangam-handoff\W4F-STAGED\M-143"
Copy-Item "$d\main\verify.tsv" "$st\main-download-verify.tsv"
$h = (Get-FileHash "$st\main-download-verify.tsv").Hash
$f = "D:\GITHUB  Project\sangam-handoff\DOWNLOADS-WAVE4.md"
$ts = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
$head = $ts + " | W4-F/B66 D-5 RESULT | resolved the 67 missing jars of the reduced main closure: sangam\mvnw.cmd -B -C -f (copy of W4F-STAGED/M-143/quarkus-eval-main-pom.xml) dependency:resolve, run from the sangam folder 18:54:28Z to 18:56:27Z, EXIT 0; 67 of 67 Downloaded from central https://repo.maven.apache.org/maven2/; strict checksums (-C) on; afterwards each jar's SHA-1 recomputed and equal to the Central .sha1, and _remote.repositories records central for each | https://repo.maven.apache.org/maven2/ | see per-jar lines | evidence W4F-STAGED/M-143/main-download-verify.tsv SHA-256 " + $h + "; statuses: download DONE (67/67 verified), licence review ALLOWED (99/99), technical compatibility NOT_RUN, deployment approval NOT APPROVED; no install script, no terms accepted, quarkus-maven-plugin not included, nothing from the held test closure fetched"
$L = New-Object System.Collections.Generic.List[string]
$L.Add($head)
$tot = 0
foreach ($r in Get-Content "$d\main\verify.tsv") {
    $c = $r -split "`t"
    $tot += [long]$c[1]
    $L.Add($ts + " | W4-F/B66 D-5 RESULT | " + $c[0] + " jar | central | " + $c[1] + " B | SHA-256 " + $c[2] + "; " + $c[3] + " (Central .sha1)")
}
Add-Content -Encoding utf8 $f $L
"lines " + $L.Count + " total bytes " + $tot + " at " + $ts

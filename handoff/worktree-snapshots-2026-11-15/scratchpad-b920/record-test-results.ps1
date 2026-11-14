$d = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\quarkus-eval\test"
$st = "D:\GITHUB  Project\sangam-handoff\W4F-STAGED\M-143"
Copy-Item "$d\resolve\pom.xml" "$st\quarkus-eval-test-resolve-pom.xml"
Copy-Item "$d\resolve\verify.tsv" "$st\test-download-verify.tsv"
$hp = (Get-FileHash "$st\quarkus-eval-test-resolve-pom.xml").Hash
$hv = (Get-FileHash "$st\test-download-verify.tsv").Hash
$ts = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
$L = New-Object System.Collections.Generic.List[string]
$L.Add($ts + " | W4-F/B66 D-5 RESULT (decision 229 order 3) | resolved the allowed missing jars of the quarkus-junit test closure with its *-deployment artefacts: sangam\mvnw.cmd -B -C -f (copy of W4F-STAGED/M-143/quarkus-eval-test-resolve-pom.xml SHA-256 " + $hp + ", the review POM with net.java.dev.jna:jna excluded) dependency:resolve, run from the sangam folder 01:38:48Z to 01:39:49Z, EXIT 0; 94 of 94 Downloaded from central https://repo.maven.apache.org/maven2/, 0 POMs (all cached); strict checksums on; each jar's SHA-1 recomputed equal to the Central .sha1, _remote.repositories records central, and every coordinate is in the 226-coordinate review; jna 5.8.0 NOT downloaded; no further extension needs a deployment artefact beyond the 9 reviewed | https://repo.maven.apache.org/maven2/ | see per-jar lines | evidence W4F-STAGED/M-143/test-download-verify.tsv SHA-256 " + $hv + "; statuses: download DONE (94/94 verified), licence review ALLOWED (225 of 226; the 3 decision-229 jars test scope only; jna 5.8.0 FLAGGED), technical compatibility NOT_RUN, deployment approval NOT APPROVED; no install script, no terms accepted, quarkus-maven-plugin not included")
foreach ($r in Get-Content "$d\resolve\verify.tsv") {
    $c = $r -split "`t"
    $note = if ($c[0] -match "jdk-classfile-backport|org.eclipse.sisu") { "; test scope only, decision 229" } else { "" }
    $L.Add($ts + " | W4-F/B66 D-5 RESULT | " + $c[0] + " " + $c[1] + " | central | " + $c[2] + " B | SHA-256 " + $c[3] + "; " + $c[4] + " (Central .sha1)" + $note)
}
Add-Content -Encoding utf8 "D:\GITHUB  Project\sangam-handoff\DOWNLOADS-WAVE4.md" $L
"lines " + $L.Count + " at " + $ts

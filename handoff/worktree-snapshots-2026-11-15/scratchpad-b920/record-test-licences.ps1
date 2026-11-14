$d = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\quarkus-eval"
$st = "D:\GITHUB  Project\sangam-handoff\W4F-STAGED\M-143"
Copy-Item "$d\test\pom.xml" "$st\quarkus-eval-test-pom.xml"
Copy-Item "$d\test\tree.txt" "$st\test-closure-tree.txt"
Copy-Item "$d\test\licences.tsv" "$st\licences-test-226.tsv"
$hp = (Get-FileHash "$st\quarkus-eval-test-pom.xml").Hash
$ht = (Get-FileHash "$st\test-closure-tree.txt").Hash
$hl = (Get-FileHash "$st\licences-test-226.tsv").Hash
$main = @{}
foreach ($r in Get-Content "$d\main\licences.tsv") { $main[($r -split "`t")[0]] = 1 }
$ts = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
$L = New-Object System.Collections.Generic.List[string]
$L.Add($ts + " | W4-F/B66 D-5 licence review, quarkus-junit test closure WITH *-deployment artefacts (decision 229 order 2) | evaluation POM W4F-STAGED/M-143/quarkus-eval-test-pom.xml SHA-256 " + $hp + ": the module closure plus io.quarkus:quarkus-junit and the 9 deployment artefacts named by the META-INF/quarkus-extension.properties of the runtime extension jars (operaton-bpm-quarkus-engine-deployment 2.2.0; quarkus-arc, core, agroal, datasource, devservices, narayana-jta, mutiny, smallrye-context-propagation -deployment 3.40.1.1), Spring and GraalJS excluded, same dependencyManagement as the module; 226 coordinates, 4 flagged by the checker: the 3 of decision 229 (now ALLOWED, test scope) and net.java.dev.jna:jna:5.8.0 (see FLAG line) | tree W4F-STAGED/M-143/test-closure-tree.txt SHA-256 " + $ht + "; licences-test-226.tsv SHA-256 " + $hl + " | - | per-coordinate lines follow for the coordinates not already in the 99-coordinate main review")
$L.Add($ts + " | W4-F/B66 D-5 licence FLAG, NOT downloaded, back to the coordinator | net.java.dev.jna:jna:5.8.0 (test, via quarkus-devservices-deployment > quarkus-devservices-common > testcontainers 2.0.5 > docker-java-transport-zerodep 3.7.1) POM 'LGPL, version 2.1' + 'Apache License v2.0' (dual): checker UNKNOWN; the policy elects Apache-2.0 only for jna 5.13.0 and 5.17.0 (decision 117) | proposal: pin jna to 5.17.0 (elected, already in ~/.m2) in the Quarkus module, or an election for 5.8.0 | - | not downloaded; without it the dev-services deployment step cannot load testcontainers, so a Quarkus test boot needs this decided")
foreach ($r in Get-Content "$d\test\licences.tsv") {
    $c = $r -split "`t"
    if ($main.ContainsKey($c[0])) { continue }
    $verdict = $c[4]
    if ($c[0] -match "jdk-classfile-backport:26|org.eclipse.sisu.inject:1.0.1|org.eclipse.sisu.plexus:1.0.1") { $verdict = "ALLOWED test scope only (owner decision 229 exception; checker by name: UNKNOWN)" }
    if ($c[0] -match "net.java.dev.jna:jna:5.8.0") { $verdict = "FLAGGED, not downloaded (checker UNKNOWN; no election for 5.8.0)" }
    $L.Add($ts + " | W4-F/B66 D-5 licence | " + $c[0] + " (" + $c[1] + ") | " + ($c[2] -replace '\|', '/') + " from POM " + ($c[3] -replace '\|', '/') + " | - | " + $verdict)
}
Add-Content -Encoding utf8 "D:\GITHUB  Project\sangam-handoff\DOWNLOADS-WAVE4.md" $L
"lines " + $L.Count + " at " + $ts

$ErrorActionPreference = "Stop"
$root = "D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\"
function HashOf([string]$p) { "sangam/" + $p + " " + (Get-FileHash ($root + "sangam\" + ($p -replace '/', '\')) -Algorithm SHA256).Hash }
$fpPom   = HashOf "sangam-flowable-ports/pom.xml"
$intPom  = HashOf "sangam-integration/pom.xml"
$chan    = HashOf "sangam-flowable-ports/src/main/java/org/sangam/ports/events/channel/ChannelAdapters.java"
$ann     = HashOf "sangam-flowable-ports/src/main/java/org/sangam/ports/events/work/ExternalWorkAnnouncer.java"
$annT    = HashOf "sangam-flowable-ports/src/test/java/org/sangam/ports/events/work/ExternalWorkAnnouncerH2Test.java"
$ports   = HashOf "sangam-server/src/main/java/org/sangam/server/config/PortsWiring.java"
$intW    = HashOf "sangam-server/src/main/java/org/sangam/server/config/IntegrationWiring.java"
$waw     = HashOf "sangam-server/src/main/java/org/sangam/server/config/WorkAnnounceWiring.java"
$wawT    = HashOf "sangam-server/src/test/java/org/sangam/server/config/WorkAnnounceWiringTest.java"
$sip     = HashOf "sangam-integration/src/main/java/org/sangam/integration/publish/history/SearchIndexPublisher.java"
$sipT    = HashOf "sangam-integration/src/test/java/org/sangam/integration/publish/history/HistoryProjectionPublishersTest.java"
$kafka   = HashOf "sangam-integration/src/main/java/org/sangam/integration/publish/KafkaPublisher.java"
$kafkaT  = HashOf "sangam-integration/src/test/java/org/sangam/integration/publish/KafkaPublisherKeyTest.java"
$hpw     = HashOf "sangam-server/src/main/java/org/sangam/server/config/HistoryProjectionWiring.java"
$hpwT    = HashOf "sangam-server/src/test/java/org/sangam/server/config/HistoryProjectionWiringTest.java"
$gate = "Gate -pl sangam-server -am clean test-compile offline (target-w3a) EXIT 0 (last run finished before 15:00Z; no file changed since). Tests written, not run"
$t = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
$lines = @(
 "$t | W3-A/B42 | DONE | against my CHANGE 13:54:55Z board line 413 (M-066, decision 101, announce only): ExternalWorkAnnouncer engine plugin sends a payload-free hint after commit through the one WakeupNotifier (IntegrationWiring bean sangamWakeupNotifier), WorkAnnounceWiring opt-out sangam.work.announce.enabled, coalesce default 20 ms; no second lease path. Files: $ann ; $annT ; $waw ; $wawT ; $intW. $gate. Open: PATCHES.md Allow-list B row (REQUEST 14:26:16Z to W3-F) | integrator, W3-F",
 "$t | W3-A/B42 | DONE | against my CHANGE 13:54:55Z board line 415 (Q4): channel secrets resolve through the SecretResolver chain, platform resolver first, static sangam.ports.secrets map last; ChannelAdapters.Environment.of over a lookup; plus module poms without local broker-client versions. Files: $chan ; $ports ; $fpPom ; $intPom ; test $wawT. $gate | integrator",
 "$t | W3-A/B42 | DONE | against my CHANGE 14:00:47Z board line 418 (M-057 external history projector): SearchIndexPublisher on history-search (PUT index/_doc/event_id), KafkaPublisher Key.EVENT_ID on history-kafka, HistoryProjectionWiring, sangamOutboxWriter routes sangam.history.* to configured sinks only. Data-lake sink not coded (no object-store connector to build on; Wave 4). Files: $sip ; $sipT ; $kafka ; $kafkaT ; $hpw ; $hpwT ; $intW. $gate | integrator, W3-F"
)
$claims = @(
 "$t B42 RELEASE W3-A claim 13:54:55Z (FILE-CLAIMS line 1274), every file: $fpPom ; $intPom ; $chan ; $ann ; $annT ; $ports ; $intW ; $waw ; $wawT",
 "$t B42 RELEASE W3-A claim 14:00:47Z (FILE-CLAIMS line 1280), every file: $sip ; $sipT ; $kafka ; $kafkaT ; $intW ; $hpw ; $hpwT. B42 holds no claims."
)
foreach ($l in $lines + $claims) { if ($l.Contains('|') -and ($l -split '\|').Count -ne 5) { throw "pipe inside text: $l" } }
$board = "D:\GITHUB  Project\sangam-handoff\WAVE3-SYNC-BOARD.md"
$fc = "D:\GITHUB  Project\sangam-handoff\FILE-CLAIMS.md"
foreach ($l in $claims) { if ($l.Contains('|')) { throw "pipe in claim line" } }
Add-Content -Path $board -Value $lines -Encoding utf8
Add-Content -Path $fc -Value $claims -Encoding utf8
$b = Get-Content $board; $c = Get-Content $fc
for ($i = 0; $i -lt $b.Count; $i++) { if ($b[$i].StartsWith("$t | W3-A/B42")) { "BOARD line " + ($i + 1) + " len " + $b[$i].Length } }
for ($i = 0; $i -lt $c.Count; $i++) { if ($c[$i].StartsWith("$t B42")) { "CLAIMS line " + ($i + 1) + " len " + $c[$i].Length } }


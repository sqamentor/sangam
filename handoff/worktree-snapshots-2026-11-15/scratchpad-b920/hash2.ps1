Set-Location "D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam"
$files = @(
 "sangam-flowable-ports/pom.xml",
 "sangam-integration/pom.xml",
 "sangam-flowable-ports/src/main/java/org/sangam/ports/events/channel/ChannelAdapters.java",
 "sangam-flowable-ports/src/main/java/org/sangam/ports/events/work/ExternalWorkAnnouncer.java",
 "sangam-flowable-ports/src/test/java/org/sangam/ports/events/work/ExternalWorkAnnouncerH2Test.java",
 "sangam-integration/src/main/java/org/sangam/integration/publish/KafkaPublisher.java",
 "sangam-integration/src/main/java/org/sangam/integration/publish/history/SearchIndexPublisher.java",
 "sangam-integration/src/test/java/org/sangam/integration/publish/KafkaPublisherKeyTest.java",
 "sangam-integration/src/test/java/org/sangam/integration/publish/history/HistoryProjectionPublishersTest.java",
 "sangam-server/src/main/java/org/sangam/server/config/PortsWiring.java",
 "sangam-server/src/main/java/org/sangam/server/config/IntegrationWiring.java",
 "sangam-server/src/main/java/org/sangam/server/config/WorkAnnounceWiring.java",
 "sangam-server/src/main/java/org/sangam/server/config/HistoryProjectionWiring.java",
 "sangam-server/src/test/java/org/sangam/server/config/WorkAnnounceWiringTest.java",
 "sangam-server/src/test/java/org/sangam/server/config/HistoryProjectionWiringTest.java"
)
foreach ($f in $files) { (Get-FileHash $f -Algorithm SHA256).Hash + " " + $f + " (" + (Get-Content $f).Count + ")" }

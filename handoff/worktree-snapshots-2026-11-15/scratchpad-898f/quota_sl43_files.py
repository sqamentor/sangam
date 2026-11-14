"""SL-43/SL-39: classify my touched files across main / wt-base (sibling copy at my start) / wt (my edits) / wt-quota."""
import hashlib
import os
import sys

S = r"C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad"
MAIN = r"D:\GITHUB  Project\Custom WorkFlow\sangam"
WT = os.path.join(S, "wt", "sangam")
BASE = os.path.join(S, "wt-base", "sangam")
NEW = os.path.join(S, "wt-quota", "sangam")

MODIFIED = """
sangam-security/src/main/java/org/sangam/security/admin/TenantAdminService.java
sangam-security/src/main/java/org/sangam/security/audit/SecurityEventType.java
sangam-persistence/tools/generate_changelogs.py
sangam-persistence/src/main/java/org/sangam/persistence/SangamSchema.java
sangam-persistence/README.md
sangam-security/src/main/resources/db/sangam-security-reference.sql
sangam-integration/src/main/java/org/sangam/integration/schema/IntegrationSchema.java
sangam-integration/src/main/java/org/sangam/integration/relay/OutboxRelayService.java
sangam-integration/src/main/java/org/sangam/integration/inbox/WebhookInboxReceiver.java
sangam-integration/src/main/java/org/sangam/integration/publish/HttpWebhookPublisher.java
sangam-integration/README.md
sangam-core/src/main/java/org/sangam/core/layer/Relay.java
sangam-rest/src/main/java/org/sangam/rest/web/ProblemHandler.java
sangam-flowable-ports/src/main/java/org/sangam/ports/content/ContentService.java
sangam-flowable-ports/src/main/java/org/sangam/ports/batch/ProductBatchService.java
sangam-server/src/main/resources/application.yaml
sangam-server/README.md
sangam-server/src/test/java/org/sangam/server/acceptance/AbstractAcceptanceIT.java
""".split()

CREATED = """
sangam-core/src/main/java/org/sangam/core/quota/QuotaLimit.java
sangam-core/src/main/java/org/sangam/core/quota/TenantQuotaExceededException.java
sangam-core/src/main/java/org/sangam/core/quota/TenantQuotas.java
sangam-security/src/main/java/org/sangam/security/quota/JdbcTenantQuotas.java
sangam-security/src/test/java/org/sangam/security/quota/JdbcTenantQuotasTest.java
sangam-integration/src/main/java/org/sangam/integration/relay/DeferPublishException.java
sangam-integration/src/main/java/org/sangam/integration/publish/WebhookSignature.java
sangam-integration/src/main/java/org/sangam/integration/publish/WebhookHealth.java
sangam-integration/src/test/java/org/sangam/integration/publish/HttpWebhookPublisherTest.java
sangam-server/src/main/java/org/sangam/server/config/WebhooksProperties.java
sangam-server/src/main/java/org/sangam/server/config/WebhooksWiring.java
sangam-server/src/main/java/org/sangam/server/config/QuotaWiring.java
sangam-server/src/main/java/org/sangam/server/bridge/TenantQuotaEnginePlugin.java
sangam-server/src/main/java/org/sangam/server/bridge/TenantQuotaFilter.java
sangam-server/src/test/java/org/sangam/server/acceptance/contracts/TenantQuotaIT.java
sangam-server/src/test/java/org/sangam/server/acceptance/contracts/WebhookRelayIT.java
""".split()


def h(root, rel):
    p = os.path.join(root, rel)
    if not os.path.exists(p):
        return None
    return hashlib.sha1(open(p, "rb").read()).hexdigest()[:8]


for rel in MODIFIED + CREATED:
    m, b, w, n = h(MAIN, rel), h(BASE, rel), h(WT, rel), h(NEW, rel)
    mine = w is not None and w != b
    print(f"{'C' if rel in CREATED else 'M'} main={m} base={b} wt={w} mine={'Y' if mine else 'n'} main==base={'Y' if m == b else 'n'}  {rel}")

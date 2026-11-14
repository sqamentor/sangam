import os
import sys

os.chdir(sys.argv[1])
p = 'sangam-ops/README.md'
s = open(p, encoding='utf-8').read()
pairs = [
    ('Only EVENT_INSTANT columns of the generated `db/timestamp-inventory.csv` are converted (date-only, local business, protected and tool columns refused). Tests `UtcConversionToolH2Test`, sangam-server `UtcConversionVendorIT` (NOT_RUN). **Gap:** the engine `ACT_*` part (W4-E, through its own inventory rows), audit event names',
     'Only EVENT_INSTANT columns of the generated `db/timestamp-inventory.csv` are converted (date-only, local business, protected and tool columns refused). The 66 Operaton `ACT_*` rows of sangam-engine `db/engine-timestamp-inventory.csv` join through `UtcInventory.productAndEngine()` (read by resource name, since sangam-engine is only `provided`; absent, it fails); on MySQL and MariaDB the five engine columns Operaton declares `timestamp` (`UtcInventory.MYSQL_SESSION_ZONED`, stored by session time zone) are refused; apply and every batch refuse while an `ACT_RU_JOB` or `ACT_RU_EXT_TASK` row holds an unexpired lock (`EngineLocks`), and an `ACT_*` table is converted only where those lock tables are readable on the same connection. Tests `UtcConversionToolH2Test`, `UtcEngineRulesH2Test`, sangam-server `UtcConversionVendorIT` (NOT_RUN). **Gap:** a cross-check that `MYSQL_SESSION_ZONED` equals sangam-engine `EngineTimestampInventory.MYSQL_SESSION_ZONED` (requested from W4-E), the engine-side hard stop of the job executor during an apply (W4-E), audit event names'),
    ('process event listeners that ending an instance on Flowable fires are WARN `SOURCE_DELETE_LISTENER` and `SOURCE_EVENT_LISTENER` (`SOURCE_MODEL_UNREAD` when the model cannot be read).',
     'process event listeners that ending an instance on Flowable fires are REJECT `SOURCE_DELETE_LISTENER` and `SOURCE_EVENT_LISTENER` (owner decision 231) until an operator acknowledges that source process definition (`acknowledgeListeners`: reason required, stored in `OPS_FMIG_ACK`, changeset 0045-07, bound to the SHA-256 of the listeners seen, with audit event `FLOWABLE_MIGRATION_LISTENERS_ACKNOWLEDGED` in the same transaction; it applies to plans made afterwards, and a changed model or another source system needs a new acknowledgement), after which they are WARN; `SOURCE_MODEL_UNREAD` (model cannot be read) always rejects.'),
    ('; whether a source delete listener should REJECT instead of WARN waits for the owner;', '; the audit event name is proposed to the integrator;'),
]
for o, n in pairs:
    assert s.count(o) == 1, o[:60]
    s = s.replace(o, n)
open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')

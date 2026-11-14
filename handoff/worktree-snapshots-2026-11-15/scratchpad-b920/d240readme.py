import os
import sys

os.chdir(sys.argv[1])
p = 'sangam-ops/README.md'
s = open(p, encoding='utf-8').read()
pairs = [
    ('on MySQL and MariaDB the five engine columns Operaton declares `timestamp` (`UtcInventory.MYSQL_SESSION_ZONED`, stored by session time zone) are refused; apply and every batch refuse',
     'on MySQL and MariaDB the five engine columns Operaton declares `timestamp` (`UtcInventory.MYSQL_SESSION_ZONED`, stored by session time zone) are refused unless the operator declares the historical session zone on the dataset (owner decision 240: `UtcDataset.sessionZone`, stored in `OPS_UTC_RUN.session_zone`, alter 0045-09, part of the fingerprint and of the dry-run and apply-started audit payloads); the tool then reads those columns with `SET time_zone` = that zone (the wall clock as written) and with `+00:00` (the stored instant, used for the guard, the cutover side and reconcile), writes in `+00:00`, restores under the declared zone with a locked check and read-back, and restores the connection\'s own session zone after every step; a wall clock in a DST overlap of the session zone stays unresolved (`SESSION_ZONE_OVERLAP`); apply, rollback and every batch of either refuse'),
    ('Tests `UtcConversionToolH2Test`, `UtcEngineRulesH2Test`, sangam-server',
     'Tests `UtcConversionToolH2Test`, `UtcEngineRulesH2Test`, `UtcSessionZoneH2Test` (MySQL session emulated over H2), sangam-server'),
    ('the engine-side hard stop of the job executor during an apply (W4-E), audit event names',
     'the engine-side hard stop of the job executor during an apply (W4-E), the session-zone flow on a real MySQL and MariaDB server (named zones need the server time-zone tables), audit event names'),
]
for o, n in pairs:
    assert s.count(o) == 1, o[:60]
    s = s.replace(o, n)
open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')

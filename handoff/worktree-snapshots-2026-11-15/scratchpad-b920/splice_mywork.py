import sys
p, newp = sys.argv[1], sys.argv[2]
s = open(p, 'rb').read().decode('utf-8').replace('\r\n', '\n')
new = open(newp, 'rb').read().decode('utf-8').replace('\r\n', '\n')
start = s.index('interface QueuedRecord {')
s = s[:start] + new
old_imp = "import { newIdempotencyKey } from '../../api/client';\n"
assert s.count(old_imp) == 1
s = s.replace(old_imp, old_imp + "import { FieldQueueError, FieldSync, type CachedState, type FieldTransport, type PendingItem, type PulledTemplate, type TemplateItem } from '../../lib/offline/fieldSync';\nimport { javaInstant, type GpsFix } from '../../lib/offline/canonical';\nimport { defaultBackend } from '../../lib/offline/storage';\nimport pkg from '../../../package.json';\n", 1)
old_doc = " * S14 my delegations and leave, S13 notification centre, S15 field verification with an offline queue (EC-16).\n"
assert s.count(old_doc) == 1
s = s.replace(old_doc, " * S14 my delegations and leave, S13 notification centre, S15 field verification with an encrypted offline queue (EC-16;\n * M-001: LocalQueueContract, device registration and the /sync protocol).\n", 1)
open(p, 'wb').write(s.replace('\n', '\r\n').encode('utf-8'))
print('ok')

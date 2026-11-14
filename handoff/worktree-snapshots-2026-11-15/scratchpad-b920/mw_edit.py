import sys
p = sys.argv[1]
s = open(p, 'rb').read().decode('utf-8').replace('\r\n', '\n')
def rep(old, new):
    global s
    assert s.count(old) == 1, (old[:90], s.count(old))
    s = s.replace(old, new)
rep("""/** The field client's calls (FieldWorkController: device registration, upload, pull; WorkspaceController: record). */
const fieldTransport: FieldTransport = {
  registerDevice: (b) => api.field.registerDevice(b),
  upload: (deviceId, packets) => api.field.syncUpload(deviceId, packets),
  pull: (deviceId, cursor) => api.field.syncPull(deviceId, cursor),
  recordVisit: (visitId, body, key) => api.field.record(visitId, body, key),
};

function localStore(): Storage | null {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

""", "")
rep("""    const backend = defaultBackend();
    setDurable(backend.durable);
    FieldSync.open({ backend, crypto: window.crypto, transport: fieldTransport, tenantId, userId, appVersion: pkg.version, platform: 'web', legacyStorage: localStore() })
""", """    setDurable(defaultBackend().durable);
    openFieldSync({ tenantId, userId })
""")
rep("import { FieldQueueError, FieldSync, type CachedState, type FieldTransport, type PendingItem, type PulledTemplate, type TemplateItem } from '../../lib/offline/fieldSync';\n",
    "import { FieldQueueError, type CachedState, type FieldSync, type PendingItem, type PulledTemplate, type TemplateItem } from '../../lib/offline/fieldSync';\nimport { openFieldSync } from '../../lib/offline/session';\n")
rep("import pkg from '../../../package.json';\n", "")
open(p, 'wb').write(s.replace('\n', '\r\n').encode('utf-8'))
print('ok')

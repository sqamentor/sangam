import os
os.chdir(r'C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-bulk2\sangam')


def ed(p, pairs):
    raw = open(p, encoding='utf-8', newline='').read()
    crlf = '\r\n' in raw
    s = raw.replace('\r\n', '\n')
    for a, b in pairs:
        assert a in s, (p, a[:70])
        s = s.replace(a, b)
    open(p, 'w', encoding='utf-8', newline='').write(s.replace('\n', '\r\n') if crlf else s)


H = 'sangam-ops/src/test/java/org/sangam/ops/bulk/BulkIngestH2Test.java'
OLD_QUOTA_TEST = open(os.path.join(os.path.dirname(__file__) if '__file__' in dir() else '.', 'x'), 'w') if False else None
s = open(H, encoding='utf-8').read().replace('\r\n', '\n')
i = s.index('    @Test\n    void quotaRefusalStoresNothingAndCarriesRetryAfter()')
j = s.index('    @Test', i + 10)
new_quota = '''    /** Test double of the SL-43 port: a batch-size limit per tenant, recording the questions. */
    static final class SizeQuota implements TenantQuotas {
        final Map<String, Long> max = new ConcurrentHashMap<>();
        final List<String> asked = new java.util.concurrent.CopyOnWriteArrayList<>();

        public void acquire(String tenantId, QuotaLimit limit, long permits) {
        }

        public void checkSize(String tenantId, QuotaLimit limit, long amount) {
            asked.add(tenantId + ":" + limit + ":" + amount);
            long m = limit(tenantId, limit);
            if (!TenantQuotas.within(m, amount)) {
                throw new TenantQuotaExceededException(tenantId, limit, m, amount, null);
            }
        }

        public long limit(String tenantId, QuotaLimit limit) {
            return limit == QuotaLimit.BATCH_SIZE ? max.getOrDefault(tenantId, QuotaLimit.UNLIMITED) : QuotaLimit.UNLIMITED;
        }
    }

    @Test
    void tenantBatchSizeQuotaIsCheckedPerChunkBeforeAnythingIsStored() {
        SizeQuota quota = new SizeQuota();
        quota.max.put("t1", 2L);
        TenantQuotas.install(quota);
        try {
            BulkIngest b = ingest();
            String j = b.create("t1", TYPE, null, "alice").id();
            TenantQuotaExceededException e = assertThrows(TenantQuotaExceededException.class, () -> b.upload("t1", j, 0, rows(0, 3, "a"), "alice"));
            assertEquals(QuotaLimit.BATCH_SIZE, e.limit());
            assertEquals(413, e.httpStatus());
            assertEquals(0L, b.job("t1", j).totalRows(), "a refused chunk stores nothing");
            assertEquals(List.of("t1:BATCH_SIZE:3"), quota.asked);
            b.upload("t1", j, 0, rows(0, 2, "a"), "alice");
            b.upload("t1", j, 0, rows(0, 2, "a"), "alice"); // replay: answered before the quota is asked
            assertEquals(List.of("t1:BATCH_SIZE:3", "t1:BATCH_SIZE:2"), quota.asked);
            assertEquals(2L, b.job("t1", j).totalRows());
            String k = b.create("t2", TYPE, null, "bob").id();
            assertEquals(3, b.upload("t2", k, 0, rows(0, 3, "a"), "bob").rows(), "another tenant has its own limit");
        } finally {
            TenantQuotas.install(null);
        }
    }

'''
s = s[:i] + new_quota + s[j:]
open(H, 'w', encoding='utf-8', newline='\n').write(s)
ed(H, [
    ('import org.sangam.core.layer.Transactions;\n', 'import org.sangam.core.layer.Transactions;\nimport org.sangam.core.quota.QuotaLimit;\nimport org.sangam.core.quota.TenantQuotaExceededException;\nimport org.sangam.core.quota.TenantQuotas;\n'),
    ('    BulkIngest ingest(String node, BulkQuota quota, BulkIngest.Limits limits) {\n        return new BulkIngest(D, tx, audit, quota, limits, node).register(sink());',
     '    BulkIngest ingest(String node, BulkIngest.Limits limits) {\n        return new BulkIngest(D, tx, audit, limits, node).register(sink());'),
    ('return ingest("node-1", null, null);', 'return ingest("node-1", null);'),
    ('ingest("node-1", null, new BulkIngest.Limits(100,', 'ingest("node-1", new BulkIngest.Limits(100,'),
    ('ingest("node-1", null, new BulkIngest.Limits(3,', 'ingest("node-1", new BulkIngest.Limits(3,'),
    ('BulkIngest c = ingest("node-1", null, null);', 'BulkIngest c = ingest("node-1", null);'),
    ('ingest("node-2", null, null)', 'ingest("node-2", null)'),
    ('ingest("node-3", null, null)', 'ingest("node-3", null)'),
    ('BulkIngest first = ingest("node-1", null, null);', 'BulkIngest first = ingest("node-1", null);'),
    ('BulkIngest restarted = ingest("node-1", null, null);', 'BulkIngest restarted = ingest("node-1", null);'),
    ('new BulkIngest(D, tx, audit, null, null, "node-1")', 'new BulkIngest(D, tx, audit, null, "node-1")'),
    ('new BulkIngest(D, tx, audit, null, null, " ")', 'new BulkIngest(D, tx, audit, null, " ")'),
])
assert 'BulkQuota' not in open(H, encoding='utf-8').read()

ed('sangam-ops/src/test/java/org/sangam/ops/bulk/BulkIngestPostgresIT.java',
   [('new BulkIngest(D, tx, AUDIT, null, BulkIngest.Limits.DEFAULTS, id)', 'new BulkIngest(D, tx, AUDIT, BulkIngest.Limits.DEFAULTS, id)')])

R = 'sangam-server/src/test/java/org/sangam/server/bridge/BulkIngestRestH2Test.java'
s = open(R, encoding='utf-8').read().replace('\r\n', '\n')
i = s.index('    /** The SL-43 quota port stand-in')
j = s.index('    @Value', i)
s = s[:i] + s[j:]
open(R, 'w', encoding='utf-8', newline='\n').write(s)
ed(R, [
    ('import org.sangam.ops.bulk.BulkQuota;\n', ''),
    ('import org.springframework.boot.test.context.TestConfiguration;\n', ''),
    ('import org.springframework.context.annotation.Bean;\n', ''),
    ('        "sangam.ops.bulk.max-rows-per-chunk=5",', '        "sangam.ops.bulk.max-rows-per-chunk=5",\n        // SL-43, the real JdbcTenantQuotas: a batch of more than 3 items is refused for every tenant\n        "sangam.quotas.defaults.maxBatchSize=3",'),
    ('429 quota with\n * Retry-After,', 'SL-43 batch-size quota (413),'),
    ('''        assertEquals(429, quota.statusCode(), quota.body());
        assertEquals("17", quota.headers().firstValue("Retry-After").orElse(null));
        assertEquals("QUOTA_EXCEEDED", json(quota).get("code").asText());''', '''        assertEquals(413, quota.statusCode(), quota.body());
        assertEquals("TENANT_QUOTA_EXCEEDED", json(quota).get("code").asText());
        assertEquals(3, json(call("GET", "/bulk/jobs/" + id, null)).get("totalRows").asInt(), "the refused chunk stored nothing");'''),
])
assert 'BulkQuota' not in open(R, encoding='utf-8').read()

# OpenAPI route text: the quota is SL-43's 413, not a 429
ed('sangam-rest/api/routes/ops.py', [
    ("The quota port is asked before anything is stored.", "The tenant's SL-43 batch-size quota is checked per chunk before anything is stored (413 TENANT_QUOTA_EXCEEDED)."),
    ('''    r['413'] = {"description": "BULK_LIMIT: rows per chunk, bytes per chunk, chunks or rows per job", "content": PROBLEM}
    if quota:
        r['429'] = {"description": "QUOTA_EXCEEDED: the tenant's bulk row quota; nothing was stored", "headers": {"Retry-After": H("Retry-After")}, "content": PROBLEM}''',
     '''    r['413'] = {"description": "BULK_LIMIT (rows or bytes per chunk, chunks or rows per job)" + (", or TENANT_QUOTA_EXCEEDED (the tenant's SL-43 batch size); nothing was stored" if quota else ""), "content": PROBLEM}'''),
])
print('ok')

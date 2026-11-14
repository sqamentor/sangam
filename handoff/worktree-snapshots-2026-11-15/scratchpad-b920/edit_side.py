import sys
root = sys.argv[1]
p = root + '/sangam-persistence/tools/generate_changelogs.py'
s = open(p, 'rb').read().decode('utf-8')


def rep(a, b, count=1):
    global s
    a = a.replace('\n', '\r\n'); b = b.replace('\n', '\r\n')
    assert s.count(a) == count, (s.count(a), a[:90])
    s = s.replace(a, b)


rep('"{vc40}": "VARCHAR(40)", "{asciistr}": "VARCHAR(255)",',
    '"{vc40}": "VARCHAR(40)", "{vc55}": "VARCHAR(55)", "{vc512}": "VARCHAR(512)", "{asciistr}": "VARCHAR(255)",', 2)
rep('"{vc40}": "VARCHAR2(40 CHAR)",', '"{vc40}": "VARCHAR2(40 CHAR)", "{vc55}": "VARCHAR2(55 CHAR)", "{vc512}": "VARCHAR2(512 CHAR)",')
rep('"{vc40}": "NVARCHAR(40)",', '"{vc40}": "NVARCHAR(40)", "{vc55}": "NVARCHAR(55)", "{vc512}": "NVARCHAR(512)",')
rep('"{vc8}": "VARCHAR(8)", "{vc16}": "VARCHAR(16)", "{vc40}": "VARCHAR(40)",\n        "{asciistr}"',
    '"{vc8}": "VARCHAR(8)", "{vc16}": "VARCHAR(16)", "{vc40}": "VARCHAR(40)", "{vc55}": "VARCHAR(55)", "{vc512}": "VARCHAR(512)",\n        "{asciistr}"')
rep('"{vc40}": 80,', '"{vc40}": 80, "{vc55}": 110, "{vc512}": 1024,')
rep('''        ("heartbeat_at", "{ts}", "NOT NULL"), ("draining", "{small}", "DEFAULT 0 NOT NULL"),
    ], ["node_id"], []),
]''', '''        ("heartbeat_at", "{ts}", "NOT NULL"), ("draining", "{small}", "DEFAULT 0 NOT NULL"),
    ], ["node_id"], []),
    # Job side tables (owner decision 2026-11-12; contract sangam-handoff/W2-JOB-SIDE-TABLES-CONTRACT.md, W2-D): Sangam
    # data about Operaton jobs lives here, keyed by the engine id; ACT_RU_JOB / ACT_RU_EXT_TASK are never altered. No
    # foreign key (Operaton's bulk deletes must not fail; orphans are ignored and swept). OFF by default: the engine
    # writes nothing here unless sangam.engine.executor-partitioning / trace-links are on.
    ("0040-01", "M-056 R39 job slot side table: slot of a job (fixed at creation), owning partition and epoch, recoveries",
     "EXEC_JOB_SLOT", [
        ("job_id", "{str64}", "NOT NULL"), ("tenant_id", "{str64}", "NOT NULL"), ("root_id", "{str64}", "NOT NULL"),
        ("slot", "{int}", "NOT NULL"), ("owner_partition", "{int}", ""), ("owner_epoch", "{long}", ""),
        ("recoveries", "{int}", "DEFAULT 0 NOT NULL"), ("created_at_ms", "{long}", "NOT NULL"),
    ], ["job_id"], [("EXEC_JOB_SLOT_SLOT", False, ["slot"]),
                    ("EXEC_JOB_SLOT_OWNER", False, ["owner_partition", "owner_epoch"])]),
    ("0040-02", "M-062 R13 creating trace context (W3C traceparent/tracestate) of a job or external task, by engine id",
     "JOB_TRACE_CONTEXT", [
        ("job_id", "{str64}", "NOT NULL"), ("kind", "{vc16}", "NOT NULL"), ("traceparent", "{vc55}", "NOT NULL"),
        ("tracestate", "{vc512}", ""), ("created_at_ms", "{long}", "NOT NULL"),
    ], ["job_id"], []),
]''')
open(p, 'wb').write(s.encode('utf-8'))

p = root + '/sangam-persistence/src/main/java/org/sangam/persistence/SangamSchema.java'
s = open(p, 'rb').read().decode('utf-8')
rep('''            "EXEC_PARTITION", "EXEC_SLOT", "EXEC_MAP", "EXEC_NODE");''',
    '''            "EXEC_PARTITION", "EXEC_SLOT", "EXEC_MAP", "EXEC_NODE",
            // job side tables keyed by the engine job id (M-056 slots, M-062 trace links), off by default
            "EXEC_JOB_SLOT", "JOB_TRACE_CONTEXT");''')
open(p, 'wb').write(s.encode('utf-8'))
print('ok')

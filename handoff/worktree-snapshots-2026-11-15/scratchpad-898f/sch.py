def add(path, pairs):
    s = open(path, encoding='utf-8').read()
    for table, anchor, cols in pairs:
        i = s.index('CREATE TABLE ' + table + ' (')
        j = s.index(anchor, i)
        k = j + len(anchor)
        lines = ''.join(f'              {c},\n' for c in cols)
        if anchor.endswith(',\n'):
            s = s[:k] + lines + s[k:]
        else:  # anchor is the last column line without a comma
            s = s[:k] + ',\n' + lines.rstrip(',\n') + s[k:]
    open(path, 'w', encoding='utf-8', newline='\n').write(s)

add('sangam-ai/src/main/java/org/sangam/ai/AiSchema.java', [
    ('AI_DRAFT', 'reviewed_at  {ts}', ['package_id   {str}']),
    ('AI_CLASSIFICATION', 'created_at       {ts} NOT NULL', ["status           {str64} DEFAULT 'PENDING' NOT NULL", 'reviewed_by      {str}', 'reviewed_at      {ts}']),
    ('AI_KB_VERSION', 'created_at {ts} NOT NULL,\n', ['metadata_json {text}', 'retire_reason {text}', 'retired_by {str}', 'retired_at {ts}']),
])
add('sangam-ops/src/main/java/org/sangam/ops/OpsSchema.java', [
    ('OPS_MIG_CHECKPOINT', 'updated_at   {ts} NOT NULL,\n', ['failed       {long} DEFAULT 0 NOT NULL', 'mismatched   {long} DEFAULT 0 NOT NULL', "status       {str64} DEFAULT 'RUNNING' NOT NULL"]),
    ('OPS_INDEX_JOB', 'updated_at {ts} NOT NULL', ['reason     {text}']),
    ('OPS_VARIABLE', 'updated_at {ts} NOT NULL,\n', ["scope_type {str64} DEFAULT 'TENANT' NOT NULL", 'tenant_id  {str64}', 'version    {long} DEFAULT 1 NOT NULL', 'reason     {text}']),
    ('OPS_SECRET', 'updated_at {ts} NOT NULL,\n', ['reason     {text}']),
])
p = 'sangam-ops/src/main/java/org/sangam/ops/OpsSchema.java'
s = open(p, encoding='utf-8').read()
a = '''              PRIMARY KEY (tenant_id, ref_name)
            )"""'''
assert a in s
s = s.replace(a, a + ''',
            """
            CREATE TABLE OPS_RETENTION_RUN (
              run_id      {str64} NOT NULL PRIMARY KEY,
              tenant_id   {str64} NOT NULL,
              plan_id     {str64} NOT NULL,
              plan_hash   {str64} NOT NULL,
              reason      {text} NOT NULL,
              purged      {int} NOT NULL,
              executed_by {str} NOT NULL,
              executed_at {ts} NOT NULL
            )""",
            "CREATE INDEX OPS_RETENTION_RUN_TENANT ON OPS_RETENTION_RUN (tenant_id, executed_at)"''')
open(p, 'w', encoding='utf-8', newline='\n').write(s)
p = 'sangam-modeller/src/main/java/org/sangam/modeller/ModellerSchema.java'
s = open(p, encoding='utf-8').read()
a = '''              created_at {ts} NOT NULL
            )""");'''
assert a in s
s = s.replace(a, '''              created_at {ts} NOT NULL
            )""",
            """
            CREATE TABLE MOD_SERVICE_MOCK (
              tenant_id       {str64} NOT NULL,
              service         {str} NOT NULL,
              operation       {str} NOT NULL,
              when_input_json {text},
              output_json     {text},
              error_code      {str64},
              delay_ms        {long},
              enabled         {small} NOT NULL,
              created_by      {str} NOT NULL,
              created_at      {ts} NOT NULL,
              PRIMARY KEY (tenant_id, service, operation)
            )""");''')
open(p, 'w', encoding='utf-8', newline='\n').write(s)

p = 'sangam-persistence/src/main/java/org/sangam/persistence/SangamSchema.java'
j = open(p, encoding='utf-8').read()
a = '            "SANGAM_FEEL_BINDING", // R10\n'
assert a in j
j = j.replace(a, a + '            "MOD_SERVICE_MOCK", "OPS_RETENTION_RUN",\n', 1)
open(p, 'w', encoding='utf-8', newline='\n').write(j)

R = '''## Module tables added after the first conversion

Exact columns are in each owner's `*Schema.java`; the generator mirrors them.

| Table | Owner | Purpose |
|---|---|---|
| `CMP_RESTRICTION`, `CMP_BREACH_PLAN` | sangam-compliance `ComplianceSchema` | processing restrictions; breach plans |
| `PIPELINE_RUN`, `PIPELINE_STEP_RUN`, `PIPELINE_SCHEDULE` | sangam-integration `PipelineStore` | pipeline runs, steps, schedules |
| `TELEMETRY_DEVICE`, `ALARM_EPISODE` | sangam-integration `TelemetrySchema` | devices; alarm episodes |
| `AI_AGENT_RUN`, `AI_AGENT_STEP`, `AI_AGENT_APPROVAL` | sangam-ai `AiSchema` | agent runs, steps, tool approvals |
| `AI_KB_VERSION` | sangam-ai | knowledge-base versions; `metadata_json`, `retire_reason`, `retired_by`, `retired_at` |
| `AI_KB_CHUNK` | sangam-ai | knowledge-base chunks and embeddings |
| `AI_DRAFT` | sangam-ai | reviewed drafts; `package_id` |
| `AI_CLASSIFICATION` | sangam-ai | classifications; `status`, `reviewed_by`, `reviewed_at` |
| `CH_CONVERSATION`, `CH_PARTICIPANT`, `CH_MESSAGE` | sangam-channels `ChannelsSchema` | conversations, participants, messages |
| `CH_OPT_IN`, `CH_INBOUND_SEEN`, `CH_POLL_CURSOR` | sangam-channels | opt-ins, inbound dedup, poll cursors |
| `MOD_EDIT_LEASE`, `MOD_TAKEOVER_DRAFT` | sangam-modeller `ModellerSchema` | model edit leases; drafts kept on takeover |
| `MOD_TEMPLATE`, `MOD_ACTION`, `MOD_ACTION_RUN`, `MOD_TEST_RUN` | sangam-modeller | templates, actions and runs, test runs |
| `MOD_SERVICE_MOCK` | sangam-modeller | stored service mocks |
| `OPS_MIG_CHECKPOINT` | sangam-ops `OpsSchema` | migration checkpoints; `failed`, `mismatched`, `status` |
| `OPS_MIG_ROW`, `OPS_USAGE_DAILY` | sangam-ops | migrated rows; daily usage |
| `OPS_SEARCH_DOC`, `OPS_SEARCH_ALIAS` | sangam-ops | search documents and active generation |
| `OPS_INDEX_JOB` | sangam-ops | reindex jobs; `reason` |
| `OPS_VARIABLE` | sangam-ops | scoped variables; `scope_type`, `tenant_id`, `version`, `reason` |
| `OPS_SECRET` | sangam-ops | secret envelopes; `reason` |
| `OPS_RETENTION_RUN` | sangam-ops | executed retention purges with their reason |
| `SANGAM_FEEL_BINDING` | sangam-engine | R10 FEEL engine per model version |
| `ONB_RUN` | onboarding (`sangam-0002-onboarding.sql`) | onboarding run state |

'''
p = 'sangam-persistence/README.md'
r = open(p, encoding='utf-8').read()
a = '## Requirements covered, file by file'
assert a in r
r = r.replace(a, R + a, 1).replace('generated DDL, 284 tables', 'generated DDL, 286 tables')
open(p, 'w', encoding='utf-8', newline='\n').write(r)

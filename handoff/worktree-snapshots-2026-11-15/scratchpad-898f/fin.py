import re, sys, importlib.util
sys.path.insert(0, 'sangam-persistence/tools')
p = 'sangam-persistence/tools/generate_changelogs.py'
s = open(p, encoding='utf-8').read()
s, n = re.subn(r'QUOTED = \{\n.*?\n\}', 'QUOTED = {\n    # temporary fallback: ALARM_EPISODE.last_value (sangam-integration TelemetrySchema) must be renamed by its owner\n    "mysql": {"last_value"},\n}', s, count=1, flags=re.S)
assert n == 1
open(p, 'w', encoding='utf-8', newline='\n').write(s)
p = 'sangam-persistence/src/main/java/org/sangam/persistence/SangamSchema.java'
j = open(p, encoding='utf-8').read()
a = '            "SANGAM_FEEL_BINDING", // R10\n'
assert a in j
j = j.replace(a, a + '''            // AiSchema, ComplianceSchema, PipelineStore, TelemetrySchema
            "AI_AGENT_RUN", "AI_AGENT_STEP", "AI_AGENT_APPROVAL", "AI_KB_VERSION", "AI_KB_CHUNK", "AI_DRAFT",
            "AI_CLASSIFICATION", "CMP_RESTRICTION", "CMP_BREACH_PLAN", "PIPELINE_RUN", "PIPELINE_STEP_RUN",
            "PIPELINE_SCHEDULE", "TELEMETRY_DEVICE", "ALARM_EPISODE",
''', 1)
open(p, 'w', encoding='utf-8', newline='\n').write(j)
spec = importlib.util.spec_from_file_location('g', 'sangam-persistence/tools/generate_changelogs.py')
g = importlib.util.module_from_spec(spec); spec.loader.exec_module(g)
import import_module_schemas as m
print(len(g.TABLES), 'tables in catalogue; full-catalogue check:')
for t, _, table, cols, pk, idx in [(x[0], x[1], x[2], x[3], x[4], x[5]) for x in g.TABLES]:
    for c in cols:
        for d, w in m.RESERVED.items():
            if c[0].upper() in w:
                print(' reserved', table, c[0], d)
    ty = {c[0]: c[1] for c in cols}
    for name, kc in [('PK', pk)] + [(i[0], i[2]) for i in idx]:
        b = sum(m.MYSQL_B.get(ty.get(k), 0) for k in kc); q = sum(m.MSSQL_B.get(ty.get(k), 0) for k in kc)
        if b > 3072 or q > 1700:
            print(' key', table, name, 'mysql', b, 'mssql', q)

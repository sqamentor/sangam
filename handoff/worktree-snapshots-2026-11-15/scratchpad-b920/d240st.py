import os
import sys

os.chdir(sys.argv[1])


def edit(p, pairs):
    s = open(p, encoding='utf-8').read()
    for o, n in pairs:
        assert s.count(o) == 1, (p, o, s.count(o))
        s = s.replace(o, n)
    open(p, 'w', encoding='utf-8', newline='').write(s)


edit('sangam-ops/src/main/java/org/sangam/ops/utc/UtcConversionStore.java', [
    (' * 0045-01/02). Every method works on the caller\'s connection and never commits.',
     ' * 0045-01/02, and the declared session zone of decision 240, alter 0045-09). Every method works on the caller\'s\n'
     ' * connection and never commits.'),
    ('            + " created_by, version";', '            + " created_by, version, session_zone";'),
    ('''        Jdbc.update(c, "INSERT INTO OPS_UTC_RUN(" + RUN_COLUMNS + ", created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?,"
                        + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",''',
     '''        Jdbc.update(c, "INSERT INTO OPS_UTC_RUN(" + RUN_COLUMNS + ", created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?,"
                        + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",'''),
    ('''                r.lastKey(), r.reportJson(), r.maintenanceRef(), r.createdBy(), r.version(), now, now);''',
     '''                r.lastKey(), r.reportJson(), r.maintenanceRef(), r.createdBy(), r.version(),
                d.sessionZone() == null ? null : SessionZones.text(d.sessionZone()), now, now);'''),
    ('''                ZoneId.of(rs.getString("source_zone")), Jdbc.instant(rs, "cutover_at"), rs.getString("tenant_filter"));''',
     '''                ZoneId.of(rs.getString("source_zone")), Jdbc.instant(rs, "cutover_at"), rs.getString("tenant_filter"),
                rs.getString("session_zone") == null ? null : ZoneId.of(rs.getString("session_zone")));'''),
])
print('ok')

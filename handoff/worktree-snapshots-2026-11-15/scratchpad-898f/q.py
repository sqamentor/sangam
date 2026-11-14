import re
src = open('sangam-integration/src/main/java/org/sangam/integration/telemetry/TelemetrySchema.java', encoding='utf-8').read()
assert 'last_no' in src and 'last_value' not in src, 'TelemetrySchema not renamed'
p = 'sangam-persistence/tools/generate_changelogs.py'
s = open(p, encoding='utf-8').read()
m = re.search(r'\("[0-9-]+", "[^\n]*?", "ALARM_EPISODE", \[[^\n]*', s)
assert m and '"last_value"' in m.group(0)
s = s.replace(m.group(0), m.group(0).replace('"last_value"', '"last_no"'))
s, n = re.subn(r'QUOTED = \{\n.*?\n\}', 'QUOTED = {\n    # empty: no column needs quoting; add entries only as a temporary fallback\n}', s, count=1, flags=re.S)
assert n == 1 and 'last_value' not in s
open(p, 'w', encoding='utf-8', newline='\n').write(s)
p = 'sangam-persistence/README.md'
r = open(p, encoding='utf-8').read()
old = ' Open item: `ALARM_EPISODE.last_value` is reserved on MySQL and is created quoted there until its owner renames it; unquoted SQL fails on MySQL.'
assert old in r
r = r.replace(old, ' No column is quoted on any dialect (`ALARM_EPISODE.last_value` was renamed `last_no`).')
open(p, 'w', encoding='utf-8', newline='\n').write(r)

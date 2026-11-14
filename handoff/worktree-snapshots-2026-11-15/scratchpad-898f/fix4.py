import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
def r(a, b):
    global s
    assert s.count(a) == 1, a[:60]
    s = s.replace(a, b)
r('"sangam.security.dev-token.groups=sangam-engine-admin,sangam-admin,sangam-operator"})',
  '"sangam.security.dev-token.groups=sangam-engine-admin,sangam-admin,sangam-operator",\n'
  '        // No job executor: batches, timers and jobs stay put while the UI calls act on them (no race with execution).\n'
  '        "operaton.bpm.job-execution.enabled=false"})')
start = s.index('        // The job executor may finish a one-instance batch first')
end = s.index('        }\n', start) + len('        }\n')
s = s[:start] + '        ok("PUT", "/engine-rest/batch/" + id + "/suspended", map("suspended", true));\n        ok("DELETE", "/engine-rest/batch/" + id + "?cascade=true", null);\n' + s[end:]
open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')

import pathlib
p = pathlib.Path('packs.py'); t = p.read_text(encoding='utf-8')
old = "        if pk.placeholders:\n            manifest['placeholders'] = pk.placeholders\n"
new = old + "        for a in pk.acceptance:\n            if (pk.id, a['id']) in RUNS:\n                a['run'] = RUNS[(pk.id, a['id'])]\n"
assert t.count(old) == 1; t = t.replace(old, new)
t = t.replace("from forms import L, OPT\n", "from forms import L, OPT\nfrom acceptance_runs import RUNS\n", 1)
p.write_text(t, encoding='utf-8', newline='')
g = pathlib.Path('gov1.py'); t = g.read_text(encoding='utf-8')
old = "              dict(bill, sim_pfms_payment='UNKNOWN'), 'UNKNOWN_OUTCOME')"
assert t.count(old) == 1
g.write_text(t.replace(old, "              dict(bill, sim_pfms_payment='UNKNOWN'))  # the UNKNOWN_OUTCOME is the item's, not caseState (SL-32)"), encoding='utf-8', newline='')
print('ok')

import os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import packs, core, gov_intl
core.build()
gov_intl.build()
packs.ALL[:] = [p for p in packs.ALL if p.id == 'gov.intl.pfm-execution']
out = os.path.join(HERE, 'out-intl')
packs.write_all(out)
print('missing', sorted(packs.MISSING))
print([p.id for p in packs.ALL])

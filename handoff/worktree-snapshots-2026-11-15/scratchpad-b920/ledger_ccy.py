"""Inventory: for every profile, resolve its chain (extends, depth-first like ProfileResolver: parents first) with
keyed-list merge by id, and list money ledgers (kind LIMIT/RECEIVABLE; default LIMIT) whose currency is missing or
differs from the resolved locale.currency. Usage: python ledger_ccy.py <profiles dir>"""
import os
import sys
import yaml

ROOT = sys.argv[1]
docs = {}
for d, _, fs in os.walk(ROOT):
    for f in fs:
        if f.endswith('.yaml') and f != 'layers.yaml':
            y = yaml.safe_load(open(os.path.join(d, f), encoding='utf-8'))
            if isinstance(y, dict) and 'profile' in y:
                docs[y['profile']['id']] = (y, os.path.relpath(os.path.join(d, f), ROOT))


def merge(base, ov):
    for k, v in ov.items():
        b = base.get(k)
        if isinstance(v, dict) and isinstance(b, dict):
            merge(b, v)
        elif isinstance(v, list) and isinstance(b, list) and v and b and all(isinstance(x, dict) and 'id' in x for x in v + b):
            by = {x['id']: x for x in b}
            for x in v:
                if x['id'] in by:
                    merge(by[x['id']], x)
                else:
                    by[x['id']] = x
            base[k] = list(by.values())
        else:
            base[k] = v
    return base


def chain(pid, seen):
    if pid in seen or pid not in docs:
        return []
    seen.add(pid)
    out = []
    for p in docs[pid][0]['profile'].get('extends', []) or []:
        out += chain(p, seen)
    # dotted scopes: prefixes first (GOI.MEA -> GOI)
    if '.' in pid:
        out = chain(pid.rsplit('.', 1)[0], seen) + out
    return out + [pid]


import copy
for pid in sorted(docs):
    data = {}
    for p in chain(pid, set()):
        merge(data, copy.deepcopy({k: v for k, v in docs[p][0].items() if k not in ('profile', 'placeholders')}))
    ccy = (data.get('locale') or {}).get('currency')
    for l in data.get('ledgers') or []:
        if str(l.get('kind', 'LIMIT')).upper() in ('LIMIT', 'RECEIVABLE') and l.get('currency') != ccy:
            print(f"{pid:28} {docs[pid][1]:45} ledger={l.get('id'):14} currency={l.get('currency')} locale={ccy}")

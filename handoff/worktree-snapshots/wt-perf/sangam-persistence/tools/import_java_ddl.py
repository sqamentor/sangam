# SPDX-License-Identifier: Apache-2.0
"""Extracts portable DDL statements from the modules' *Schema.java files and parses them into generator entries."""
import re, sys, os

FILES = [
    "sangam-config/src/main/java/org/sangam/config/store/ConfigSchema.java",
    "sangam-ledger/src/main/java/org/sangam/ledger/LedgerSchema.java",
    "sangam-registry/src/main/java/org/sangam/registry/RegistrySchema.java",
    "sangam-resources/src/main/java/org/sangam/resources/ResourcesSchema.java",
    "sangam-geo/src/main/java/org/sangam/geo/GeoSchema.java",
    "sangam-field/src/main/java/org/sangam/field/FieldSchema.java",
    "sangam-case/src/main/java/org/sangam/casefile/CaseFileSchema.java",
    "sangam-programme/src/main/java/org/sangam/programme/ProgrammeSchema.java",
]


def statements(src):
    out = []
    # text blocks
    for m in re.finditer(r'"""(.*?)"""', src, re.S):
        out.append((m.start(), m.group(1)))
    # one-line strings containing DDL keywords (outside text blocks)
    blocks = [(m.start(), m.end()) for m in re.finditer(r'"""(.*?)"""', src, re.S)]
    for m in re.finditer(r'"((?:CREATE|ALTER)[^"\\]*(?:\\.[^"\\]*)*)"', src):
        if any(a <= m.start() < b for a, b in blocks):
            continue
        out.append((m.start(), m.group(1)))
    out.sort()
    return [re.sub(r'\s+', ' ', s).strip() for _, s in out if re.match(r'\s*(CREATE|ALTER)', s)]


def split_top(s):
    parts, depth, cur = [], 0, ''
    for ch in s:
        if ch == '(':
            depth += 1
        elif ch == ')':
            depth -= 1
        if ch == ',' and depth == 0:
            parts.append(cur.strip())
            cur = ''
        else:
            cur += ch
    if cur.strip():
        parts.append(cur.strip())
    return parts


def parse(path):
    src = open(path, encoding='utf-8').read()
    tables, order, problems = {}, [], []
    for st in statements(src):
        m = re.match(r'CREATE TABLE (\w+) \((.*)\)$', st)
        if m:
            name, body = m.group(1), m.group(2)
            cols, pk, uniques = [], [], []
            for part in split_top(body):
                pm = re.match(r'PRIMARY KEY \((.*)\)$', part, re.I)
                um = re.match(r'(?:CONSTRAINT \w+ )?UNIQUE \((.*)\)$', part, re.I)
                if pm:
                    pk = [c.strip() for c in pm.group(1).split(',')]
                    continue
                if um:
                    uniques.append([c.strip() for c in um.group(1).split(',')])
                    continue
                cm = re.match(r'(\w+) (\{\w+\})(.*)$', part)
                if not cm:
                    problems.append(f"{name}: cannot parse column part '{part}'")
                    continue
                cname, tok, rest = cm.group(1), cm.group(2), cm.group(3).strip()
                inline_pk = 'PRIMARY KEY' in rest.upper()
                rest = re.sub(r'PRIMARY KEY', '', rest, flags=re.I).strip()
                if re.search(r'\bUNIQUE\b', rest, re.I):
                    uniques.append([cname])
                    rest = re.sub(r'\bUNIQUE\b', '', rest, flags=re.I).strip()
                rest = re.sub(r'\s+', ' ', rest)
                if inline_pk:
                    pk = [cname]
                    if tok != '{identity}' and 'NOT NULL' not in rest.upper():
                        rest = (rest + ' NOT NULL').strip()
                cols.append((cname, tok, rest))
            if not pk:
                problems.append(f"{name}: no primary key")
            tables[name] = {"cols": cols, "pk": pk, "idx": [], "uniques": uniques}
            order.append(name)
            continue
        m = re.match(r'CREATE (UNIQUE )?INDEX (\w+) ON (\w+) \((.*)\)$', st)
        if m:
            t = m.group(3)
            if t not in tables:
                problems.append(f"index {m.group(2)} on table {t} not defined in this file")
                continue
            tables[t]["idx"].append((m.group(2), bool(m.group(1)), [c.strip() for c in m.group(4).split(',')]))
            continue
        problems.append(f"unhandled statement: {st[:100]}")
    return order, tables, problems


if __name__ == "__main__":
    root = sys.argv[1]
    toks = set()
    for f in FILES:
        order, tables, problems = parse(os.path.join(root, f))
        print(f"== {f}: {len(order)} tables")
        for p in problems:
            print("   PROBLEM", p)
        for t in order:
            for c in tables[t]["cols"]:
                toks.add(c[1])
                if c[2] and c[2] not in ("NOT NULL",):
                    print("   col extra:", t, c)
            if tables[t]["uniques"]:
                print("   uniques:", t, tables[t]["uniques"])
    print("tokens:", sorted(toks))

import sys, os, re
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from cov_data import CENTRAL, STATE, CROSS, PRIVATE, CAPS

def esc(s): return s.replace("|", "/")

def cat_gov(rows):
    out = ["| ID | Ministry / department (type) | Typical systems | Core workflows | Sangam packs today | Source |",
           "|---|---|---|---|---|---|"]
    for r in rows:
        out.append("| %s | %s | %s | %s | %s | %s |" % (r[0], esc(r[1]), esc(r[2]), esc(r[3]), esc(r[4]), r[7]))
    return "\n".join(out)

def cat_priv(rows):
    out = ["| ID | Sector | System | Typical products | Core workflows | Sangam packs today | Source |",
           "|---|---|---|---|---|---|---|"]
    for r in rows:
        out.append("| %s | %s | %s | %s | %s | %s | %s |" % (r[0], r[1], esc(r[2]), esc(r[3]), esc(r[4]), esc(r[5]), r[8]))
    return "\n".join(out)

def needs(r, priv):
    core = r[6] if priv else r[5]
    part = r[7] if priv else r[6]
    return set(core.split()), set(part.split())

ALL = [("central", r, False) for r in CENTRAL] + [("state", r, False) for r in STATE] + \
      [("cross", r, False) for r in CROSS] + [("private", r, True) for r in PRIVATE]

codes = sorted(CAPS)
def matrix(group):
    hdr = "| ID | System | " + " | ".join("N" + c for c in codes) + " |"
    sep = "|---|---|" + "---|" * len(codes)
    out = [hdr, sep]
    for g, r, priv in ALL:
        if g != group: continue
        c, p = needs(r, priv)
        name = (r[1] + ": " + r[2]) if priv else r[1]
        name = re.sub(r"\s*\(.*?\)", "", name)
        if len(name) > 48: name = name[:46] + "..."
        cells = ["●" if k in c else ("○" if k in p else "") for k in codes]
        out.append("| %s | %s | %s |" % (r[0], esc(name), " | ".join(cells)))
    return "\n".join(out)

# counts
stats = {}
for k in codes:
    s = {"central": [0, 0], "state": [0, 0], "cross": [0, 0], "private": [0, 0], "sectors": set()}
    for g, r, priv in ALL:
        c, p = needs(r, priv)
        if k in c: s[g][0] += 1
        elif k in p: s[g][1] += 1
        else: continue
        if priv: s["sectors"].add(r[1])
    stats[k] = s

def total(k):
    s = stats[k]
    return sum(s[g][0] + s[g][1] for g in ("central", "state", "cross", "private"))
def core(k):
    s = stats[k]
    return sum(s[g][0] for g in ("central", "state", "cross", "private"))
def score(k):
    s = stats[k]
    return sum(s[g][0] + 0.5 * s[g][1] for g in ("central", "state", "cross", "private"))

ranked = sorted(codes, key=lambda k: (-score(k), -core(k), k))
nsect = len(set(r[1] for r in PRIVATE))
def ranking():
    out = ["| Rank | Capability | Central ● / ○ | State ● / ○ | Cross-cutting ● / ○ | Private ● / ○ | Private sectors (of %d) | Rows needing it | Weighted score |" % nsect,
           "|---|---|---|---|---|---|---|---|---|"]
    for i, k in enumerate(ranked, 1):
        s = stats[k]
        f = lambda g: "%d / %d" % (s[g][0], s[g][1])
        out.append("| %d | **N%s** %s | %s | %s | %s | %s | %d | %d | %.1f |" % (
            i, k, CAPS[k], f("central"), f("state"), f("cross"), f("private"), len(s["sectors"]), total(k), score(k)))
    return "\n".join(out)

counts = dict(central=len(CENTRAL), state=len(STATE), cross=len(CROSS), private=len(PRIVATE), sectors=nsect)

if __name__ == "__main__":
    tpl_path, out_path = sys.argv[1], sys.argv[2]
    tpl = open(tpl_path, encoding="utf-8").read()
    rep = {
        "{{CAT_CENTRAL}}": cat_gov(CENTRAL), "{{CAT_STATE}}": cat_gov(STATE), "{{CAT_CROSS}}": cat_gov(CROSS),
        "{{CAT_PRIVATE}}": cat_priv(PRIVATE),
        "{{MX_CENTRAL}}": matrix("central"), "{{MX_STATE}}": matrix("state"), "{{MX_CROSS}}": matrix("cross"),
        "{{MX_PRIVATE}}": matrix("private"), "{{RANKING}}": ranking(),
        "{{N_CENTRAL}}": str(counts["central"]), "{{N_STATE}}": str(counts["state"]), "{{N_CROSS}}": str(counts["cross"]),
        "{{N_PRIVATE}}": str(counts["private"]), "{{N_SECTORS}}": str(nsect),
        "{{N_ROWS}}": str(sum(counts[x] for x in ("central", "state", "cross", "private"))),
    }
    for a, b in rep.items(): tpl = tpl.replace(a, b)
    left = re.findall(r"\{\{[A-Z_]+\}\}", tpl)
    assert not left, left
    open(out_path, "w", encoding="utf-8", newline="\n").write(tpl)
    print(counts)
    for i, k in enumerate(ranked, 1): print(i, "N" + k, CAPS[k], total(k), core(k), score(k), len(stats[k]["sectors"]))

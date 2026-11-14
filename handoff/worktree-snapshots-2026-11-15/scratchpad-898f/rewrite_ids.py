"""Prefix FLOWABLE-INTO-OPERATON register ids (D-01..D-56, FC-01..FC-59) in sangam/*/src/main/**/*.java and turn
SANGAM.md decision citations written as bare D-7 / D-8 / (D-22 boundary) into ADR-D-n. Dry run unless 'apply'."""
import re, os, sys
root = r"D:\GITHUB  Project\Custom WorkFlow\sangam"
fio = open(r"D:\GITHUB  Project\Custom WorkFlow\spike\results\FLOWABLE-INTO-OPERATON.md", encoding="utf-8").read()
FIO_IDS = set(re.findall(r"^\|\s*((?:D|FC)-\d+)\s*\|", fio, re.M))
P = "FLOWABLE-INTO-OPERATON "
apply = len(sys.argv) > 1 and sys.argv[1] == "apply"
pat = re.compile(r"(?<![A-Za-z0-9-])(D|FC)-(\d+)\b")
changed, unknown = 0, []
for dp, dn, fn in os.walk(root):
    if "node_modules" in dp or "\\src\\main" not in dp:
        continue
    for f in fn:
        if not f.endswith(".java"):
            continue
        p = os.path.join(dp, f)
        s = open(p, encoding="utf-8").read()
        out, last, n = [], 0, 0
        for m in pat.finditer(s):
            kind, num = m.group(1), m.group(2)
            pre = s[max(0, m.start() - 40):m.start()]
            post = s[m.end():m.end() + 1]
            if pre.endswith("ADR-") or pre.endswith(P):
                continue
            if pre.endswith('"') and post == '"':
                continue  # a finding code literal; the guard checks it exists in the FIO register
            rel = os.path.relpath(p, root)
            if kind == "D" and len(num) == 1:
                if num == "0" and pre.lower().endswith("rule "):
                    continue  # SANGAM.md "Rule D-0"
                if num == "1" and pre.endswith("{@code "):
                    continue  # date arithmetic, not a citation
                if num in ("7", "8"):
                    rep = "ADR-D-" + num
                else:
                    unknown.append((rel, m.group(0), pre[-30:]))
                    continue
            else:
                ident = f"{kind}-{num}"
                if kind == "D" and num == "22" and "heavy engines stay outside" in pre:
                    rep = "ADR-D-22"
                elif ident in FIO_IDS:
                    rep = P + ident
                else:
                    unknown.append((rel, ident, pre[-30:]))
                    continue
            # "Operaton D-33" / "Flowable FC-04" already name the source: replace the word with the register
            start = m.start()
            for w in ("Operaton ", "Flowable "):
                if rep.startswith(P) and pre.endswith(w):
                    start = m.start() - len(w)
            out.append(s[last:start]); out.append(rep); last = m.end(); n += 1
        if n:
            out.append(s[last:])
            changed += n
            if apply:
                open(p, "w", encoding="utf-8", newline="").write("".join(out))
print("rewrites:", changed, "apply" if apply else "(dry run)")
for u in unknown:
    print("UNRESOLVED", u)

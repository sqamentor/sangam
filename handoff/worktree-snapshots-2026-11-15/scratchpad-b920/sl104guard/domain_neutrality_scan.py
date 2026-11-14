#!/usr/bin/env python3
"""SL-104 domain-neutrality scan: the standalone equivalent of sangam-core DomainNeutralityGuardTest (same files, same
lexer, same categories, same allow-list, same ratchet). Standard library only.

Usage: python -I domain_neutrality_scan.py <sangam reactor root> <iso-jdk25.txt>
iso-jdk25.txt: the JDK's java.util.Currency / Locale.getISOCountries() lists (what the JUnit guard reads at run time).
Exit 0 when every hit is on the ratchet and every ratchet entry still has a hit; 1 otherwise.
"""
import os
import re
import sys

ZONE_ID = re.compile(r"^(Africa|America|Antarctica|Arctic|Asia|Atlantic|Australia|Europe|Indian|Pacific)/[A-Za-z0-9_+\-/]+$")
OFFSET_CALL = re.compile(r"ZoneOffset\.of(Hours|HoursMinutes|HoursMinutesSeconds|TotalSeconds)\(\s*-?\s*[1-9]")
OFFSET_LITERAL = re.compile(r"^[+-](0\d|1[0-8]):?[0-5]\d$")
PROPERTY_DEFAULT = re.compile(r"getProperty\(\s*\"[^\"]*\"\s*,\s*\"([A-Z]{2})\"\s*\)")
LANGUAGE_TAG = re.compile(r"^[a-z]{2,3}[-_]([A-Z]{2})$")
TZ_ABBREV = re.compile(r"\b(IST|EST|PST|CST|EDT|PDT)\b")
SYSTEM_NAMES = re.compile(r"(?i)\b(aadhaar|gstin|upi|npci|pfms|ifms|digilocker|limbs|cctns|ecourts|umang|ssn|irs|medicare|medicaid)\b")
# ISO 4217 codes that are not a country default: "ALL" is the English word (also the Albanian lek); "XXX" means
# "no currency involved" (a neutral default) and "XTS" is the code reserved for testing.
NOT_A_CURRENCY = {"ALL", "XXX", "XTS"}

# protocol adapters (ARCHITECTURE.md: sangam-integration connectors with simulator profiles; health connectors)
def allow_listed(rel):
    return "/connector/gov/" in rel or "/sim/" in rel or rel.startswith("sangam-connectors-health/")

# Pending items: (path, category) -> reason. Shrink-only; empty since SL-104 Q6a moved the last ones to pack data.
RATCHET = {}


def lex(text):
    """Code with comments blanked (newlines kept) and the string literals as (line, value)."""
    out, lits = [], []
    i, n, line = 0, len(text), 1
    while i < n:
        ch = text[i]
        if text.startswith("//", i):
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i)); i = j
        elif text.startswith("/*", i):
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            seg = text[i:j]
            out.append(re.sub(r"[^\n]", " ", seg)); line += seg.count("\n"); i = j
        elif text.startswith('"""', i):
            j = text.find('"""', i + 3)
            j = n if j < 0 else j + 3
            seg = text[i:j]
            lits.append((line, seg[3:-3])); out.append(seg); line += seg.count("\n"); i = j
        elif ch == '"':
            j, buf = i + 1, []
            while j < n and text[j] != '"' and text[j] != "\n":
                if text[j] == "\\" and j + 1 < n:
                    buf.append(text[j:j + 2]); j += 2
                else:
                    buf.append(text[j]); j += 1
            j = min(j + 1, n)
            lits.append((line, "".join(buf))); out.append(text[i:j]); i = j
        elif ch == "'":
            j = i + 1
            while j < n and text[j] != "'" and text[j] != "\n":
                j += 2 if text[j] == "\\" else 1
            j = min(j + 1, n)
            out.append(text[i:j]); i = j
        else:
            if ch == "\n":
                line += 1
            out.append(ch); i += 1
    return "".join(out), lits


def scan(root, currencies, countries):
    hits = []
    modules = sorted(d for d in os.listdir(root) if d.startswith("sangam-") and os.path.isdir(os.path.join(root, d)))
    files = 0
    for m in modules:
        base = os.path.join(root, m, "src", "main", "java")
        for dirpath, dirnames, filenames in os.walk(base):
            dirnames[:] = sorted(d for d in dirnames if not d.startswith("target"))
            for f in sorted(filenames):
                if not f.endswith(".java"):
                    continue
                path = os.path.join(dirpath, f)
                rel = os.path.relpath(path, root).replace(os.sep, "/")
                files += 1
                if allow_listed(rel):
                    continue
                with open(path, encoding="utf-8") as fh:
                    code, lits = lex(fh.read())
                for (ln, v) in lits:
                    if ZONE_ID.match(v):
                        hits.append((rel, "zone-id", ln, v))
                    if OFFSET_LITERAL.match(v) and v.replace(":", "") not in ("+0000", "-0000"):
                        hits.append((rel, "fixed-offset", ln, v))
                    if v in currencies and v not in NOT_A_CURRENCY:
                        hits.append((rel, "currency", ln, v))
                    t = LANGUAGE_TAG.match(v)
                    if t and t.group(1) in countries:
                        hits.append((rel, "country", ln, v))
                for no, text in enumerate(code.split("\n"), start=1):
                    if OFFSET_CALL.search(text):
                        hits.append((rel, "fixed-offset", no, text.strip()))
                    for mm in PROPERTY_DEFAULT.finditer(text):
                        if mm.group(1) in countries:
                            hits.append((rel, "country", no, text.strip()))
                    if TZ_ABBREV.search(text):
                        hits.append((rel, "tz-abbrev", no, text.strip()))
                    if SYSTEM_NAMES.search(text):
                        hits.append((rel, "system-name", no, text.strip()))
    return files, hits


def main():
    root, iso = sys.argv[1], sys.argv[2]
    data = dict(l.strip().split("=", 1) for l in open(iso, encoding="utf-8") if "=" in l)
    currencies, countries = set(data["CURRENCIES"].split(",")), set(data["COUNTRIES"].split(","))
    files, hits = scan(root, currencies, countries)
    print(f"files scanned: {files}  (allow-list: */connector/gov/*, */sim/*, sangam-connectors-health/*)")
    unexpected = [h for h in hits if (h[0], h[1]) not in RATCHET]
    seen = {(h[0], h[1]) for h in hits}
    stale = [k for k in RATCHET if k not in seen]
    for h in hits:
        tag = "RATCHET " if (h[0], h[1]) in RATCHET else "VIOLATION"
        print(f"{tag} {h[1]:12} {h[0]}:{h[2]}  |  {h[3][:110]}")
    for k in stale:
        print(f"STALE RATCHET ENTRY (fixed: remove it) {k[1]} {k[0]}")
    print(f"hits={len(hits)} violations={len(unexpected)} ratchet-entries={len(RATCHET)} stale={len(stale)}")
    for k, why in RATCHET.items():
        print(f"  ratchet: {k[1]} {k[0]} -- {why}")
    return 0 if not unexpected and not stale else 1


if __name__ == "__main__":
    sys.exit(main())

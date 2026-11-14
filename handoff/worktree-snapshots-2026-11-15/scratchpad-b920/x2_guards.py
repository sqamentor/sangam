import os, re, sys, json
ROOT = sys.argv[1]           # .../sangam
REPO = os.path.dirname(ROOT)  # repo root (SANGAM.md, spike/)
def walk(skip_dot=False, skip_nm=True, skip_target_prefix=True):
    for d, dirs, fs in os.walk(ROOT):
        keep = []
        for x in dirs:
            if skip_target_prefix and x.startswith("target"): continue
            if skip_nm and x == "node_modules": continue
            if x == ".git": continue
            if skip_dot and x.startswith("."): continue
            keep.append(x)
        dirs[:] = keep
        for f in fs:
            p = os.path.join(d, f)
            yield os.path.relpath(p, ROOT).replace("\\", "/"), p
def jlines(p):
    b = open(p, "rb").read()
    try: t = b.decode("utf-8")
    except UnicodeDecodeError: return None
    ls = re.split(r"\r\n|\r|\n", t)
    if ls and ls[-1] == "": ls = ls[:-1]
    return ls
def iscomment(c): return c.startswith("*") or c.startswith("//") or c.startswith("/*")

mainjava = [(r, p) for r, p in walk() if r.endswith(".java") and "/src/main/" in r]
bad = [r for r, p in mainjava if jlines(p) is None]
print("non-UTF8 main java (readAllLines would throw):", bad)

# ---- BusinessDateGuardTest
FORB = re.compile(r"\b(?:java\.time\.)?(?:LocalDate|LocalDateTime|LocalTime|ZonedDateTime|OffsetDateTime|YearMonth|Year|MonthDay)\s*\.\s*now\s*\("
                  r"|\bZoneId\s*\.\s*systemDefault\s*\(|\bTimeZone\s*\.\s*getDefault\s*\(|\bClock\s*\.\s*systemDefaultZone\s*\(")
BD_ALLOWED = {"sangam-core/src/main/java/org/sangam/core/time/EngineTimeZone.java":1,
"sangam-connectors-health/src/main/java/org/sangam/connectors/health/x12/X12Builders.java":1,
"sangam-connectors-health/src/main/java/org/sangam/connectors/health/x12/X12.java":1,
"sangam-connectors-health/src/main/java/org/sangam/connectors/health/pas/PasConnector.java":1,
"sangam-integration/src/main/java/org/sangam/integration/connector/gov/EsignConnector.java":1,
"sangam-integration/src/main/java/org/sangam/integration/connector/gov/DigiLockerPushConnector.java":1,
"sangam-flowable-ports/src/main/java/org/sangam/ports/content/S3DocumentStore.java":1,
"sangam-server/src/main/java/org/sangam/server/config/OpsWiring.java":1,
"sangam-rollup/src/main/java/org/sangam/rollup/RollupProjector.java":2,
"sangam-registry/src/main/java/org/sangam/registry/asset/AssetService.java":2,
"sangam-registry/src/main/java/org/sangam/registry/numbering/NumberingService.java":1,
"sangam-registry/src/main/java/org/sangam/registry/register/RegisterBookService.java":1,
"sangam-registry/src/main/java/org/sangam/registry/rest/AssetController.java":1,
"sangam-onboarding/src/main/java/org/sangam/onboarding/flow/OnboardingService.java":2,
"sangam-packs/src/main/java/org/sangam/packs/sim/SimulatorConnectorGateway.java":1}
found = {}; lines = {}
for r, p in mainjava:
    ls = jlines(p) or []
    for i, l in enumerate(ls):
        c = l.strip()
        if iscomment(c): continue
        for m in FORB.finditer(l):
            found[r] = found.get(r, 0) + 1; lines.setdefault(r, []).append(f"{i+1}: {c}")
print("== BusinessDate violations ==")
for r, n in sorted(found.items()):
    if r not in BD_ALLOWED or n > BD_ALLOWED[r]: print("  VIOL", r, n, lines[r])
for r, n in BD_ALLOWED.items():
    if found.get(r, 0) != n: print("  STALE", r, "allowed", n, "found", found.get(r, 0), lines.get(r))

# ---- ConsumerIdsOutOfProductFilesTest
CID = re.compile(r"(?i)oneifms|SD-301(?!-exception\.md)|secret://ifms/|IFMS R-\d+|\bOQ-\d+")
OQ = re.compile(r"\bOQ-\d+")
CID_NOOQ = re.compile(r"(?i)oneifms|SD-301(?!-exception\.md)|secret://ifms/|IFMS R-\d+")
print("== ConsumerIds hits ==")
for r, p in walk():
    if "/src/test/" in r or r == "deploy/licence/SG-27-SD-301-exception.md": continue
    if not ("/src/main/" in r or r.endswith("pom.xml") or r.startswith("deploy/licence/") or r.startswith("deploy/airgap/")): continue
    try: t = open(p, "rb").read().decode("utf-8")
    except UnicodeDecodeError: continue
    for i, l in enumerate(t.split("\n")):
        if CID_NOOQ.search(l) or OQ.search(l):
            print("  HIT", f"{r}:{i+1}: {l.strip()[:200]}")

# ---- ProductionPredicateGuardTest
OWN = re.compile(r"(?i)\.contains\(\s*[\"']prod[\"']|Profiles\.of\([^)]*[\"']prod[\"']|equals(IgnoreCase)?\(\s*\"prod\"\s*\)|getProperty\(\s*\"sangam\.security\.production\"|\$\{sangam\.security\.production")
print("== ProductionPredicate hits ==")
for r, p in mainjava:
    if r.endswith("/ProductionMode.java"): continue
    for i, l in enumerate(jlines(p) or []):
        if OWN.search(l): print("  HIT", f"{r}:{i+1}: {l.strip()}")

# ---- JacksonModuleDiscoveryGuardTest (skips dot-dirs)
DISC = re.compile(r"\bfindAndRegisterModules\b|\bfindAndAddModules\b|\bfindModules\s*\(|\bfindModulesViaServiceLoader\b|ServiceLoader\s*\.\s*load\s*\(\s*(?:com\.fasterxml\.jackson\.databind\.)?Module\s*\.\s*class")
STR = re.compile(r"\"(?:[^\"\\]|\\.)*\"")
print("== Jackson discovery hits ==")
for r, p in walk(skip_dot=True):
    s = "/" + r
    if not (s.endswith(".java") and ("/src/main/" in s or "/src/test/" in s)): continue
    ls = jlines(p)
    if ls is None: print("  NONUTF8", r); continue
    for i, l in enumerate(ls):
        c = l.strip()
        if iscomment(c): continue
        code = STR.sub('""', c); k = code.find("//"); code = code if k < 0 else code[:k]
        if DISC.search(code): print("  HIT", f"{r}:{i+1}: {c}")

# ---- MoneyGuardTest rule 1 + 3
MONEY = re.compile(r"(?i:amount|amt\b|balance|limit|price|tariff|fee\b|fees\b|penalt|interest|total|budget|cost|charge|payable|receivable|paise|money|salary|grant|fund|outstanding|arrear|refund|collected|expended|allocated|released|committed)")
FLOAT = re.compile(r"\b(?:double|float|Double|Float)\b|\.doubleValue\s*\(|\.floatValue\s*\(|parseDouble|parseFloat")
FA = {"sangam-field/src/main/java/org/sangam/field/schedule/RouteOrdering.java":1,
"sangam-flowable-ports/src/main/java/org/sangam/ports/sla/SlaService.java":1,
"sangam-packs/src/main/java/org/sangam/packs/core/CumulativeThresholdDelegate.java":1,
"sangam-programme/src/main/java/org/sangam/programme/period/PeriodService.java":1,
"sangam-registry/src/main/java/org/sangam/registry/party/PartyMatcher.java":2,
"sangam-rest/src/main/java/org/sangam/rest/adapter/jdbc/JdbcReconciliationSupport.java":1,
"sangam-rest/src/main/java/org/sangam/rest/adapter/operaton/OperatonInsightsAdapter.java":1,
"sangam-rest/src/main/java/org/sangam/rest/port/InsightsPort.java":1,
"sangam-rest/src/main/java/org/sangam/rest/port/OpsPort.java":1}
mj2 = [(r, p) for r, p in mainjava if "/target/" not in r and "/node_modules/" not in r]
found = {}; lines = {}; billing = []
for r, p in mj2:
    for i, l in enumerate(jlines(p) or []):
        c = l.strip()
        if iscomment(c): continue
        ns = STR.sub('""', c)
        if FLOAT.search(ns) and MONEY.search(ns):
            found[r] = found.get(r, 0) + 1; lines.setdefault(r, []).append(f"{i+1}: {c}")
        if r.startswith("sangam-billing/") and re.match(r".*(\.setScale\s*\(|\bRoundingMode\.(?:HALF|UP|DOWN|CEILING|FLOOR)).*", c):
            billing.append(f"{r}:{i+1}: {c}")
print("== Money float ==")
for r, n in sorted(found.items()):
    if r not in FA or n > FA[r]: print("  VIOL", r, n, lines[r])
for r, n in FA.items():
    if found.get(r, 0) != n: print("  STALE", r, n, found.get(r, 0), lines.get(r))
print("== billing rounding ==", billing)
# rule 2
MCOL = re.compile(r"(?i)^(?:.*_)?(?:amount|amt|balance|balance_after|delta|total|distributed_amt|committed|committed_amt|encumbered|consumed|cost|budget|price|fee|penalty|interest|allocated|released|expended|acquisition_value|residual_value|functional_amount|debit_amt|credit_amt)$")
DEC = re.compile(r"\{dec}|\{num\d+_\d+}|NUMERIC\s*\(|DECIMAL\s*\(|NUMBER\s*\(\d+\s*,")
TABLE = re.compile(r"\"(\w+)\",\s*\["); COL = re.compile(r"\(\"(\w+)\",\s*\"(\{\w+})\"")
dm = {}; table = None
for l in jlines(os.path.join(ROOT, "sangam-persistence/tools/generate_changelogs.py")):
    t = TABLE.search(l)
    if t and l.strip().startswith('("0'): table = t.group(1)
    for c in COL.finditer(l):
        if table and MCOL.fullmatch(c.group(1)) and DEC.search(c.group(2)): dm[table + "." + c.group(1)] = "gen " + c.group(2)
for r, p in mj2:
    if not os.path.basename(r).endswith("Schema.java"): continue
    tb = None
    for l in jlines(p) or []:
        t = re.search(r"CREATE TABLE (\w+)", l)
        if t: tb = t.group(1); continue
        c = re.fullmatch(r"\s*(\w+)\s+(\S.*)", l)
        if tb and c and MCOL.fullmatch(c.group(1)) and DEC.search(c.group(2)): dm[tb + "." + c.group(1)] = r + " " + c.group(2).strip()
print("== money columns ==", {k: v for k, v in dm.items() if k != "REG_METER_READING.delta"}, "meter present:", "REG_METER_READING.delta" in dm)

# ---- DecisionCitationGuardTest
CITE = re.compile(r"(?<![A-Za-z0-9-])(ADR-|FLOWABLE-INTO-OPERATON )?(D|FC)-(\d+)\b")
adr = set(re.findall(r"(?m)^\|\s*(D-\d+)\s*\|", open(os.path.join(REPO, "SANGAM.md"), encoding="utf-8").read()))
fio = set(re.findall(r"(?m)^\|\s*((?:D|FC)-\d+)\s*\|", open(os.path.join(REPO, "spike/results/FLOWABLE-INTO-OPERATON.md"), encoding="utf-8").read()))
topics = {}
tp = os.path.join(ROOT, "sangam-server/src/test/resources/citations/decision-topics.txt")
for l in re.split(r"\r\n|\r|\n", open(tp, encoding="utf-8").read()):
    l = l.strip().replace("﻿", "")
    if not l or l.startswith("#"): continue
    k, v = l.split("=", 1); topics[k] = v.split("|")
errs = []; checked = 0
for d, dirs, fs in os.walk(ROOT):   # Files.walk: no dir skipping except node_modules substring filter
    dirs[:] = [x for x in dirs if x != ".git"]
    for f in fs:
        p = os.path.join(d, f); s = p.replace("\\", "/")
        if not f.endswith(".java") or "/src/main/" not in s or "node_modules" in p: continue
        ls = jlines(p)
        if ls is None: errs.append("NONUTF8 " + s); continue
        rel = os.path.relpath(p, ROOT).replace("\\", "/")
        for i, line in enumerate(ls):
            for m in CITE.finditer(line):
                checked += 1
                idd = m.group(2) + "-" + m.group(3); pre = m.group(1)
                where = f"{rel}:{i+1} {m.group()}"
                if pre is None:
                    code = m.start() > 0 and line[m.start()-1] == '"' and m.end() < len(line) and line[m.end()] == '"'
                    before = line[max(0, m.start()-7):m.start()]
                    if code and idd in fio: continue
                    if idd == "D-0" and before.lower().endswith("rule "): continue
                    if idd == "D-1" and before.endswith("{@code "): continue
                    errs.append(where + ": bare id"); continue
                isadr = pre.startswith("ADR")
                if (not (idd.startswith("D-") and idd in adr)) if isadr else (idd not in fio):
                    errs.append(where + ": not in register"); continue
                if idd.startswith("FC-"): continue
                key = ("ADR:" if isadr else "FIO:") + idd
                ctx = " ".join(ls[max(0, i-3):min(len(ls), i+4)]).lower()
                kws = topics.get(key)
                if kws is None: errs.append(where + ": no topic keywords for " + key)
                elif not any(k in ctx for k in kws): errs.append(where + f": context matches none of {key} {kws}")
print("== DecisionCitation checked", checked, "errors ==")
for e in errs: print("  ", e)

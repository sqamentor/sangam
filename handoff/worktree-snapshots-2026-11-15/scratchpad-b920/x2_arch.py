import os, re, sys
ROOT = sys.argv[1]
SKIP = {"node_modules", ".git"}
SPDX = "// SPDX-License-Identifier: Apache-2.0"
DIS = re.compile(r"@(?:org\.junit\.jupiter\.api\.)?Disabled(?![A-Za-z0-9_])|@(?:org\.junit\.)?Ignore(?![A-Za-z0-9_])")
OPER = re.compile(r"\borg\.operaton\b")
GRPC = re.compile(r"\bio\.grpc\b|\bcom\.google\.protobuf\b")
ND = re.compile(r"\bSystem\s*(?:\.|::)\s*(?:currentTimeMillis|nanoTime)\b"
 r"|\b(?:Instant|LocalDate|LocalDateTime|LocalTime|ZonedDateTime|OffsetDateTime|OffsetTime|Year|YearMonth|MonthDay)\s*(?:\.|::)\s*now\b"
 r"|\bClock\s*(?:\.|::)\s*system(?:UTC|DefaultZone)?\b"
 r"|\bnew\s+(?:java\.util\.)?Date\s*\(\s*\)|\bDate\s*::\s*new\b"
 r"|\bCalendar\s*(?:\.|::)\s*getInstance\b"
 r"|\b(?:Inheritable)?ThreadLocal\b"
 r"|\bUUID\s*(?:\.|::)\s*randomUUID\b"
 r"|\bnew\s+(?:java\.util\.)?(?:Random|SplittableRandom)\s*\(|\bnew\s+(?:java\.security\.)?SecureRandom\s*\("
 r"|\bSecureRandom\s*(?:\.|::)\s*getInstance\w*\b"
 r"|\bThreadLocalRandom\s*(?:\.|::)\s*current\b"
 r"|\bMath\s*(?:\.|::)\s*random\b"
 r"|\bRandomGenerator\s*(?:\.|::)\s*(?:getDefault|of)\b")
ALLOWED = {
"sangam-rest/src/main/java/org/sangam/rest/adapter/operaton/OperatonOpsAdapter.java":1373,
"sangam-registry/src/main/java/org/sangam/registry/party/PartyService.java":1341,
"sangam-engine/src/main/java/org/sangam/engine/OperatonEngineAdapter.java":1195,
"sangam-resources/src/main/java/org/sangam/resources/roster/RosterService.java":1146,
"sangam-registry/src/main/java/org/sangam/registry/custody/CustodyService.java":1118,
"sangam-resources/src/main/java/org/sangam/resources/queue/QueueService.java":1042,
"sangam-server/src/main/java/org/sangam/server/bridge/CellTenantMover.java":989,
"sangam-rest/src/main/java/org/sangam/rest/adapter/operaton/OperatonTaskAdapter.java":981,
"sangam-case/src/main/java/org/sangam/casefile/rest/CaseRestAdapter.java":974,
"sangam-resources/src/main/java/org/sangam/resources/booking/BookingService.java":913,
"sangam-rest/src/main/java/org/sangam/rest/adapter/operaton/OperatonProcessAdapter.java":832,
"sangam-integration/src/main/java/org/sangam/integration/pipeline/PipelineRunner.java":825,
"sangam-rest/src/main/java/org/sangam/rest/adapter/jdbc/JdbcIdentityAdminAdapter.java":821}
PROV = {"sangam-core/src/main/java/org/sangam/core/engine/SangamClock.java":1,
"sangam-core/src/main/java/org/sangam/core/time/BusinessClock.java":1,
"sangam-core/src/main/java/org/sangam/core/layer/IdSource.java":4}
DEBT = {"sangam-core/src/main/java/org/sangam/core/engine/JobIdentityBinder.java":1,
"sangam-core/src/main/java/org/sangam/core/engine/LockScope.java":1}

def files():
    for d, dirs, fs in os.walk(ROOT):
        dirs[:] = [x for x in dirs if not (x.startswith("target") or x in SKIP)]
        for f in fs:
            p = os.path.join(d, f)
            yield os.path.relpath(p, ROOT).replace("\\", "/"), p

def read(p):
    try:
        return open(p, "rb").read().decode("utf-8")
    except UnicodeDecodeError:
        return None

def jlines(t):
    # Java String.lines(): \n, \r, \r\n terminators; trailing terminator adds no line
    if t == "": return 0
    parts = re.split(r"\r\n|\r|\n", t)
    if parts and parts[-1] == "": parts = parts[:-1]
    return len(parts)

def header_ok(t):
    b = t[1:] if t.startswith("﻿") else t
    nl = b.find("\n")
    first = (b if nl < 0 else b[:nl]).rstrip()
    if first == SPDX: return True
    head = "\n".join(re.split(r"\r\n|\r|\n", b)[:40])
    return "Licensed under the Apache License" in head and "Modified by Sangam" in head

counts = {}; spdx_missing = []; dis = []; oper = []; grpc = []; nd = {}; ndlines = []
nmain = 0
for r, p in files():
    if not r.endswith(".java"):
        if "/src/main/" in r and r.endswith(".proto"): grpc.append(r + ": .proto")
        continue
    t = read(p)
    if t is None: continue
    if not header_ok(t): spdx_missing.append(r)
    lines = t.split("\n")
    if "/src/test/" in r and not r.endswith("/ArchitectureGuardTest.java"):
        for i, l in enumerate(lines):
            if DIS.search(l): dis.append(f"{r}:{i+1}: {l.strip()}")
    if "/src/main/" in r:
        counts[r] = jlines(t)
        for i, l in enumerate(lines):
            if GRPC.search(l): grpc.append(f"{r}:{i+1}")
    if r.startswith("sangam-core/src/main/"):
        for i, l in enumerate(lines):
            if OPER.search(l): oper.append(f"{r}:{i+1}")
            c = l.strip()
            if c.startswith("*") or c.startswith("//") or c.startswith("/*"): continue
            if ND.search(l):
                nd[r] = nd.get(r, 0) + 1; ndlines.append(f"{r}:{i+1}: {c}")

print("== class size ==")
for r, n in sorted(counts.items()):
    if n > 800 and r not in ALLOWED: print("NEW-OVER", r, n)
for r, c in sorted(ALLOWED.items()):
    n = counts.get(r, 0)
    if n <= 800: print("UNDER", r, n, "ceiling", c)
    elif n != c: print("GREW" if n > c else "SHRANK", r, "ceiling", c, "now", n)
print("near-limit (>=760, not allowed):", [(r, n) for r, n in counts.items() if 760 <= n <= 800 and r not in ALLOWED])
print("== spdx missing ==", spdx_missing)
print("== disabled ==", dis)
print("== operaton in core ==", oper)
print("== grpc ==", grpc)
print("== determinism ==")
ceil = dict(DEBT); ceil.update(PROV)
for r, n in sorted(nd.items()):
    if r not in ceil: print("NEW", r, n)
    elif n > ceil[r]: print("GREW", r, ceil[r], n)
for r, c in ceil.items():
    if nd.get(r, 0) < c: print("SHRANK", r, c, nd.get(r, 0))
for l in ndlines: print("  ", l)

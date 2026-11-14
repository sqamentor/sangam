import sys
p = sys.argv[1]
s = open(p, encoding="utf-8", newline="").read()
DT = ('"dedicatedTenant": "M-016: tenant code this cell is dedicated to (database-per-tenant instance); absent = shared. "\n'
      '                          "A cell registered with it hosts only that tenant, and the registration answer echoes it back "\n'
      '                          "(a dedicated cell never registers as a shared one)"')
ISO = ('"isolation": "M-016 (decision 97): SHARED (default), SCHEMA or DATABASE. A DATABASE tenant is placed only on the "\n'
       '                    "cell dedicated to it; SHARED and SCHEMA tenants on shared cells"')
reps = [
    ('"region jurisdiction residency apiBaseUrl adminBaseUrl dbVendor capacityStartsPerSec:int capacityTenants:int version",\n'
     '       ["apiBaseUrl", "jurisdiction", "residency"])',
     '"region jurisdiction residency apiBaseUrl adminBaseUrl dbVendor capacityStartsPerSec:int capacityTenants:int version "\n'
     '       "dedicatedTenant", ["apiBaseUrl", "jurisdiction", "residency"],\n'
     '       descs={' + DT + '})'),
    ('"tenantsReported:long version lastHeartbeatMs:long registeredAtMs:long",\n'
     '       enums={"status": ["UP", "DEGRADED", "DRAINING", "DOWN", "RETIRED"]})',
     '"tenantsReported:long version lastHeartbeatMs:long registeredAtMs:long dedicatedTenant",\n'
     '       enums={"status": ["UP", "DEGRADED", "DRAINING", "DOWN", "RETIRED"]},\n'
     '       descs={' + DT + '})'),
    ('"tenantCode parentCode levelKind name jurisdiction residency sector expectedStartsPerSec:num place:bool", ["tenantCode"],\n'
     '       descs={"levelKind": "Hierarchy level name (data, ADR-D-20); default ORG"})',
     '"tenantCode parentCode levelKind name jurisdiction residency sector expectedStartsPerSec:num place:bool isolation",\n'
     '       ["tenantCode"], enums={"isolation": ["SHARED", "SCHEMA", "DATABASE"]},\n'
     '       descs={"levelKind": "Hierarchy level name (data, ADR-D-20); default ORG", ' + ISO + '})'),
    ('"status routeEpoch:long createdAtMs:long updatedAtMs:long",\n'
     '       enums={"status": ["ACTIVE", "DRAINING", "MOVING", "RETIRED"]})',
     '"status routeEpoch:long createdAtMs:long updatedAtMs:long isolation",\n'
     '       enums={"status": ["ACTIVE", "DRAINING", "MOVING", "RETIRED"], "isolation": ["SHARED", "SCHEMA", "DATABASE"]},\n'
     '       descs={' + ISO + '})'),
]
for old, new in reps:
    n = s.count(old)
    if n != 1:
        sys.exit("MISS %d: %s" % (n, old[:60]))
    s = s.replace(old, new)
open(p, "w", encoding="utf-8", newline="").write(s)
print("ok")

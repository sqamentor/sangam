# SPDX-License-Identifier: Apache-2.0
"""Plan of a ci-lanes run (owner decisions 411 and 412, level 2 and level 3).

One source of truth for what a lane run builds and tests, read by .github/workflows/ci-lanes.yml (GitHub Actions,
level 2) and by sangam/tools/ci/aws/buildspec.yml (AWS CodeBuild, level 3), so both levels run the same selection
as the lanes run locally (D:/sgw/r7-it-run/chunks.ps1).

Inputs, in order of precedence: the command-line values (workflow_dispatch inputs or CodeBuild environment
overrides), then ci-lane.json at the repository root, then the defaults below.

  ci-lane.json = {"modules": "sangam-engine", "dbs": "postgres", "chunks": "A-contracts", "ui": false}
    modules  comma list of Maven modules for the build job (-pl <modules> -am); "" = the whole reactor
    dbs      comma list of postgres, oracle, sqlserver, sqlserver2025, mysql, postgres18; "" = no IT job
    chunks   comma list of chunk names below, or "all"; "" = every IT of the listed modules (no -Dit.test)
    ui       true = run the UI job (npm test)

  python -I ci_plan.py --modules X --dbs Y --chunks Z --ui false [--file ci-lane.json] [--github-output F]
  python -I ci_plan.py --db postgres --print-it-args     (the Maven arguments of one IT leg, one per line)
Standard library only.
"""
import argparse
import json
import os
import sys

# Same chunks as the local lanes (R7-it, chunks.ps1); each stays near or under 30 minutes on one database.
CHUNKS = {
    "A-contracts": "AuditChainIT,AuthorizationPolicyIT,DialectSchemaIT,E4IdempotencyIT,E5OutboxInboxIT,E7DeleteIT,"
                   "IdempotencyStoreIT,InboxIT,OperationLedgerIT,OutboxRelayIT,TenantQuotaIT,TombstonesIT,"
                   "UtcConversionVendorIT,WebhookRelayIT,WorkApiLedgerIT,UnverifiedPaymentVendorIT,NullBindDialectIT,"
                   "ExplicitOnlyPermissionDialectIT,MysqlUtf8mb4PatchIT,ServerStartsIT,NowPlusMillisDialectIT,"
                   "ValidateAfterMigrateIT,PresentationSeedOnLaneDatabaseIT,OfficeSeatFailClosedOnLaneDatabaseIT",
    "B-engine": "E1CorpusCoverageIT,E10PlatformFitIT,E11GapSetIT,E8DstTimerIT,DecimalVariableIT,FetchLockVerificationIT,"
                "FlowableMigrationStoreIT,FwtWorkerCompatIT,MultiInstanceCompensationIT,E46ConformanceIT,IfmsPacksIT,"
                "E3LeaseIT,MiwgConformanceIT,BpmnConstructPortsIT,CmmnConformanceIT",
    "B2-e2": "E2AuthorizationIT",
    "C-e16a": "E16aPatternsIT",
    "D-e16bc": "E16bPatternsIT,E16cPatternIT",
    "E-e17": "E17aDomainsIT,E17bDomainIT,E17cDomainIT",
    "F-platform": "CrossTenantIsolationIT,PlugAndPlayIT,T08PoisonOutboxIT,T35RestoreIT,ScimAcceptanceIT,TScaleLoadIT",
}
DBS = ["postgres", "oracle", "sqlserver", "sqlserver2025", "mysql", "postgres18"]
# Images: the names AcceptanceDatabase asks Testcontainers for; digests as in db-matrix.yml (RM-33). The leg pulls
# the digest and tags it with the name, so the pinned bytes are what runs (sqlserver2025 has no digest pin yet, SG-05).
IMAGES = {
    "postgres": "postgres:17@sha256:0d938e975177bef9f1f61f3f9dbcf2e0aa0a139568f66d3415ed8892eb97f6ac",
    "oracle": "gvenzl/oracle-free:23-slim-faststart@sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093",
    "sqlserver": "mcr.microsoft.com/mssql/server:2022-latest@sha256:4525e3efac9f3e241cb003bb2ff2a82818b502f37e12049d788b1a9fa7e13b7f",
    "sqlserver2025": "mcr.microsoft.com/mssql/server:2025-CU10-ubuntu-24.04",
    "mysql": "mysql:8.4@sha256:a81d043930e51745522bd5c430961cf8286f0a23f0a9a2a6e0108f7ea71b0902",
    # Decision 416: deployments stay on postgres 17.12; this leg tests 18 for new installs (18.7 digest, 2026-11-19)
    "postgres18": "postgres:18.7@sha256:e754b839f447639b7004895f4d8a4bfcb4e9dbe3a1bc035f27f646562a16fd61",
}
# Decision 122: vendor terms are accepted by the operator (repository variable / CodeBuild environment), never here.
TERMS = {"oracle": "SANGAM_ACCEPT_ORACLE_FREE_TERMS", "sqlserver": "SANGAM_ACCEPT_MSSQL_EULA",
         "sqlserver2025": "SANGAM_ACCEPT_MSSQL_EULA"}
DEFAULTS = {"modules": "", "dbs": "postgres", "chunks": "A-contracts", "ui": False}


def split(v):
    return [x.strip() for x in (v or "").split(",") if x.strip()]


def as_bool(v):
    return v if isinstance(v, bool) else str(v).strip().lower() in ("1", "true", "yes", "y")


def plan(cli, file_path):
    cfg = dict(DEFAULTS)
    if file_path and os.path.isfile(file_path):
        with open(file_path, encoding="utf-8") as f:
            data = json.load(f)
        unknown = set(data) - set(DEFAULTS)
        if unknown:
            raise SystemExit(f"{file_path}: unknown keys {sorted(unknown)}")
        cfg.update(data)
    for k in DEFAULTS:
        v = cli.get(k)
        if v in (None, ""):
            v = os.environ.get("CI_" + k.upper(), "")   # CodeBuild environment overrides (level 3)
        if v not in (None, ""):
            cfg[k] = v
    dbs = split(cfg["dbs"])
    bad = [d for d in dbs if d not in DBS]
    if bad:
        raise SystemExit(f"unknown database(s) {bad}; allowed {DBS}")
    chunks = list(CHUNKS) if str(cfg["chunks"]).strip() == "all" else split(cfg["chunks"])
    bad = [c for c in chunks if c not in CHUNKS]
    if bad:
        raise SystemExit(f"unknown chunk(s) {bad}; allowed {list(CHUNKS)} or all")
    modules = split(cfg["modules"])
    for m in modules:
        if not m.replace("-", "").replace("_", "").replace(":", "").replace(".", "").isalnum():
            raise SystemExit(f"bad module name {m!r}")
    return {"modules": ",".join(modules), "dbs": list(dict.fromkeys(dbs)), "chunks": chunks, "ui": as_bool(cfg["ui"])}


def it_args(p, db):
    profiles = "sangam-db-it,sangam-it-only" + (",oracle-it" if db == "oracle" else "")
    args = ["verify", f"-P{profiles}", f"-Dsangam.db={db}", "-Duser.timezone=UTC"]
    if p["chunks"]:
        classes = ",".join(CHUNKS[c] for c in p["chunks"])
        args += ["-pl", "sangam-server", "-am", f"-Dit.test={classes}", "-Dfailsafe.failIfNoSpecifiedTests=false",
                 "-Dsurefire.failIfNoSpecifiedTests=false"]
    elif p["modules"]:
        args += ["-pl", p["modules"], "-am"]
    return args


def build_args(p):
    args = ["verify", "-Duser.timezone=UTC"]
    if p["modules"]:
        args += ["-pl", p["modules"], "-am"]
    return args


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--modules")
    ap.add_argument("--dbs")
    ap.add_argument("--chunks")
    ap.add_argument("--ui")
    ap.add_argument("--file", default="ci-lane.json")
    ap.add_argument("--github-output")
    ap.add_argument("--db", help="with --print-it-args or --print-image or --print-terms")
    ap.add_argument("--print-it-args", action="store_true")
    ap.add_argument("--print-build-args", action="store_true")
    ap.add_argument("--print-image", action="store_true")
    ap.add_argument("--print-terms", action="store_true")
    a = ap.parse_args(argv)
    p = plan({"modules": a.modules, "dbs": a.dbs, "chunks": a.chunks, "ui": a.ui}, a.file)
    if a.print_it_args:
        print("\n".join(it_args(p, a.db)))
    elif a.print_build_args:
        print("\n".join(build_args(p)))
    elif a.print_image:
        print(IMAGES[a.db])
    elif a.print_terms:
        print(TERMS.get(a.db, ""))
    else:
        out = {"matrix": json.dumps({"db": p["dbs"]}), "has_it": "true" if p["dbs"] else "false",
               "ui": "true" if p["ui"] else "false", "modules": p["modules"], "chunks": ",".join(p["chunks"]),
               "dbs": ",".join(p["dbs"])}
        if a.github_output:
            with open(a.github_output, "a", encoding="utf-8") as f:
                for k, v in out.items():
                    f.write(f"{k}={v}\n")
        print(json.dumps(out, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())

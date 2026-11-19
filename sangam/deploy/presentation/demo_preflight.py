# SPDX-License-Identifier: Apache-2.0
"""Hard preconditions before the demo data load on demo.ifms.dev (DEMO-E2E lane, 2026-11-19). Standard library only.

  python3 -I deploy/presentation/demo_preflight.py money      # stops unless no payment can leave Sangam
  python3 -I deploy/presentation/demo_preflight.py smoke      # loads 2 cases first and checks where they stopped

money: reads the tenant's environment bindings named pfms.payment (GET /env-bindings?name=pfms.payment, permission
  package.read, SANGAM_ADMIN_TOKEN) and stops (exit 1) when an APPROVED binding does not name a sandbox or held rail.
  No binding at all is safe for money (nothing can pay) but the COMPLETED cases of the data set will then not close:
  it says so and stops too, so the deployer decides with the owner. It also refuses when SANGAM_CONNECTORS_ENV (the
  output of `docker exec <server> env`, saved to a file and passed as SANGAM_CONNECTORS_ENV=<file>) shows a payment
  connector URL that is set.
smoke: runs load_presentation_data.py on a data set of two cases that pay nothing (BILL-0005 stops at pc_dealing,
  GPF-0002 at sn_sanction; they carry claimPeriod and qualifyingService as strings over REST) and stops unless both
  report that they wait at their task. Only then load the rest.
Nothing secret is printed.
"""
import json
import os
import subprocess
import sys
import tempfile
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "data", "ifms-jaipur-fy2026-27.json")
SAFE_WORDS = ("sandbox", "held", "sim")
SMOKE = {"BILL-0005": "pc_dealing", "GPF-0002": "sn_sanction"}


def fail(msg):
    print(f"STOP: {msg}")
    sys.exit(1)


def money():
    base = os.environ.get("SANGAM_API_BASE") or fail("SANGAM_API_BASE is not set")
    token = os.environ.get("SANGAM_ADMIN_TOKEN") or fail("SANGAM_ADMIN_TOKEN is not set")
    envfile = os.environ.get("SANGAM_CONNECTORS_ENV")
    if envfile:
        for line in open(envfile, encoding="utf-8"):
            k, _, v = line.strip().partition("=")
            if k.startswith("SANGAM_CONNECTORS_") and any(w in k for w in ("PAYGOV", "PFMS", "IFMS")) and v.strip():
                fail(f"{k} is set on the server: a payment connector is configured")
    req = urllib.request.Request(base.rstrip("/") + "/env-bindings?name=pfms.payment",
                                 headers={"Authorization": f"Bearer {token}", "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        body = json.load(r)
    items = body if isinstance(body, list) else body.get("items", [])
    approved = [b for b in items if str(b.get("state", "APPROVED")).upper() == "APPROVED"]
    if not approved:
        fail("no APPROVED pfms.payment binding: nothing can pay, but the COMPLETED cases will not close; "
             "decide with the owner (bind the sandbox connector) before the load")
    for b in approved:
        text = json.dumps(b).lower()
        if not any(w in text for w in SAFE_WORDS):
            fail(f"pfms.payment binding {b.get('name')} / env {b.get('env')} is not a sandbox or held rail")
    print(f"OK: {len(approved)} pfms.payment binding(s), all sandbox or held")


def smoke():
    ds = json.load(open(DATA, encoding="utf-8"))
    ds["cases"] = [c for c in ds["cases"] if c["id"] in SMOKE]
    if len(ds["cases"]) != len(SMOKE):
        fail(f"smoke cases {sorted(SMOKE)} not all in {DATA}")
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as f:
        json.dump(ds, f)
    out = subprocess.run([sys.executable, "-I", os.path.join(HERE, "load_presentation_data.py"), "--data", f.name],
                         capture_output=True, text=True)
    print(out.stdout)
    if out.returncode != 0:
        fail(f"loader exit {out.returncode}: {out.stderr.strip()[-500:]}")
    for task in SMOKE.values():
        if f"waits at {task}" not in out.stdout:
            fail(f"no case reported 'waits at {task}'")
    print("OK: smoke cases reached their tasks; load the rest")


if __name__ == "__main__":
    {"money": money, "smoke": smoke}.get(sys.argv[1] if len(sys.argv) > 1 else "", lambda: fail(__doc__))()

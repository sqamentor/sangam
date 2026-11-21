# SPDX-License-Identifier: Apache-2.0
"""Hard preconditions before the demo data load on demo.ifms.dev (DEMO-E2E lane, 2026-11-19). Standard library only.

  python3 -I deploy/presentation/demo_preflight.py money      # stops unless the tenant's payments stay inside Sangam
  python3 -I deploy/presentation/demo_preflight.py smoke      # loads 2 cases first and checks where they stopped

money: asks the server whether the tenant is one of its sandbox tenants (GET /me with SANGAM_ADMIN_TOKEN, field
  `sandbox`; no permission needed) and stops (exit 1) unless the answer is true. For a sandbox tenant
  (sangam.demo.sandbox-tenants, the fragment's SANGAM_DEMO_SANDBOXTENANTS) the payee check, the payment and the bank
  statement are answered inside the server by its simulator and marked sandbox; for any other tenant a payment step
  calls whatever connector is configured, or stops as an incident. The check does not read the tenant's environment
  bindings (GET /env-bindings): a binding is a named endpoint of a package manifest, read when a package is
  activated; no payment is routed by it and the sandbox writes none (register E-27). It also refuses when
  SANGAM_CONNECTORS_ENV (the output of `docker exec <server> env`, saved to a file and passed as
  SANGAM_CONNECTORS_ENV=<file>) shows a payment connector URL that is set; without that file it says that the
  server's connector variables were not looked at.
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
    req = urllib.request.Request(base.rstrip("/") + "/me",
                                 headers={"Authorization": f"Bearer {token}", "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        me = json.load(r)
    tenant = me.get("tenantId") if isinstance(me, dict) else None
    if not isinstance(me, dict) or me.get("sandbox") is not True:
        fail(f"tenant {tenant} is not a sandbox tenant of this server (GET /me does not say sandbox: true): a payment "
             "step would call a configured connector or stop as an incident; the server's sandbox tenant list "
             "(sangam.demo.sandbox-tenants) is set with the owner's yes, before the load")
    print(f"OK: tenant {tenant} is a sandbox tenant: payee check, payment and bank statement are answered inside the "
          "server and marked sandbox; " + ("no payment connector URL is set on the server" if envfile else
                                           "SANGAM_CONNECTORS_ENV was not given, so the server's connector variables "
                                           "were not looked at"))


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

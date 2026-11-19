# SPDX-License-Identifier: Apache-2.0
"""Loads a presentation data set (deploy/presentation/data/*.json) into a running Sangam through its REST API.
DEMO-DATA lane 2026-11-19. Run after seed_presentation.py (tenant, seats, postings exist; every person signed in once).

Each case is started by the person the data set names (startedBy) and every task it opens is completed by the person
who holds the task's seat, each with that person's own bearer token, so separation of duties and the audit chain are
the real ones. A task gets the first option of its form's decision (the forward or approving answer, the same rule
the H2 load test PresentationDataSetH2Test uses) and the data set's remarks. A case stops at its 'stopAt' task
(RUNNING); a PAYMENT_HELD case stops when the payment request is made: the tenant's payment connector must be the
sandbox or a held rail (money paths are not live for IFMS, SD-553). Nothing here pays anything.

Tokens never come from this repository: SANGAM_PRESENTATION_TOKEN_<USERNAME> (upper case, '.' and '-' as '_') holds
a bearer token of that user, obtained by signing in. Nothing secret is printed. Idempotent: a case whose business key
already exists is skipped.

  python -I deploy/presentation/load_presentation_data.py --dry-run     # plan only, no network
  python -I deploy/presentation/load_presentation_data.py               # apply (SANGAM_API_BASE + tokens)
"""
import glob
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "data", "ifms-jaipur-fy2026-27.json")
PACKS = os.path.join(os.path.dirname(os.path.dirname(HERE)), "sangam-packs", "src", "main", "resources", "packs")
REMARKS = "Checked against the sanction order and the bill register; in order."


def token_of(user):
    return os.environ.get("SANGAM_PRESENTATION_TOKEN_" + user.upper().replace(".", "_").replace("-", "_"))


def http(method, url, token, body=None):
    h = {"Authorization": "Bearer " + token, "Accept": "application/json"}
    data = None
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        h["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=60) as res:
            raw = res.read()
            return json.loads(raw) if raw[:1] in (b"{", b"[") else None
    except urllib.error.HTTPError as e:
        raise SystemExit(f"{method} {url.split('?')[0]} -> {e.code}: {e.read().decode('utf-8', 'replace')[:300]}") from None


def form(form_key):
    """The pack form of a task (formKey sangam:<pack>/<form>), read from the shipped packs."""
    if not form_key or not form_key.startswith("sangam:"):
        return {}
    pack, name = form_key[len("sangam:"):].split("/", 1)
    path = os.path.join(PACKS, pack, "forms", name + ".form.json")
    if not os.path.exists(path):
        return {}
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def answer(task):
    f = form(task.get("formKey"))
    dec = (f.get("decision") or {}).get("options") or []
    return {"decision": dec[0]["value"] if dec else None, "remarks": REMARKS, "variables": {}}


def holder_of(seat, people):
    return next((u for u, p in people.items() if p.get("seat") == seat), None)


def run_case(base, case, people, dry):
    bk, by = case["businessKey"], case["startedBy"]
    print(f"  {case['id']} {case['process']} {bk} by {by} -> {case['target']}"
          + (f" (stops at {case['stopAt']})" if case.get("stopAt") else ""))
    if dry:
        return
    tok = token_of(by)
    if not tok:
        print(f"    skipped: no token for {by}")
        return
    q = urllib.parse.urlencode({"businessKey": bk})
    found = http("GET", f"{base}/process-instances?{q}", tok)
    if (found if isinstance(found, list) else (found or {}).get("items")):
        print("    exists: skipped")
        return
    http("POST", f"{base}/process-instances", tok,
         {"key": case["process"], "businessKey": bk, "variables": case["variables"]})
    idle = 0
    while idle < 20:
        tasks = http("GET", f"{base}/tasks?{q}", tok) or []
        tasks = tasks if isinstance(tasks, list) else tasks.get("items", [])
        if not tasks:
            idle += 1
            time.sleep(3)
            continue
        idle = 0
        for t in tasks:
            if t.get("taskDefinitionKey") == case.get("stopAt"):
                print(f"    waits at {case['stopAt']}")
                return
            seat = t.get("seatId") or t.get("candidateGroup")
            who = holder_of(seat, people) if seat else by
            ttok = token_of(who) if who else None
            if not ttok:
                print(f"    stopped at {t.get('taskDefinitionKey')}: no token for the holder of {seat}")
                return
            http("POST", f"{base}/tasks/{t['id']}/complete", ttok, answer(t))
            print(f"    {t.get('taskDefinitionKey')} done by {who}")
    print("    no open task: closed, or waiting for the payment status (HELD)")


def main():
    dry = "--dry-run" in sys.argv[1:]
    sets = sys.argv[sys.argv.index("--data") + 1:sys.argv.index("--data") + 2] if "--data" in sys.argv else [DATA]
    base = (os.environ.get("SANGAM_API_BASE") or "").rstrip("/")
    if not dry and not base:
        raise SystemExit("SANGAM_API_BASE is not set")
    for path in sets or sorted(glob.glob(os.path.join(HERE, "data", "*.json"))):
        with open(path, encoding="utf-8") as f:
            ds = json.load(f)
        print(f"data set {ds['dataSet']} {ds['version']}: tenant {ds['tenant']}, {len(ds['cases'])} cases"
              + (" (dry run: no network)" if dry else ""))
        for case in ds["cases"]:
            run_case(base, case, ds["people"], dry)


if __name__ == "__main__":
    main()

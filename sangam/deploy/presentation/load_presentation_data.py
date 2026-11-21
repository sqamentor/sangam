# SPDX-License-Identifier: Apache-2.0
"""Loads a presentation data set (deploy/presentation/data/*.json) into a running Sangam through its REST API.
DEMO-DATA lane 2026-11-19; reworked against a production-mode server by DEMO-LOADER (EV-M-0932, 2026-11-20).
Run after seed_presentation.py (tenant, seats, postings exist; every person signed in once).

Before the cases, the data set's own set-up is applied with a tenant administrator's token (SANGAM_ADMIN_TOKEN, as for
the seed and the preflight; register E-26 Follow-up 2): each payee of 'payees' is registered in the party registry by
its bank account unless the registry already holds it (a bill's payee must be a registered party), and each release
of 'setup.budget' is made through the ledger's ALLOCATE and DISTRIBUTE operations unless its first-stage account
already holds the amount. Every such amount is ASSUMED and printed so. Without the token the load is refused.

Each case is started by the person the data set names (startedBy) and every task it opens is completed by the person
who holds the task's seat, each with that person's own bearer token and seat (header X-Sangam-Seat), so separation of
duties and the audit chain are the real ones:

  - a task is visible only to the holder of its seat, so the open tasks of a case are read as each person of the data
    set in turn (GET /tasks?businessKey=, with that person's token and seat);
  - the answer is the form's decision under the form's own decision variable (the affirmative option, the same rule
    the H2 load test PresentationDataSetH2Test uses) and the data set's remarks. No other value is invented: when the
    server names required fields that the case does not carry (422 REQUIRED_FIELDS_MISSING), the case stops there and
    the fields are reported.

A case stops at its 'stopAt' task (RUNNING). After the last task the case is read back and compared with the target
the data set declares: COMPLETED (ended), RUNNING (open at stopAt), WAITING (open, no task for anyone, in the
declared case state) or PAYMENT_HELD (open, no task for anyone, past the PAO). The exit code is 1 when a case is not
at its target. A task at a seat that none of the data set's people holds is seen by nobody here: the case then shows
as not at its target. Nothing here pays anything: the tenant's payment
connector must be the sandbox or a held rail (money paths are not live for IFMS, SD-553).

Tokens never come from this repository: SANGAM_PRESENTATION_TOKEN_<USERNAME> (upper case, '.' and '-' as '_') holds
a bearer token of that user, obtained by signing in. Nothing secret is printed. A second run changes nothing: a case
whose business key already exists is not started again; its open tasks are continued, so a run that stopped half way
(a dropped connection, an expired token) is resumed by the next run.

  python -I deploy/presentation/load_presentation_data.py --dry-run     # plan only, no network
  python -I deploy/presentation/load_presentation_data.py               # apply (SANGAM_API_BASE + tokens)
  SANGAM_PRESENTATION_SETTLE_SECONDS (default 30): how long a case may show no task before it is read as settled
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
# The affirmative option of a decision, in this order of preference (FormAnswers.AFFIRMATIVE of the H2 load test).
AFFIRMATIVE = ("approve", "accept", "recommend", "sanction", "falsePositive", "feasible", "pass", "eligible", "release",
               "confirm", "allow", "grant", "proceed", "yes", "complete", "verified", "agree", "settle", "pay", "close")


class Refused(Exception):
    """An HTTP error answer: status and body."""

    def __init__(self, method, url, status, body):
        super().__init__(f"{method} {url.split('?')[0]} -> {status}: {body[:300]}")
        self.status, self.body = status, body


def token_of(user):
    return os.environ.get("SANGAM_PRESENTATION_TOKEN_" + user.upper().replace(".", "_").replace("-", "_"))


def http(method, url, token, body=None, seat=None, headers=None):
    h = {"Authorization": "Bearer " + token, "Accept": "application/json"}
    if seat:
        h["X-Sangam-Seat"] = seat
    h.update(headers or {})
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
        raise Refused(method, url, e.code, e.read().decode("utf-8", "replace")) from None


def items_of(body):
    return body if isinstance(body, list) else (body or {}).get("items", [])


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
    """The completion body: the form's decision under its own variable (a task without a decision sends none), and
    the data set's remarks also as the form field 'remarks' where the form has one (some forms require it)."""
    f = form(task.get("formKey"))
    dec = f.get("decision") or {}
    options = [o["value"] for o in dec.get("options") or []]
    variables = {}
    if any(fld.get("id") == "remarks" for fld in f.get("fields") or []):
        variables["remarks"] = REMARKS
    if dec.get("variable") and options:
        variables[dec["variable"]] = next((a for a in AFFIRMATIVE if a in options), options[0])
    return {"remarks": REMARKS, "variables": variables}


def open_tasks(base, bk, people):
    """The open tasks of the case, each with the person who can see it: [(task, user, seat)]."""
    q = urllib.parse.urlencode({"businessKey": bk, "size": 100})
    out, seen = [], set()
    for user, p in people.items():
        tok = token_of(user)
        if not tok:
            continue
        for t in items_of(http("GET", f"{base}/tasks?{q}", tok, seat=p.get("seat"))):
            if t["id"] not in seen:
                seen.add(t["id"])
                out.append((t, user, p.get("seat")))
    return out


def instance(base, bk, tok):
    """The case's own process instance (the one no other instance called), or None."""
    q = urllib.parse.urlencode({"businessKey": bk, "size": 100})
    found = items_of(http("GET", f"{base}/process-instances?{q}", tok))
    roots = [i for i in found if not i.get("superProcessInstanceId")]
    # the list leaves activeActivityIds empty; the instance itself names where it stands
    return http("GET", f"{base}/process-instances/{urllib.parse.quote(roots[0]['id'])}", tok) if roots else None


def at_target(case, inst, waits_at, open_keys, case_state):
    """The rules of PresentationDataSetH2Test, as far as the REST API shows them to the person who started the case."""
    target = case["target"]
    if inst is None:
        return False
    if target == "COMPLETED":
        return bool(inst.get("ended"))
    if target == "RUNNING":
        return not inst.get("ended") and waits_at == case.get("stopAt")
    if inst.get("ended") or open_keys:
        return False
    if target == "WAITING":      # waits for time or an outside answer, in the state the data set declares
        return case_state == case.get("expectState")
    # PAYMENT_HELD: passed by the PAO and not closed; a case still as it was submitted has not got that far
    return case_state not in (None, "SUBMITTED", "CLOSED")


def run_case(base, case, people, dry, settle):
    """Returns True when the case stands at its target (or on a dry run)."""
    bk, by = case["businessKey"], case["startedBy"]
    print(f"  {case['id']} {case['process']} {bk} by {by} -> {case['target']}"
          + (f" (stops at {case['stopAt']})" if case.get("stopAt") else ""))
    if dry:
        return True
    tok = token_of(by)
    if not tok:
        print(f"    skipped: no token for {by}")
        return False
    seat = (people.get(by) or {}).get("seat")
    if instance(base, bk, tok) is None:
        http("POST", f"{base}/process-instances", tok,
             {"key": case["process"], "businessKey": bk, "variables": case["variables"]}, seat=seat)
        print("    started")
    else:
        print("    exists: continued")
    waits_at, quiet, sent = None, 0.0, False
    while quiet < settle:
        tasks = open_tasks(base, bk, people)
        stop = [t for t, _, _ in tasks if t.get("taskDefinitionKey") == case.get("stopAt")]
        if stop:
            waits_at = case["stopAt"]
            print(f"    waits at {waits_at}")
            break
        if not tasks:
            inst = instance(base, bk, tok)
            if inst and inst.get("ended"):
                break
            if not sent and quiet >= min(settle, 6) and case.get("events"):
                for ev in case["events"]:
                    # an outside answer the data set declares (the citizen's rating): POST /messages by business key
                    http("POST", f"{base}/messages", tok, {"messageName": ev["message"], "businessKey": bk,
                                                            "variables": ev.get("variables", {}), "all": False})
                    print(f"    message {ev['message']} delivered")
                sent, quiet = True, 0.0
                continue
            time.sleep(2)
            quiet += 2
            continue
        quiet = 0.0
        for t, who, tseat in tasks:
            try:
                http("POST", f"{base}/tasks/{t['id']}/complete", token_of(who), answer(t), seat=tseat)
            except Refused as e:
                print(f"    stopped at {t.get('taskDefinitionKey')} ({who}): {e}")
                return False
            print(f"    {t.get('taskDefinitionKey')} done by {who}")
    inst = instance(base, bk, tok)
    open_keys = [t.get("taskDefinitionKey") for t, _, _ in open_tasks(base, bk, people)]
    case_state = None
    if inst is not None and not inst.get("ended"):
        case_state = (http("GET", f"{base}/process-instances/{urllib.parse.quote(inst['id'])}/variables", tok) or {}).get("caseState")
    ok = at_target(case, inst, waits_at, open_keys, case_state)
    state = "no instance" if inst is None else ("ended" if inst.get("ended") else "open at " + ", ".join(
        inst.get("activeActivityIds") or ["(nothing active)"]))
    print(f"    {'OK' if ok else 'NOT AT TARGET'}: {state}" + (f", case state {case_state}" if case_state else "")
          + (f"; open tasks {', '.join(open_keys)}" if open_keys else ""))
    return ok


def rupees(paise):
    """Integer paise (the data set's money, SD-398) as the ledger API's decimal rupees."""
    return f"{paise // 100}.{paise % 100:02d}"


def setup_payees(base, ds, tok, dry):
    """Register E-26 Follow-up 2 (vi): a bill's payee must be a party of the registry (an unregistered payee is
    refused). Each payee of the data set is registered once by its bank account (POST /registry/parties), after a
    lookup by that account (GET /registry/parties?idType=BANK_ACCOUNT): one holder is kept as it is, none is created,
    more than one is reported and nothing is changed. Returns what went wrong."""
    wrong = []
    for p in ds.get("payees") or []:
        account = f"{p['payeeAccount']}@{p['ifsc']}"
        if dry:
            print(f"  set-up payee {p['employeeId']}: register by account if absent")
            continue
        q = urllib.parse.urlencode({"idType": "BANK_ACCOUNT", "idValue": account, "size": 10})
        found = items_of(http("GET", f"{base}/registry/parties?{q}", tok, headers={"X-Purpose": "payment"}))
        if len(found) > 1:
            wrong.append(f"payee {p['employeeId']}: {len(found)} parties hold its account")
            print(f"  set-up payee {p['employeeId']}: {len(found)} parties hold the account, nothing changed")
            continue
        if found:
            print(f"  set-up payee {p['employeeId']}: already registered")
            continue
        http("POST", f"{base}/registry/parties", tok,
             {"kind": "PERSON", "name": p["payee"], "source": "payments",
              "identifiers": [{"type": "BANK_ACCOUNT", "value": account}]},
             headers={"Idempotency-Key": f"presentation-payee-{ds['dataSet']}-{p['employeeId']}", "X-Purpose": "payment"})
        print(f"  set-up payee {p['employeeId']}: registered")
    return wrong


def setup_budget(base, ds, tok, dry):
    """Register E-26 Follow-up 2 (v): budget the data set's cases draw on that no other step releases, through the
    ledger's own operations (POST /ledger/by-ledger/operations: ALLOCATE at the first stage, one DISTRIBUTE per further
    stage), never a database write. Every amount is ASSUMED (setup.budget[].assumed) and printed so. A release whose
    first-stage account already holds the amount is not repeated (a second run changes nothing)."""
    for b in (ds.get("setup") or {}).get("budget") or []:
        ledger, head, stages, amount = b["ledger"], b["headOfAccount"], b["stages"], b["amount"]
        period = ds["fiscalYear"]
        line = (f"set-up budget {ledger} {period} head {head}: Rs {rupees(amount)} through {' > '.join(stages)}"
                + (" (ASSUMED: " + b["why"] + ")" if b.get("assumed") else ""))
        if dry:
            print("  " + line + " [plan]")
            continue
        q = urllib.parse.urlencode({"ledger": ledger, "period": period, "line": head, "holder": "-", "stage": stages[0]})
        held = [a for a in items_of(http("GET", f"{base}/ledger/by-ledger/accounts?{q}", tok))
                if float(a.get("total") or 0) * 100 >= amount]
        if held:
            print("  " + line + ": already released")
            continue

        def account(stage):
            return {"period": period, "line": head, "holder": "-", "stage": stage}

        steps = [("ALLOCATE", account(stages[0]), None)] + [
            ("DISTRIBUTE", account(stages[i - 1]), account(stages[i])) for i in range(1, len(stages))]
        for op, at, to in steps:
            ref = f"{ledger}/{period}/{head}/{op}/{at['stage']}" + (f">{to['stage']}" if to else "")
            body = {"operation": op, "account": at, "amount": rupees(amount), "reference": ref,
                    "auditRef": b["auditRef"], "fromCommitment": False, "fromEncumbrance": False, "holdIfShort": False}
            if to:
                body["target"] = to
            r = http("POST", f"{base}/ledger/by-ledger/operations?ledger={urllib.parse.quote(ledger)}", tok, body,
                     headers={"Idempotency-Key": f"presentation-{ds['dataSet']}-{ref}"})
            if not (r or {}).get("applied"):
                raise Refused("POST", f"{base}/ledger/by-ledger/operations", 200, f"{op} {ref} not applied: {r}")
        print("  " + line + ": released")
    return []


def setup(base, ds, dry):
    """The data set's set-up (payees, budget) with a tenant administrator's token (SANGAM_ADMIN_TOKEN, the token the
    seed and the preflight use). Without it nothing is set up and the load is refused: the cases would only fail."""
    if not (ds.get("payees") or (ds.get("setup") or {}).get("budget")):
        return []
    tok = os.environ.get("SANGAM_ADMIN_TOKEN")
    if not dry and not tok:
        print("  set-up refused: SANGAM_ADMIN_TOKEN is not set")
        return ["set-up (SANGAM_ADMIN_TOKEN is not set)"]
    try:
        return setup_payees(base, ds, tok, dry) + setup_budget(base, ds, tok, dry)
    except Refused as e:
        print(f"  set-up stopped: {e}")
        return [f"set-up ({e})"]


def main():
    dry = "--dry-run" in sys.argv[1:]
    sets = sys.argv[sys.argv.index("--data") + 1:sys.argv.index("--data") + 2] if "--data" in sys.argv else [DATA]
    base = (os.environ.get("SANGAM_API_BASE") or "").rstrip("/")
    settle = float(os.environ.get("SANGAM_PRESENTATION_SETTLE_SECONDS") or 30)
    if not dry and not base:
        raise SystemExit("SANGAM_API_BASE is not set")
    wrong = []
    for path in sets or sorted(glob.glob(os.path.join(HERE, "data", "*.json"))):
        with open(path, encoding="utf-8") as f:
            ds = json.load(f)
        print(f"data set {ds['dataSet']} {ds['version']}: tenant {ds['tenant']}, {len(ds['cases'])} cases"
              + (" (dry run: no network)" if dry else ""))
        wrong += setup(base, ds, dry)
        for case in ds["cases"]:
            try:
                if not run_case(base, case, ds["people"], dry, settle):
                    wrong.append(case["id"])
            except Refused as e:
                print(f"    stopped: {e}")
                wrong.append(case["id"])
    if wrong:
        raise SystemExit(f"{len(wrong)} case(s) not at their target: {', '.join(wrong)}")


if __name__ == "__main__":
    main()

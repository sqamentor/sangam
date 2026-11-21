# SPDX-License-Identifier: Apache-2.0
"""Loads a presentation data set (deploy/presentation/data/*.json) into a running Sangam through its REST API.
DEMO-DATA lane 2026-11-19; reworked against a production-mode server by DEMO-LOADER (EV-M-0932, 2026-11-20).
Run after seed_presentation.py (tenant, seats, postings exist; every person signed in once).

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
the data set declares: COMPLETED (ended, in case state CLOSED), RUNNING (open at stopAt), WAITING (open, no task for anyone, in the
declared case state) or PAYMENT_HELD (open, no task for anyone, past the PAO). The exit code is 1 when a case is not
at its target. A task at a seat that none of the data set's people holds is seen by nobody here: the case then shows
as not at its target. Nothing here pays anything: the tenant's payment
connector must be the sandbox or a held rail (money paths are not live for IFMS, SD-553).

Tokens never come from this repository: SANGAM_PRESENTATION_TOKEN_<USERNAME> (upper case, '.' and '-' as '_') holds
a bearer token of that user, obtained by signing in. Nothing secret is printed.

A reload after a partial load. The server has no rule that a business key is unique (a second start with the same key
is a second instance), so this script is the guard: a case whose business key already exists, open or ended, is never
started again. It is continued from where it stands (its open tasks are completed, an outside answer is sent only
where the case waits for it), so a run that stopped half way (a killed process, a dropped connection, an expired
token) is resumed by the next run, and a run on a finished load changes nothing. What the next run finds is judged
like any case: an instance that ended without reaching CLOSED (rejected, cancelled) is NOT AT TARGET, and so is an
open one on which an incident is recorded (the server's list answers with every instance that ever had one, so a case
whose incident an operator has resolved is still shown to a person to look at). Two instances on one key are reported
and neither is touched. The script never cancels or removes anything: what to do with an instance that cannot reach
its target any more is an operator's decision.

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


def http(method, url, token, body=None, seat=None):
    h = {"Authorization": "Bearer " + token, "Accept": "application/json"}
    if seat:
        h["X-Sangam-Seat"] = seat
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


def tree(base, bk, tok, incidents=False):
    """Every process instance that carries the business key, ended ones included: the case's own instance and the
    processes it called (a call passes the key on). With incidents=True only those on which an incident is recorded."""
    q = {"businessKey": bk, "size": 100}
    if incidents:
        q["withIncidents"] = "true"
    return items_of(http("GET", f"{base}/process-instances?{urllib.parse.urlencode(q)}", tok))


def own(found):
    """The instances no other instance called. One is the case; none means it was never started. The server has no
    rule that a business key is unique, so more than one is possible, and then nobody can say which is the case."""
    return [i for i in found if not i.get("superProcessInstanceId")]


def instance(base, bk, tok):
    """The case's own process instance (the one no other instance called), or None."""
    roots = own(tree(base, bk, tok))
    # the list leaves activeActivityIds empty; the instance itself names where it stands
    return http("GET", f"{base}/process-instances/{urllib.parse.quote(roots[0]['id'])}", tok) if roots else None


def waits_where(base, bk, tok):
    """The activities the case stands at, over its own instance and the processes it called."""
    out = set()
    for i in tree(base, bk, tok):
        if not i.get("ended"):
            one = http("GET", f"{base}/process-instances/{urllib.parse.quote(i['id'])}", tok) or {}
            out.update(one.get("activeActivityIds") or [])
    return out


def at_target(case, inst, waits_at, open_keys, case_state, incident=False):
    """The rules of PresentationDataSetH2Test, as far as the REST API shows them to the person who started the case."""
    target = case["target"]
    if inst is None:
        return False
    if target == "COMPLETED":    # ended is not enough: a rejected or cancelled case has ended too
        return bool(inst.get("ended")) and case_state == "CLOSED"
    if target == "RUNNING":
        return not inst.get("ended") and waits_at == case.get("stopAt")
    if inst.get("ended") or open_keys or incident:
        return False             # a case that stopped at a failed step is open with no task too; it waits for nothing
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
    roots = own(tree(base, bk, tok))
    if len(roots) > 1:
        print(f"    {len(roots)} instances carry this business key: not continued and nothing started (one case is one "
              "instance; which of them is the case is an operator's decision)")
        return False
    if not roots:
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
            if not sent and case.get("events"):
                # An outside answer the data set declares (the citizen's rating) is sent where the case waits for it
                # ('when', the rule of PresentationDataSetH2Test) and nowhere else: a case that stands somewhere
                # else has no use for it. An event without 'when' is sent once the case has been quiet for a while.
                where = waits_where(base, bk, tok)
                if all(ev["when"] in where if ev.get("when") else quiet >= min(settle, 6) for ev in case["events"]):
                    for ev in case["events"]:
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
    case_state, incident = None, False
    if inst is not None:
        # an ended instance answers with the variables it ended with (the server reads them from its history)
        case_state = (http("GET", f"{base}/process-instances/{urllib.parse.quote(inst['id'])}/variables", tok) or {}).get("caseState")
        if isinstance(case_state, dict):
            case_state = case_state.get("value")
        incident = not inst.get("ended") and bool(tree(base, bk, tok, incidents=True))
    ok = at_target(case, inst, waits_at, open_keys, case_state, incident)
    state = "no instance" if inst is None else ("ended" if inst.get("ended") else "open at " + ", ".join(
        inst.get("activeActivityIds") or ["(nothing active)"]))
    print(f"    {'OK' if ok else 'NOT AT TARGET'}: {state}" + (f", case state {case_state}" if case_state else "")
          + ("; an incident is recorded on the case (a step failed: the operator's view names it)" if incident else "")
          + (f"; open tasks {', '.join(open_keys)}" if open_keys else ""))
    return ok


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
        for case in ds["cases"]:
            try:
                if not run_case(base, case, ds["people"], dry, settle):
                    wrong.append(case["id"])
            except Refused as e:
                print(f"    stopped: {e}")
                wrong.append(case["id"])
            except OSError as e:     # the connection dropped or timed out: the next run continues from here
                print(f"    stopped: no answer from the server ({type(e).__name__}); run again to continue")
                wrong.append(case["id"])
    if wrong:
        raise SystemExit(f"{len(wrong)} case(s) not at their target: {', '.join(wrong)}")


if __name__ == "__main__":
    main()

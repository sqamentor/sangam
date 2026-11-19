# SPDX-License-Identifier: Apache-2.0
"""Seeds the presentation seat holders once; safe to run again (idempotent). R8-present.

The users, roles and seats are the lists in deploy/compose/keycloak/generate_realm.py (PRESENTATION_*), so the local
realm files and a hosted Keycloak get the same people. Two parts, each skipped when its settings are absent:

  1. Keycloak (admin REST API): the realm roles exist; each user exists with name, e-mail, attributes tenant, seat,
     office; its password is (re)set from SANGAM_PRESENTATION_PASSWORD_<USERNAME>; its realm roles are added.
     A user whose password variable is empty is created without a password and reported.
  2. Sangam (REST API, a tenant administrator's bearer token): the org unit, roles and seats of the IFMS pack seed
     (the server does not install pack seeds), then every seat-holding user who has signed in once (JIT person)
     gets a SUBSTANTIVE assignment to its seat from today, unless it holds it already.

Nothing is printed that is secret. Standard library only; no outbound call except to the two URLs given.

  python -I deploy/presentation/seed_presentation.py --dry-run          # plan only, no network
  python -I deploy/presentation/seed_presentation.py                    # apply

Environment (see deploy/presentation/README.md):
  KEYCLOAK_URL, KEYCLOAK_REALM, KEYCLOAK_PLATFORM_REALM, KEYCLOAK_ADMIN_REALM (default master),
  KEYCLOAK_ADMIN_CLIENT_ID (default admin-cli), KEYCLOAK_ADMIN_USER, KEYCLOAK_ADMIN_PASSWORD
  SANGAM_API_BASE (e.g. https://host/sangam/api/v1), SANGAM_ADMIN_TOKEN
  SANGAM_PRESENTATION_TENANT (default GOI.FIN), SANGAM_PRESENTATION_PASSWORD_<USERNAME>
"""
import datetime
import importlib.util
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
GEN = os.path.join(os.path.dirname(HERE), "compose", "keycloak", "generate_realm.py")
SEED = os.path.join(os.path.dirname(os.path.dirname(HERE)), "sangam-packs", "src", "main", "resources", "packs",
                    "ifms-gpf", "seed.json")


def load_lists():
    spec = importlib.util.spec_from_file_location("generate_realm", GEN)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def http(method, url, token=None, body=None, form=None, headers=None):
    h = dict(headers or {})
    data = None
    if token:
        h["Authorization"] = "Bearer " + token
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        h["Content-Type"] = "application/json"
    if form is not None:
        data = urllib.parse.urlencode(form).encode("utf-8")
        h["Content-Type"] = "application/x-www-form-urlencoded"
    req = urllib.request.Request(url, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=30) as res:
            raw = res.read()
            return res.status, (json.loads(raw) if raw and raw[:1] in (b"{", b"[") else None)
    except urllib.error.HTTPError as e:
        raw = e.read()
        detail = raw.decode("utf-8", "replace")[:300]
        raise SystemExit(f"{method} {url.split('?')[0]} -> {e.code}: {detail}") from None


class Keycloak:
    def __init__(self, base, admin_realm, client_id, user, password):
        self.base = base.rstrip("/")
        _, tok = http("POST", f"{self.base}/realms/{admin_realm}/protocol/openid-connect/token",
                      form={"grant_type": "password", "client_id": client_id, "username": user, "password": password})
        self.token = tok["access_token"]

    def a(self, realm, path):
        return f"{self.base}/admin/realms/{urllib.parse.quote(realm)}{path}"

    def ensure_role(self, realm, name, description):
        try:
            http("GET", self.a(realm, f"/roles/{urllib.parse.quote(name)}"), self.token)
            return "exists"
        except SystemExit as e:
            if "-> 404" not in str(e):
                raise
        http("POST", self.a(realm, "/roles"), self.token, {"name": name, "description": description})
        return "created"

    def ensure_user(self, realm, rep, password, roles):
        _, found = http("GET", self.a(realm, "/users?exact=true&username=" + urllib.parse.quote(rep["username"])), self.token)
        if found:
            uid, state = found[0]["id"], "updated"
            http("PUT", self.a(realm, f"/users/{uid}"), self.token, rep)
        else:
            http("POST", self.a(realm, "/users"), self.token, rep)
            _, found = http("GET", self.a(realm, "/users?exact=true&username=" + urllib.parse.quote(rep["username"])), self.token)
            uid, state = found[0]["id"], "created"
        if password:
            http("PUT", self.a(realm, f"/users/{uid}/reset-password"), self.token,
                 {"type": "password", "value": password, "temporary": False})
        reps = [http("GET", self.a(realm, f"/roles/{urllib.parse.quote(r)}"), self.token)[1] for r in roles]
        http("POST", self.a(realm, f"/users/{uid}/role-mappings/realm"), self.token, reps)   # adding twice is a no-op
        return state


def user_rep(entry, tenant, office):
    uname, first, last, _roles, seats = entry[:5]
    attrs = {"tenant": [tenant], "seat": list(seats)}
    if seats:
        attrs["office"] = [office]
    return {"username": uname, "enabled": True, "emailVerified": True, "email": f"{uname}@ifms-jaipur.invalid",
            "firstName": first, "lastName": last, "attributes": attrs}


def items_of(body):
    return body if isinstance(body, list) else (body or {}).get("items", [])


def idem(*parts):
    return {"Idempotency-Key": str(uuid.uuid5(uuid.NAMESPACE_URL, "sangam-presentation/" + "/".join(parts)))}


# The 409 codes that mean "already there" (OrgUnitService UNIT_EXISTS, SeatService SEAT_EXISTS). Every other 409
# (UNKNOWN_LEVEL, UNKNOWN_UNIT, UNKNOWN_SEAT, ...) is a real refusal and stops the seed (EV-M-0923: a blanket 409
# reported "exists" for an org unit that was never created).
EXISTS_CODES = ("UNIT_EXISTS", "SEAT_EXISTS")


def create_if_absent(what, method, url, tok, body, headers):
    """POST/PUT; a 409 with an "already there" code counts as present. Other errors stop the seed."""
    try:
        http(method, url, tok, body, headers=headers)
        print(f"  {what}: created")
    except SystemExit as e:
        if "-> 409" not in str(e) or not any(f'"code":"{c}"' in str(e) for c in EXISTS_CODES):
            raise
        print(f"  {what}: exists")


def seats_in_order(seed):
    """The seed's seats, each after the seat it reports to (as IfmsPacksIT.installSeatsAndHolders creates them)."""
    pending, made, out = list(seed["seats"]), set(), []
    ids = {s["id"] for s in seed["seats"]}
    while pending:
        ready = [s for s in pending if not s.get("reportsTo") or s["reportsTo"] in made or s["reportsTo"] not in ids]
        if not ready:
            raise SystemExit("seat hierarchy has a cycle: " + ", ".join(s["id"] for s in pending))
        for s in ready:
            out.append(s)
            made.add(s["id"])
            pending.remove(s)
    return out


def seed_sangam(base, tok, g, tenant, office, today):
    """Org unit, roles and seats of the IFMS pack seed (the server does not install pack seeds: PackDeployer loads
    them only into the simulator seat directory), then one SUBSTANTIVE posting per presentation seat holder.

    A posting needs the person to exist (PostingService.requirePerson). Persons come from the first sign-in (JIT,
    sangam.security.jit-provisioning-tenants, person id = the preferred_username), so a holder who has not signed in
    yet is reported and the seed is run again afterwards."""
    with open(SEED, encoding="utf-8") as f:
        seed = json.load(f)
    unit = next(o for o in seed["offices"] if o["id"] == office)
    create_if_absent(f"org unit {office}", "POST", f"{base}/admin/org-units", tok,
                     {"id": office, "code": office, "name": "Jaipur", "level": "OFFICE",
                      "parentId": unit.get("parent") or None}, idem(tenant, "unit", office))
    # PUT /admin/roles/by-role-id?roleId= (IdentityAdminController.defineRole, decision 393: a role id may carry ':',
    # so it is a query parameter, never a path segment). Defining a role again replaces it with the same content.
    for r in seed["roles"]:
        http("PUT", f"{base}/admin/roles/by-role-id?" + urllib.parse.urlencode({"roleId": r["code"]}), tok,
             {"id": r["code"], "name": r["name"]["en"]}, headers=idem(tenant, "role", r["code"]))
    print(f"  roles: {len(seed['roles'])} defined")
    for s in seats_in_order(seed):
        reports = s.get("reportsTo") if s.get("reportsTo") in {x["id"] for x in seed["seats"]} else None
        create_if_absent(f"seat {s['id']}", "POST", f"{base}/admin/seats", tok,
                         {"id": s["id"], "code": s["id"], "designation": s["role"], "name": s["name"]["en"],
                          "orgUnitId": s["office"], "reportsToSeatId": reports, "roles": [s["role"]]},
                         idem(tenant, "seat", s["id"]))
    waiting = []
    for e in g.PRESENTATION_USERS:
        if not e[4]:
            continue
        _, people = http("GET", f"{base}/admin/users?" + urllib.parse.urlencode({"q": e[0]}), tok)
        if not any(p.get("id") == e[0] for p in items_of(people)):
            waiting.append(e[0])
            continue
        for seat in e[4]:
            q = urllib.parse.urlencode({"seatId": seat, "kind": "SUBSTANTIVE", "activeOn": today})
            _, held = http("GET", f"{base}/admin/assignments?{q}", tok)
            holders = {a.get("personId") for a in items_of(held)}
            if e[0] in holders:
                print(f"  assignment {e[0]} -> {seat}: already active")
                continue
            if holders:   # SEAT_OCCUPIED: a regular seat has one substantive holder at a time
                print(f"  assignment {e[0]} -> {seat}: seat held by {', '.join(sorted(holders))}; end that posting first")
                continue
            http("POST", f"{base}/admin/assignments", tok,
                 {"seatId": seat, "personId": e[0], "kind": "SUBSTANTIVE", "validFrom": today,
                  "orderRef": "Office order: presentation posting"}, headers=idem(tenant, e[0], seat, today))
            print(f"  assignment {e[0]} -> {seat}: created")
    if waiting:
        print("  not yet signed in (no person yet): " + ", ".join(waiting) + "; sign in once as each, then run again")


def main():
    dry = "--dry-run" in sys.argv[1:]
    g = load_lists()
    tenant, office = g.PRESENTATION_TENANT, g.PRESENTATION_OFFICE
    env = os.environ.get
    today = datetime.date.today().isoformat()
    plan = [(env("KEYCLOAK_REALM", "sangam"), g.PRESENTATION_USERS),
            (env("KEYCLOAK_PLATFORM_REALM", "sangam-platform"), g.PRESENTATION_PLATFORM_USERS)]
    kc_ready = all(env(k) for k in ("KEYCLOAK_URL", "KEYCLOAK_ADMIN_USER", "KEYCLOAK_ADMIN_PASSWORD"))
    api_ready = all(env(k) for k in ("SANGAM_API_BASE", "SANGAM_ADMIN_TOKEN"))

    print(f"tenant {tenant}, office {office}, today {today}" + (" (dry run: no network)" if dry else ""))
    for realm, users in plan:
        for e in users:
            pw = "set" if env(g.presentation_env_name(e[0])) else "MISSING"
            print(f"  keycloak {realm}: user {e[0]} roles {','.join(e[3])} seats {','.join(e[4]) or '-'} password {pw}")
    with open(SEED, encoding="utf-8") as f:
        order = [s["id"] for s in seats_in_order(json.load(f))]
    print(f"  sangam: org unit {office}, pack roles, seats in order {', '.join(order)} (409 = already there)")
    for e in g.PRESENTATION_USERS:
        for seat in e[4]:
            print(f"  sangam: assignment {e[0]} -> {seat} SUBSTANTIVE from {today} (if none active)")
    if dry:
        return

    if kc_ready:
        kc = Keycloak(env("KEYCLOAK_URL"), env("KEYCLOAK_ADMIN_REALM", "master"), env("KEYCLOAK_ADMIN_CLIENT_ID", "admin-cli"),
                      env("KEYCLOAK_ADMIN_USER"), env("KEYCLOAK_ADMIN_PASSWORD"))
        tenant_realm = plan[0][0]
        for n, d in g.PRESENTATION_ROLES + [("sangam-user", "Every interactive user")]:
            print(f"  role {n}: {kc.ensure_role(tenant_realm, n, d)}")
        for realm, users in plan[1:]:   # the platform realm: its users' roles (sangam-admin, sangam-user)
            for n in sorted({r for e in users for r in e[3]}):
                print(f"  role {realm}/{n}: {kc.ensure_role(realm, n, 'Sangam platform role')}")
        for realm, users in plan:
            for e in users:
                state = kc.ensure_user(realm, user_rep(e, tenant, office), env(g.presentation_env_name(e[0])), e[3])
                print(f"  user {realm}/{e[0]}: {state}")
    else:
        print("keycloak: skipped (KEYCLOAK_URL, KEYCLOAK_ADMIN_USER, KEYCLOAK_ADMIN_PASSWORD not all set)")

    if api_ready:
        seed_sangam(env("SANGAM_API_BASE").rstrip("/"), env("SANGAM_ADMIN_TOKEN"), g, tenant, office, today)
    else:
        print("sangam: skipped (SANGAM_API_BASE, SANGAM_ADMIN_TOKEN not both set)")


if __name__ == "__main__":
    main()

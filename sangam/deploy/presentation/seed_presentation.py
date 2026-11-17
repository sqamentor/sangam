# SPDX-License-Identifier: Apache-2.0
"""Seeds the presentation seat holders once; safe to run again (idempotent). R8-present.

The users, roles and seats are the lists in deploy/compose/keycloak/generate_realm.py (PRESENTATION_*), so the local
realm files and a hosted Keycloak get the same people. Two parts, each skipped when its settings are absent:

  1. Keycloak (admin REST API): the realm roles exist; each user exists with name, e-mail, attributes tenant, seat,
     office; its password is (re)set from SANGAM_PRESENTATION_PASSWORD_<USERNAME>; its realm roles are added.
     A user whose password variable is empty is created without a password and reported.
  2. Sangam (REST API, a tenant administrator's bearer token): every seat-holding user gets a SUBSTANTIVE assignment
     to its seat from today (POST /admin/assignments), unless one is already active today.

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
    uname, first, last, _roles, seats = entry
    attrs = {"tenant": [tenant], "seat": list(seats)}
    if seats:
        attrs["office"] = [office]
    return {"username": uname, "enabled": True, "emailVerified": True, "email": f"{uname}@sangam.test",
            "firstName": first, "lastName": last, "attributes": attrs}


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
        base, tok = env("SANGAM_API_BASE").rstrip("/"), env("SANGAM_ADMIN_TOKEN")
        for e in g.PRESENTATION_USERS:
            for seat in e[4]:
                q = urllib.parse.urlencode({"personId": e[0], "seatId": seat, "activeOn": today})
                _, held = http("GET", f"{base}/admin/assignments?{q}", tok)
                items = held if isinstance(held, list) else (held or {}).get("items", [])
                if items:
                    print(f"  assignment {e[0]} -> {seat}: already active")
                    continue
                key = str(uuid.uuid5(uuid.NAMESPACE_URL, f"sangam-presentation/{tenant}/{e[0]}/{seat}/{today}"))
                http("POST", f"{base}/admin/assignments", tok,
                     {"seatId": seat, "personId": e[0], "kind": "SUBSTANTIVE", "validFrom": today,
                      "orderRef": "Presentation posting"}, headers={"Idempotency-Key": key})
                print(f"  assignment {e[0]} -> {seat}: created")
    else:
        print("sangam: skipped (SANGAM_API_BASE, SANGAM_ADMIN_TOKEN not both set)")


if __name__ == "__main__":
    main()

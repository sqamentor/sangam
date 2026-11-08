# SPDX-License-Identifier: Apache-2.0
"""Generates realm/sangam-realm.json (development Keycloak realm) and the Grafana overview dashboard.

Run from anywhere:  python -I deploy/compose/keycloak/generate_realm.py
The generated files are committed; edit this script, not the JSON.

Realm content:
  * platform roles (admin, operator, auditor, model lifecycle MODEL_REVIEWER / MODEL_APPROVER, worker);
  * pack roles for P1-P4 (SANGAM.md IX.4) and groups /tenants/<tenant>/<office>/<pack>;
  * test seat holders per pack: user attributes tenant, seat (multi-valued seat ids), office, additionalCharge;
  * protocol mappers on each client mapping them to the access-token claims tenant, seats, office, additional_charge,
    roles, groups and audience sangam-server (ARCHITECTURE.md 4: tenant from claim `tenant`);
  * clients sangam-ui (public, PKCE S256), sangam-server (audience), sangam-worker (client credentials),
    sangam-cli (password grant, dev only).
All passwords and secrets are DEVELOPMENT-ONLY test values.
"""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
COMPOSE = os.path.dirname(HERE)

TEST_PW = "Sangam@Test-2026"   # test seat password, development realm only


def grafana_dashboard():
    def ts(pid, title, exprs, unit, x, y):
        return {"id": pid, "type": "timeseries", "title": title,
                "datasource": {"type": "prometheus", "uid": "prometheus"},
                "gridPos": {"h": 8, "w": 12, "x": x, "y": y},
                "fieldConfig": {"defaults": {"unit": unit}, "overrides": []},
                "options": {"legend": {"displayMode": "list", "placement": "bottom"}},
                "targets": [{"refId": chr(65 + i), "expr": e, "legendFormat": l}
                            for i, (e, l) in enumerate(exprs)]}
    j = 'job="sangam-server"'
    panels = [
        ts(1, "HTTP requests / s by status",
           [(f'sum by (status) (rate(http_server_requests_seconds_count{{{j}}}[5m]))', "{{status}}")], "reqps", 0, 0),
        ts(2, "HTTP p95 latency by uri",
           [(f'histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket{{{j}}}[5m])))', "{{uri}}")], "s", 12, 0),
        ts(3, "JVM heap used",
           [(f'sum by (instance) (jvm_memory_used_bytes{{{j},area="heap"}})', "{{instance}}")], "bytes", 0, 8),
        ts(4, "DB pool (Hikari) active / pending",
           [(f'sum(hikaricp_connections_active{{{j}}})', "active"),
            (f'sum(hikaricp_connections_pending{{{j}}})', "pending")], "short", 12, 8),
        ts(5, "Outbox pending / oldest age",
           [(f'sum(sangam_outbox_pending{{{j}}})', "pending"),
            (f'max(sangam_outbox_oldest_age_seconds{{{j}}})', "oldest age s")], "short", 0, 16),
        ts(6, "Reconciliation queue by state (UNKNOWN_OUTCOME ...)",
           [(f'sum by (state) (sangam_reconciliation_items{{{j}}})', "{{state}}")], "short", 12, 16),
        ts(7, "Job executor: acquired / failed / open incidents",
           [(f'sum(rate(sangam_jobs_acquired_total{{{j}}}[5m]))', "acquired/s"),
            (f'sum(rate(sangam_jobs_failed_total{{{j}}}[5m]))', "failed/s"),
            (f'sum(sangam_incidents_open{{{j}}})', "open incidents")], "short", 0, 24),
        ts(8, "Stale-lease rejections (409) and lock extensions",
           [(f'sum(rate(sangam_lease_stale_total{{{j}}}[5m]))', "stale/s"),
            (f'sum(rate(sangam_lease_extended_total{{{j}}}[5m]))', "extended/s")], "short", 12, 24),
    ]
    return {"uid": "sangam-overview", "title": "Sangam overview", "schemaVersion": 39, "version": 1,
            "editable": False, "time": {"from": "now-1h", "to": "now"}, "refresh": "30s",
            "tags": ["sangam"], "panels": panels, "templating": {"list": []}, "annotations": {"list": []}}


PLATFORM_ROLES = [
    ("sangam-admin", "Platform administrator (tenants, policies, seats)"),
    ("sangam-operator", "Operations: incidents, jobs, outbox, reconciliation console"),
    ("sangam-auditor", "Read audit timelines and verify audit chains (READ_AUDIT)"),
    ("sangam-modeller", "Author BPMN/DMN/forms packages"),
    ("MODEL_REVIEWER", "Review a model package (reviewer != author, by person and seat)"),
    ("MODEL_APPROVER", "Approve a model package for deployment"),
    ("sangam-deployer", "Deploy approved packages"),
    ("sangam-worker", "External-task worker (worker API /api/v1/work)"),
    ("sangam-user", "Every interactive user"),
]

PACKS = {
    "P1": ("Bill / payment approval", [
        ("p1-initiator", "Initiator (DDO office)"), ("p1-checker", "Financial checker"),
        ("p1-approver", "Final approver (by amount band)"), ("p1-treasury", "Treasury / payment system operator")]),
    "P2": ("File movement (e-Office style)", [
        ("p2-dealing-assistant", "Dealing assistant"), ("p2-section-officer", "Section officer"),
        ("p2-under-secretary", "Under secretary"), ("p2-deputy-secretary", "Deputy secretary"),
        ("p2-competent-authority", "Competent authority")]),
    "P3": ("Grant / scheme sanction", [
        ("p3-applicant", "Applicant or field office"), ("p3-verifier", "Verifier"),
        ("p3-sanctioning-authority", "Sanctioning authority"), ("p3-finance", "Finance")]),
    "P4": ("Licence / permit service", [
        ("p4-citizen", "Citizen / applicant"), ("p4-scrutiny-clerk", "Scrutiny clerk"),
        ("p4-inspector", "Inspector"), ("p4-approving-officer", "Approving officer")]),
}

TENANT_OF = {"P1": ("dept-finance", "DDO-001"), "P2": ("dept-finance", "SECRETARIAT-FIN"),
             "P3": ("dept-social-welfare", "DIST-SW-01"), "P4": ("dept-industries", "DIC-01")}

# (username, role, seat code, first name, last name). Two holders where SoD needs distinct persons/seats
# (IX.5 rule 5: initiator != checker != approver, by person and by seat).
SEAT_PLAN = {
    "P1": [("p1.initiator1", "p1-initiator", "INIT-1", "Asha", "Initiator"),
           ("p1.initiator2", "p1-initiator", "INIT-2", "Ravi", "Initiator"),
           ("p1.checker1", "p1-checker", "CHK-1", "Meena", "Checker"),
           ("p1.checker2", "p1-checker", "CHK-2", "Arjun", "Checker"),
           ("p1.approver1", "p1-approver", "APR-BAND1", "Sunita", "Approver"),
           ("p1.approver2", "p1-approver", "APR-BAND2", "Vikram", "Approver"),
           ("p1.treasury1", "p1-treasury", "TRSY-1", "Kiran", "Treasury")],
    "P2": [("p2.da1", "p2-dealing-assistant", "DA-1", "Pooja", "DealingAsst"),
           ("p2.so1", "p2-section-officer", "SO-1", "Rahul", "SectionOfficer"),
           ("p2.so2", "p2-section-officer", "SO-2", "Neha", "SectionOfficer"),
           ("p2.us1", "p2-under-secretary", "US-1", "Anil", "UnderSecy"),
           ("p2.ds1", "p2-deputy-secretary", "DS-1", "Geeta", "DeputySecy"),
           ("p2.ca1", "p2-competent-authority", "CA-1", "Rajesh", "CompetentAuth")],
    "P3": [("p3.applicant1", "p3-applicant", "APPL-1", "Lata", "Applicant"),
           ("p3.verifier1", "p3-verifier", "VER-1", "Mohan", "Verifier"),
           ("p3.verifier2", "p3-verifier", "VER-2", "Farah", "Verifier"),
           ("p3.sanction1", "p3-sanctioning-authority", "SANC-1", "Deepak", "SanctionAuth"),
           ("p3.finance1", "p3-finance", "FIN-1", "Usha", "Finance")],
    "P4": [("p4.citizen1", "p4-citizen", "CIT-1", "Imran", "Citizen"),
           ("p4.clerk1", "p4-scrutiny-clerk", "SCR-1", "Savita", "Clerk"),
           ("p4.inspector1", "p4-inspector", "INSP-1", "Harish", "Inspector"),
           ("p4.inspector2", "p4-inspector", "INSP-2", "Nisha", "Inspector"),
           ("p4.approver1", "p4-approving-officer", "APO-1", "Prakash", "ApprovingOfficer")],
}


def user(username, first, last, roles, groups, tenant, seats, extra=None):
    attrs = {"tenant": [tenant], "seat": list(seats)}
    if extra:
        attrs.update(extra)
    return {"username": username, "enabled": True, "emailVerified": True,
            "email": f"{username}@sangam.test", "firstName": first, "lastName": last,
            "attributes": attrs, "realmRoles": roles, "groups": groups,
            "credentials": [{"type": "password", "value": TEST_PW, "temporary": False}]}


def attr_mapper(name, attr, claim, multi):
    return {"name": name, "protocol": "openid-connect", "protocolMapper": "oidc-usermodel-attribute-mapper",
            "consentRequired": False,
            "config": {"user.attribute": attr, "claim.name": claim, "jsonType.label": "String",
                       "multivalued": "true" if multi else "false", "aggregate.attrs": "false",
                       "id.token.claim": "true", "access.token.claim": "true", "userinfo.token.claim": "true",
                       "introspection.token.claim": "true"}}


def realm():
    roles = [{"name": n, "description": d} for n, d in PLATFORM_ROLES]
    for p, (title, rs) in PACKS.items():
        roles += [{"name": n, "description": f"{p} {title}: {d}"} for n, d in rs]

    def pack_group(pack, office):
        pack_roles = [n for n, _ in PACKS[pack][1]]
        return {"name": pack, "attributes": {"pack": [pack], "office": [office],
                                             "packRoles": pack_roles},
                "realmRoles": ["sangam-user"], "subGroups": []}

    groups = [
        {"name": "tenants", "subGroups": [
            {"name": "dept-finance", "attributes": {"tenant": ["dept-finance"]}, "subGroups": [
                {"name": "DDO-001", "attributes": {"office": ["DDO-001"], "officeName": ["District Treasury Office"]},
                 "subGroups": [pack_group("P1", "DDO-001")]},
                {"name": "SECRETARIAT-FIN", "attributes": {"office": ["SECRETARIAT-FIN"],
                                                           "officeName": ["Finance Department Secretariat"]},
                 "subGroups": [pack_group("P2", "SECRETARIAT-FIN")]}]},
            {"name": "dept-social-welfare", "attributes": {"tenant": ["dept-social-welfare"]}, "subGroups": [
                {"name": "DIST-SW-01", "attributes": {"office": ["DIST-SW-01"],
                                                      "officeName": ["District Social Welfare Office"]},
                 "subGroups": [pack_group("P3", "DIST-SW-01")]}]},
            {"name": "dept-industries", "attributes": {"tenant": ["dept-industries"]}, "subGroups": [
                {"name": "DIC-01", "attributes": {"office": ["DIC-01"], "officeName": ["District Industries Centre"]},
                 "subGroups": [pack_group("P4", "DIC-01")]}]}]},
        {"name": "platform", "subGroups": [
            {"name": "admins", "realmRoles": ["sangam-admin", "sangam-user"], "subGroups": []},
            {"name": "operators", "realmRoles": ["sangam-operator", "sangam-user"], "subGroups": []},
            {"name": "auditors", "realmRoles": ["sangam-auditor", "sangam-user"], "subGroups": []},
            {"name": "model-lifecycle", "realmRoles": ["sangam-modeller", "sangam-user"], "subGroups": []}]},
    ]

    users = []
    for pack, entries in SEAT_PLAN.items():
        tenant, office = TENANT_OF[pack]
        gpath = f"/tenants/{tenant}/{office}/{pack}"
        for uname, role, seat_code, first, last in entries:
            users.append(user(uname, first, last, [role, "sangam-user"], [gpath], tenant,
                              [f"SEAT-{pack}-{office}-{seat_code}"], {"office": [office]}))
    # Additional charge / acted-for (IX.5 rule 4): p2.us1 also holds the DS-1 seat for November 2026. The token
    # lists seat ids; the server's effective-dated seat model decides whether the charge is active.
    for u in users:
        if u["username"] == "p2.us1":
            u["attributes"]["seat"].append("SEAT-P2-SECRETARIAT-FIN-DS-1")
            u["attributes"]["additionalCharge"] = ["SEAT-P2-SECRETARIAT-FIN-DS-1:2026-11-01/2026-11-30"]
    users += [
        user("admin.dev", "Platform", "Admin", ["sangam-admin", "sangam-user"], ["/platform/admins"],
             "dept-finance", []),
        user("operator.dev", "Ops", "Operator", ["sangam-operator", "sangam-user"], ["/platform/operators"],
             "dept-finance", []),
        user("auditor.dev", "Audit", "Reader", ["sangam-auditor", "sangam-user"], ["/platform/auditors"],
             "dept-finance", []),
        user("modeller.dev", "Model", "Author", ["sangam-modeller", "sangam-user"], ["/platform/model-lifecycle"],
             "dept-finance", ["SEAT-PLATFORM-MODEL-AUTHOR-1"]),
        user("reviewer.dev", "Model", "Reviewer", ["MODEL_REVIEWER", "sangam-user"], ["/platform/model-lifecycle"],
             "dept-finance", ["SEAT-PLATFORM-MODEL-REVIEW-1"]),
        user("approver.dev", "Model", "Approver", ["MODEL_APPROVER", "sangam-deployer", "sangam-user"],
             ["/platform/model-lifecycle"], "dept-finance", ["SEAT-PLATFORM-MODEL-APPROVE-1"]),
    ]
    users.append({"username": "service-account-sangam-worker", "enabled": True,
                  "serviceAccountClientId": "sangam-worker",
                  "attributes": {"tenant": ["dept-finance"]}, "realmRoles": ["sangam-worker"]})

    # Mappers are attached to each client (not a custom client scope): a realm import that lists clientScopes
    # replaces Keycloak's built-in default scopes.
    def sangam_mappers():
        return [
            attr_mapper("tenant", "tenant", "tenant", False),
            attr_mapper("seats", "seat", "seats", True),
            attr_mapper("office", "office", "office", False),
            attr_mapper("additional-charge", "additionalCharge", "additional_charge", True),
            {"name": "roles", "protocol": "openid-connect", "protocolMapper": "oidc-usermodel-realm-role-mapper",
             "config": {"claim.name": "roles", "jsonType.label": "String", "multivalued": "true",
                        "id.token.claim": "false", "access.token.claim": "true", "userinfo.token.claim": "true",
                        "introspection.token.claim": "true"}},
            {"name": "groups", "protocol": "openid-connect", "protocolMapper": "oidc-group-membership-mapper",
             "config": {"claim.name": "groups", "full.path": "true", "id.token.claim": "false",
                        "access.token.claim": "true", "userinfo.token.claim": "true",
                        "introspection.token.claim": "true"}},
            {"name": "audience-sangam-server", "protocol": "openid-connect", "protocolMapper": "oidc-audience-mapper",
             "config": {"included.client.audience": "sangam-server", "id.token.claim": "false",
                        "access.token.claim": "true", "introspection.token.claim": "true"}},
        ]

    clients = [
        {"clientId": "sangam-ui", "name": "Sangam web UI", "enabled": True, "publicClient": True,
         "protocol": "openid-connect", "standardFlowEnabled": True, "directAccessGrantsEnabled": False,
         "implicitFlowEnabled": False, "serviceAccountsEnabled": False,
         "redirectUris": ["http://localhost:3000/*", "http://localhost:5173/*"],
         "webOrigins": ["http://localhost:3000", "http://localhost:5173"],
         "attributes": {"pkce.code.challenge.method": "S256",
                        "post.logout.redirect.uris": "http://localhost:3000/*##http://localhost:5173/*"},
         "protocolMappers": sangam_mappers()},
        {"clientId": "sangam-server", "name": "Sangam API (resource server / audience)", "enabled": True,
         "publicClient": False, "bearerOnly": False, "standardFlowEnabled": False,
         "directAccessGrantsEnabled": False, "serviceAccountsEnabled": False, "protocol": "openid-connect",
         "secret": "sangam-server-dev-secret"},
        {"clientId": "sangam-worker", "name": "Sangam external-task workers (client credentials)", "enabled": True,
         "publicClient": False, "standardFlowEnabled": False, "directAccessGrantsEnabled": False,
         "serviceAccountsEnabled": True, "protocol": "openid-connect", "secret": "sangam-worker-dev-secret",
         "protocolMappers": sangam_mappers()},
        {"clientId": "sangam-cli", "name": "Developer CLI / API tests (password grant, dev realm only)",
         "enabled": True, "publicClient": True, "standardFlowEnabled": False, "directAccessGrantsEnabled": True,
         "protocol": "openid-connect", "protocolMappers": sangam_mappers()},
    ]

    perm_admin = {"view": ["admin", "user"], "edit": ["admin"]}
    user_profile = {
        "unmanagedAttributePolicy": "ADMIN_EDIT",
        "attributes": [
            {"name": "username", "displayName": "${username}", "permissions": perm_admin,
             "validations": {"length": {"min": 3, "max": 255}, "username-prohibited-characters": {},
                             "up-username-not-idn-homograph": {}}},
            {"name": "email", "displayName": "${email}", "permissions": perm_admin,
             "validations": {"email": {}, "length": {"max": 255}}},
            {"name": "firstName", "displayName": "${firstName}", "permissions": {"view": ["admin", "user"], "edit": ["admin", "user"]},
             "validations": {"length": {"max": 255}, "person-name-prohibited-characters": {}}},
            {"name": "lastName", "displayName": "${lastName}", "permissions": {"view": ["admin", "user"], "edit": ["admin", "user"]},
             "validations": {"length": {"max": 255}, "person-name-prohibited-characters": {}}},
            {"name": "tenant", "displayName": "Tenant", "permissions": perm_admin, "multivalued": False},
            {"name": "seat", "displayName": "Seats (posts)", "permissions": perm_admin, "multivalued": True},
            {"name": "office", "displayName": "Office", "permissions": perm_admin, "multivalued": False},
            {"name": "additionalCharge", "displayName": "Additional charge (seat:from/to)",
             "permissions": perm_admin, "multivalued": True},
        ],
        "groups": [{"name": "user-metadata", "displayHeader": "User metadata",
                    "displayDescription": "Attributes, which refer to user metadata"}],
    }

    return {
        "realm": "sangam", "displayName": "Sangam (development)", "enabled": True,
        "sslRequired": "external", "registrationAllowed": False, "loginWithEmailAllowed": True,
        "duplicateEmailsAllowed": False, "resetPasswordAllowed": False, "editUsernameAllowed": False,
        "bruteForceProtected": True, "failureFactor": 10,
        "accessTokenLifespan": 300, "ssoSessionIdleTimeout": 1800, "ssoSessionMaxLifespan": 36000,
        "internationalizationEnabled": True, "supportedLocales": ["en", "hi"], "defaultLocale": "en",
        "passwordPolicy": "length(12) and notUsername",
        "eventsEnabled": True, "adminEventsEnabled": True, "adminEventsDetailsEnabled": False,
        "roles": {"realm": roles},
        "groups": groups,
        "users": users,
        "clients": clients,
        "components": {"org.keycloak.userprofile.UserProfileProvider": [
            {"providerId": "declarative-user-profile", "subComponents": {},
             "config": {"kc.user.profile.config": [json.dumps(user_profile)]}}]},
    }


def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, indent=2, ensure_ascii=False)
        f.write("\n")


if __name__ == "__main__":
    r = realm()
    write(os.path.join(HERE, "realm", "sangam-realm.json"), r)
    write(os.path.join(COMPOSE, "observability", "grafana", "dashboards", "sangam-overview.json"), grafana_dashboard())
    print(f"realm: {len(r['users'])} users, {len(r['roles']['realm'])} roles; dashboard written")

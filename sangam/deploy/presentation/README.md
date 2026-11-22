# Sangam under `/sangam/` (presentation hosting)

R8-present. How Sangam runs behind a host that forwards `<public host>/sangam/*` to it with the path unchanged
(IFMS joint-ledger answer "How demo.ifms.dev is served": Cloudflare Access, then CloudFront, then an EC2 host).
IFMS builds the images from the pushed green sha and deploys them with its redeploy script. Nothing here hosts,
pushes or downloads anything.

## 1. Containers

| Container | Built from | Listens | Role |
|---|---|---|---|
| `sangam-front` | `deploy/presentation/Dockerfile` | 8080 | UI under `/sangam/`, `/sangam/config.json`, proxy of `/sangam/api/*` and `/sangam/engine-rest/*` to the server (prefix removed), `/sangam/health` |
| `sangam-server` | `deploy/docker/server.Dockerfile` (unchanged, default target) | 8080 app, 8081 management | REST API, engine, packs |

Build context is the `sangam/` directory at the green sha:

```
docker build -f deploy/presentation/Dockerfile -t sangam-front:<sha> .
docker build -f deploy/docker/server.Dockerfile -t sangam-server:<sha> .
```

Base images are the digest-pinned ones of `deploy/docker/ui.Dockerfile` and `server.Dockerfile`. Pulling them is a
download and needs the owner's approval. At run time neither container calls the internet: UI assets, fonts and
diagram assets are bundled (R56).

The server needs no context path: the front removes `/sangam` before proxying, and sends `X-Forwarded-Prefix: /sangam`.
Only `sangam-front` is exposed to the host's reverse proxy.

**Oracle driver.** The server image ships no Oracle JDBC driver (LIC-4). IFMS mounts its licensed `ojdbc11.jar` into
`/opt/sangam/drivers` (a volume), which is on `loader.path`.

## 2. Environment variables

All configuration is environment only. `presentation.env.example` lists every variable with placeholders; copy it to
`presentation.local.env` (git-ignored).

Front:

| Variable | Default | Meaning |
|---|---|---|
| `SANGAM_API_UPSTREAM` | `http://sangam-server:8080` | the server's app port |
| `SANGAM_PUBLIC_API_BASE` | `/sangam/api/v1` | API base the browser uses |
| `SANGAM_OIDC_ISSUER` | (none, required) | issuer URL, e.g. `https://<keycloak>/realms/oneifms` |
| `SANGAM_OIDC_CLIENT_ID` | `sangam-ui` | public PKCE client |
| `SANGAM_DEFAULT_LOCALE`, `SANGAM_LOCALES` | `en`, `en` | English only in 0.1.0 (Q-06) |
| `SANGAM_ENV_LABEL` | empty | sent to the browser as `environment` in `/sangam/config.json`; the UI shows it nowhere and reads it only to choose the sign-in mode (`dev` = development token, anything else OIDC); leave empty |
| `SANGAM_FONTS_MANIFEST` | `/sangam/fonts/site/fonts.json` | the optional site font manifest (`fonts.manifest` in `/sangam/config.json`, sangam-ui FONTS.md); the default is the path the UI asks for anyway; set it empty where no site fonts are installed, and the UI asks for none (otherwise a 404 at every page load) |
| `SANGAM_UI_IDLE_MINUTES` | `30` | UI idle sign-out, 1-480 |
| `SANGAM_CSP_CONNECT_EXTRA` | empty | extra `connect-src` origins, if any |

Server:

| Variable | Meaning |
|---|---|
| `SPRING_PROFILES_ACTIVE=oracle,oidc` | Oracle schema and OIDC resource server |
| `SANGAM_DB_URL`, `SANGAM_DB_USER`, `SANGAM_DB_PASSWORD` | Oracle schema; password from the secret store |
| `SANGAM_LIQUIBASE_MODE` | `migrate-only` once on an empty schema, then `validate` (decision 334) |
| `SANGAM_OIDC_ISSUER` | same issuer as the front |
| `SANGAM_SECURITY_OIDC_AUDIENCE` | `sangam-server` |
| `SANGAM_SECURITY_OIDC_TENANT_CLAIM`, `..._ROLES_CLAIM`, `..._SEATS_CLAIM` | `tenant`, `roles`, `seats` |
| `SANGAM_SECURITY_JITPROVISIONINGTENANTS` | `GOI.FIN`: a person is created at first sign-in, id = `preferred_username` |
| `SANGAM_SECURITY_PLATFORM_ISSUER` | issuer of a **separate** platform realm; empty means nobody is platform administrator |
| `SANGAM_PORT`, `SANGAM_MANAGEMENT_PORT` | 8080, 8081 |

## 3. Health

- Public: `GET /sangam/health` returns the server's readiness group (`/readyz` on the app port, Spring Boot probes).
- Container: `GET /healthz` on the front (nginx only), and `GET /livez`, `/readyz` on the server's port 8080, or
  `/actuator/health` on 8081.

## 4. Keycloak (what IFMS configures in realm `oneifms`)

The binding contract is the joint-ledger entry **[CONTRACT] SANGAM-HOSTING-KEYCLOAK**; this section is the same.

How Sangam reads a token (settled from code):

- The principal is the `preferred_username` claim (`sangam.security.claims.principal`, `SangamSecurityProperties`;
  `JwtPrincipalConverter` falls back to `sub` only when the claim is empty). It is the IdP subject that Sangam maps to a
  person (`PrincipalResolver.resolve` → `PersonService.findBySubject`).
- With JIT provisioning for the tenant (`SANGAM_SECURITY_JITPROVISIONINGTENANTS=GOI.FIN`), the first sign-in creates the
  person with person id = `preferred_username` (`PersonService.provisionJit`). Without it, sign-in is
  `401 NOT_LINKED`. Do not create these users by hand in Sangam first: a LOCAL user has no subject, and JIT then
  refuses the clashing person id (`USER_EXISTS`).
- Seats come from Sangam's postings, not from the token (`PrincipalResolver.enrich` reads `postings.seatsOf`). A
  `seats` claim is harmless but not used for task routing.
- Roles: `realm_access.roles`, `resource_access.<client>.roles` and a plain `roles` claim (`KeycloakClaimsMapper`),
  mirrored as IdP memberships. IFMS realm roles work as they are.

Claims needed in every access token for Sangam:

| Claim | Source in Keycloak | Required |
|---|---|---|
| `aud` contains `sangam-server` | audience mapper (included client audience `sangam-server`) | yes, else 401 |
| `tenant` | user attribute `tenant`, single value, `GOI.FIN` | yes, else 401 `TENANT_CLAIM_MISSING` |
| `preferred_username` | standard (profile scope) | yes: the person id |
| `roles` or `realm_access.roles` | realm roles | yes |
| `name`, `email` | standard | display name and e-mail of the JIT person |

Clients:

1. `sangam-ui`: public, standard flow only, PKCE S256, redirect URI `https://<public host>/sangam/*`, web origin
   `https://<public host>`, post-logout redirect `https://<public host>/sangam/*`; with the tenant and audience
   mappers.
2. `sangam-server`: bearer-only (or confidential with no flows), used only as the audience.

Realm roles: `sangam-user` (every user), the IFMS pack roles `DDO`, `HOO`, `DEALING_HAND`, `PAO`, `FA`, `BILL_DISTRIBUTOR`, `AAO`, `SECOND_SIGNATORY`, and
`sangam-tenant-admin`, `sangam-auditor`.

Platform administrator: SL-70 refuses a platform issuer equal to the tenant issuer, so a platform administrator needs a
second realm (for example `sangam-platform`, with the same `sangam-ui` client, the audience mapper, a `tenant` attribute
and the role `sangam-admin`). Without it, leave `SANGAM_SECURITY_PLATFORM_ISSUER` empty: the presentation then has no
platform administrator.

## 5. Seed (idempotent; run, sign in once as each person, run again)

```
python -I deploy/presentation/seed_presentation.py --dry-run   # plan, no network
python -I deploy/presentation/seed_presentation.py             # apply
```

1. Keycloak: roles, then each presentation user (created or updated; password from
   `SANGAM_PRESENTATION_PASSWORD_<USERNAME>`).
2. Sangam, with a tenant administrator's token: the tenant's org level types (`PUT /admin/org-levels`, levels
   `OFFICE`; idempotent, fixed once a unit exists), org unit `PAO-OFFICE` (`POST /admin/org-units`), the pack roles
   (`PUT /admin/roles/by-role-id?roleId=<code>`) and the ten seats of the IFMS pack seed (`ifms-gpf/seed.json`,
   `POST /admin/seats`, the pack role as designation), in reporting order. Only `409 UNIT_EXISTS` / `409 SEAT_EXISTS`
   mean already there; any other refusal stops the seed (EV-M-0923). The server does **not** install pack seeds itself: `PackDeployer` loads
   seats and holders only into the simulator seat directory (`InMemorySeatDirectory`), so the pack-seed holders
   (`gp.ddo-1` and so on) never reach a hosted database and do not collide with the presentation holders.
3. Sangam: one SUBSTANTIVE posting per seat holder, from today. A posting needs the person (`requirePerson`), which JIT
   creates at first sign-in, so the first run reports who has not signed in; sign in once as each, then run again.
   SUBSTANTIVE posts directly (`SecurityAdminBridge.createAssignment` → `PostingService.post`); there is no second
   approval. A regular seat takes one substantive holder at a time (`SEAT_OCCUPIED`); the seed reports an occupied seat
   instead of ending anyone's posting.

The user list is `PRESENTATION_USERS` in `deploy/compose/keycloak/generate_realm.py`; for a local Keycloak the same users
come from `generate_realm.py --presentation` (output in the git-ignored `realm-local/`).

Before the seed: the tenant `GOI.FIN` exists (`POST /api/v1/admin/tenants`) and the packs `sangam.core`, `gov.ifms.gpf`,
`gov.ifms.bill`, `gov.ifms.budget-allocation`, `gov.ifms.pao-chain` are deployed for it.

## 6. Who acts at each step (from the pack data)

People: the Jaipur personas of IFMS E-67, `PRESENTATION_USERS` in `generate_realm.py`; seat by seat in the joint
ledger [ANSWER] SANGAM-PERSONA-SEATS. Seats without a persona (PRAO-1, SECY-1) are created but
nobody holds them: IFMS E-67 has no person for either (requested in the joint ledger), and the flows that use them
(monthly accounts, SNA-SPARSH payment, year-end closing, budget formulation) are out of the presentation. Payee review
in the bill is at the dealing hand DH-1 (69ccc49e).


- GPF: started by the subscriber (no seat; the initiator seat is then `citizen`, `PackStartListener`), scrutiny at
  `DDO-1` (pack setting `scrutinySeat=DDO-1`), sanction by the role `HOO` (`ifms-gpf-authority.dmn` competentRole),
  i.e. `HOO-1`; a return goes to `DDO-1` (`ifms-gpf-return-target.dmn`).
- Bill, PAO chain: `BD-1` distributes, `DH-1` checks, `AAO-1` checks, `PAO-1` passes, objects or returns, `SS-1`
  second signature; payee confirmation at the dealing hand `DH-1` (69ccc49e); reply to an objection at the initiator seat.

## 7. What the demo sandbox tenant simulates (waiver W-5, register E-26)

`SANGAM_DEMO_SANDBOXTENANTS: GOI.FIN` is a per-tenant exception inside production mode. For GOI.FIN only, and for the
connector ids below only, the in-process simulator answers; every answer carries `sandbox=true`, every audit event of
the tenant carries `sandbox=true`, and an audit event that names one of these connectors carries `simulated=true` as
well. Every other tenant, and every other connector without a binding, is refused as before
(`CONNECTOR_NOT_CONFIGURED`). No outside address is configured for any of them.

| Connector id | What it is | Money | What the sandbox answers | Leaves the list when |
|---|---|---|---|---|
| `pfms.payment`, `ifms.payment`, `pfms.dbt`, `bank.transfer` | payment rails (W-5) | out | paid, reference `UTR…` | a real PFMS adapter is bound |
| `bank.statement` | the statement a payment is matched against (W-5) | none | shows the simulated payment | with the rails |
| verified payee (port, no connector id) | payee version on the payment date (W-5) | none | `SIM-PV-<employee id>` | a real verified-payee adapter is installed |
| `ifms.duplicateBill` | duplicate or similar claim check of a bill (E-26) | none | "no duplicate found" for every bill | a real IFMS endpoint answers the check |
| `docs.issue` | number of an issued document: payment advice, sanction order, rejection order (E-26) | none | `DOC/<8 characters>`; nothing is rendered | a document service is bound to `docs.issue` |

The answers of the two E-26 ids are data: `sangam-packs/src/main/resources/org/sangam/packs/sim/sandbox-answers.properties`.
In the UI the tenant shows the badge "Sandbox" on every page (from `GET /me`, `sandbox=true`); the audit events carry
`sandbox` and `simulated` in their details.

What the bill journey also needs on the tenant, none of it a connector (found by the sequence test
`DemoLoaderSequenceAgainstServerTest`, lines `DEMO-SEQUENCE`):

- budget for the account each bill is charged to (its head of account and its `ddoCode`, at the consuming stage of
  ledger BUDGET). A bill whose account holds less than the bill waits at "Held for budget". For this data set:
  head `2054-00-001-01-00-13`, Rs 1,97,050 for `JPR-DDO-0420` (BILL-0001..0005) and Rs 1,50,000 for `DDO-1` (GPF-0001);
- payees named by account (payee, `payeeAccount`, `ifsc`), not by a party id the registry did not issue, and each a
  party of the registry: a payee the registry does not hold is refused (`registry.party` answers
  `PAYEE_NOT_REGISTERED`, the bill is rejected `PAYEE_CHECK_NOT_CONFIRMED`, the refused check is audited
  `EXT_OP_FAILED` with that reason code). `load_presentation_data.py` registers the six payees of the data set
  (`payees`) by account through `POST /registry/parties` unless the registry already holds them;
- budget for BA-0001: `load_presentation_data.py` releases `setup.budget` through the ledger API (ALLOCATE, then
  DISTRIBUTE) with `SANGAM_ADMIN_TOKEN`. The amount is ASSUMED, not an IFMS figure:

  | Ledger | Head | Stages | Amount | Why |
  |---|---|---|---|---|
  | BUDGET, FY 2026-27 | `2054-00-097-01-00-01` | APPROPRIATION > ALLOCATION > RELEASE (holder `-`) | Rs 43,00,000 | BA-0001 distributes Rs 25,00,000 from the released stage; BA-0002 Rs 18,00,000 once sanctioned |

- not reached, named gaps: BILL-0003 (the sandbox pays, so the bill closes instead of standing at "payment held";
  SANGAM.md defines no payment-hold directive for a hosted load, so the loader sends none) and ADV-0001 ("IFMS
  ledger RECOVERY not available": the advance's bill asks ledger `RECOVERY`; it closes when the IFMS ledger connector
  exists, no RECOVERY ledger is defined here).

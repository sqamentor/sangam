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
| `SANGAM_ENV_LABEL` | empty | environment badge text; leave empty |
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
| `SANGAM_SECURITY_PLATFORM_ISSUER` | issuer of a **separate** platform realm; empty means nobody is platform administrator |
| `SANGAM_PORT`, `SANGAM_MANAGEMENT_PORT` | 8080, 8081 |

## 3. Health

- Public: `GET /sangam/health` returns the server's readiness group (`/readyz` on the app port, Spring Boot probes).
- Container: `GET /healthz` on the front (nginx only), and `GET /livez`, `/readyz` on the server's port 8080, or
  `/actuator/health` on 8081.

## 4. Keycloak (what IFMS configures in realm `oneifms`)

Sangam reads roles from `realm_access.roles`, from `resource_access.<client>.roles` and from a plain `roles` claim
(`KeycloakClaimsMapper`), so IFMS realm roles work as they are. It needs, in every access token for Sangam:

| Claim | Source in Keycloak | Required |
|---|---|---|
| `aud` contains `sangam-server` | audience mapper (included client audience `sangam-server`) | yes, else 401 |
| `tenant` | user attribute `tenant`, single value, e.g. `GOI.FIN` | yes, else 401 `TENANT_CLAIM_MISSING` |
| `seats` | user attribute `seat`, multi-valued, e.g. `DDO-1` | for seat holders |
| `office` | user attribute `office` | for seat holders |
| `roles` | realm roles mapper, claim `roles`, multi-valued | yes |
| `preferred_username` | standard | yes (the principal) |

Clients:

1. `sangam-ui`: public, standard flow, PKCE S256, redirect URI `https://<public host>/sangam/*`, web origin
   `https://<public host>`, post-logout redirect `https://<public host>/sangam/*`; with the mappers above.
2. `sangam-server`: confidential or bearer-only, used only as the audience.

Realm roles: `sangam-user` (every user), `DDO`, `HOO`, `PAO` (IFMS pack roles), `sangam-tenant-admin`,
`sangam-supervisor`. If `oneifms` already has roles with these names for IFMS itself, Sangam sees them too; that is
intended for `DDO`, `HOO` and `PAO`.

Platform administrator: SL-70 refuses a platform issuer equal to the tenant issuer, so a platform administrator needs a
second realm (for example `sangam-platform`, with the same `sangam-ui` client and `sangam-admin` role). Without it,
leave `SANGAM_SECURITY_PLATFORM_ISSUER` empty: the presentation then has no platform administrator.

## 5. Seed (once, idempotent)

```
python -I deploy/presentation/seed_presentation.py --dry-run   # plan, no network
python -I deploy/presentation/seed_presentation.py             # apply
```

It creates or updates the presentation users in Keycloak (password from `SANGAM_PRESENTATION_PASSWORD_<USERNAME>`)
and posts each seat holder to its seat in Sangam (`POST /admin/assignments`, skipped when one is already active). The
user list is `PRESENTATION_USERS` in `deploy/compose/keycloak/generate_realm.py`; for a local Keycloak the same users
come from `generate_realm.py --presentation` (output in the git-ignored `realm-local/`).

Before the seed: the tenant exists (`POST /api/v1/admin/tenants`) and the packs `sangam.core`, `gov.ifms.gpf`,
`gov.ifms.bill`, `gov.ifms.budget-allocation`, `gov.ifms.pao-chain` are deployed for it, so seats `DDO-1`, `HOO-1`
and `PAO-1` exist (pack seeds).

## 6. Open points (below 98% confidence)

1. **UI base path.** The UI reads `/config.json` from the site root and its router has no base name
   (`sangam-ui/src/config.ts` `loadConfig`, `sangam-ui/src/app/App.tsx` `BrowserRouter`, `sangam-ui/src/fonts.ts`).
   Under `/sangam/` the browser would ask `<public host>/config.json`, which is IFMS's, and no route would match.
   A UI change is needed: `fetch(import.meta.env.BASE_URL + 'config.json')`, `<BrowserRouter
   basename={import.meta.env.BASE_URL}>`, and base-relative font URLs. This lane changes no product code; it is listed
   for a UI lane. Assets already follow `SANGAM_UI_BASE` (`vite.config.ts` `base`).
2. Whether the seat directory matches a holder by `preferred_username` or by `sub`: the seed posts the username as
   `personId` (as the pack seeds do), not verified against a running server.
3. Whether `POST /admin/assignments` needs a second administrator's approval (`/admin/assignments/{id}/approve`
   exists). If it does, approve the three postings once from the Assignments screen.
4. The pack seeds also post their own holders (for example `gp.ddo-1`) to the same seats; with two holders the task
   goes to the seat and either may claim it. Not verified on screen.

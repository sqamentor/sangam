# DEMO-GO-LIVE-RUNBOOK: Sangam on demo.ifms.dev (2026-11-19)

- Lane: DEMO-E2E (branch `feat/demo-e2e`).
- Source of this file: `sangam/deploy/demo/DEMO-GO-LIVE-RUNBOOK.md` in the repository. The hand-over folder (`sangam-handoff\<date>\sangam-demo-handover\`) carries a copy of it and of the other files of `sangam/deploy/demo/`; correct the source, never the copy.
- Target: one EC2 host in ap-south-1 running a docker compose stack. Traffic arrives through a Cloudflare Tunnel (cloudflared), and Keycloak realm `oneifms` runs in the same stack.
- Deployer: the IFMS go-live lane only. Sangam hands over artifacts and deploys nothing itself.
- Owner approval: "Yes, all 4 steps (Recommended)", given in chat on 2026-11-19 and relayed by the integrator.
- Each outward step below says whose yes it needs. A step not covered by that approval needs a new yes from the owner in chat.

## Inputs to fill in (IFMS)

| Parameter | Meaning |
|---|---|
| `IFMS_EDGE_NETWORK` | The docker network that cloudflared and Keycloak are on |
| `SANGAM_OIDC_ISSUER` | The public issuer, `https://<auth host>/realms/oneifms` |
| `SANGAM_OIDC_JWKS_INTERNAL` | `http://<keycloak service>:<port>/realms/oneifms/protocol/openid-connect/certs` |
| `SANGAM_CSP_CONNECT_EXTRA` | `https://<auth host>` |
| `SANGAM_PG_PASSWORD` | Secret from the host's secret store. Never committed and never printed. |
| `SANGAM_SECRETS_DIR` | The host directory that `generate-demo-keys.sh` writes: `sangam-keys.env` (KEK, registry signer, vault pepper) and `files/`, the file secret provider's root (compliance principal key, audit checkpoint pair). |
| `SANGAM_IMAGE_TAG` | `demo-<tag>` from `IMAGES.txt` |
| `SANGAM_TRUSTED_PROXIES`, `SANGAM_CLIENT_IP_HEADERS` | From `trusted-proxies.env`. Check the Cloudflare lists against cloudflare.com/ips first. |

## Steps

| # | Step | Who runs it | Outward? | Approval |
|---|---|---|---|---|
| 0 | CI builds the images and uploads the artifact `sangam-demo-images`, kept 7 days, with no registry push. Run: (filled in below) | Sangam (route_build) | GitHub only | covered |
| 1 | Download the artifact to the EC2 host, check it with `sha256sum -c SHA256SUMS`, then `docker load` both images and compare the image ids with `IMAGES.txt` (see `README.md`) | IFMS | download to host | owner yes (step 1 of 4) |
| 2 | Keycloak `oneifms`: create the realm roles in `keycloak-clients-sangam.json`, import the clients `sangam-ui` and `sangam-server`, and give the demo users the attribute `tenant=GOI.FIN` and their roles | IFMS | changes the shared realm | owner yes (step 2 of 4) |
| 3 | cloudflared: add an ingress rule for host `demo.ifms.dev` with path `^/sangam(/.*)?$` to `http://sangam-front:8080`, keeping the path unchanged. Put it before the IFMS catch-all. | IFMS | public route | owner yes (step 3 of 4) |
| 4 | Keys and schema, then serve. (a) Keys, once: `SANGAM_SECRETS_DIR=<host dir> sh generate-demo-keys.sh`. It is idempotent, prints no key material and needs only openssl. Make `<dir>/files` readable by the server image's user. (b) Custodian key, once (Key attestation 0). The first serve start uses `SANGAM_KEYS_BOOTSTRAP=true` (Key attestation 1); then sign (2) and switch to the attested settings (3). (c) Schema, one-shot: `docker compose -f <ifms> -f sangam-compose-fragment.yml --env-file sangam-demo.env --env-file <dir>/sangam-keys.env run --rm -e SANGAM_LIQUIBASE_MODE=migrate-only sangam-server`. It creates the engine and product schema and exits. (d) Serve: `SANGAM_LIQUIBASE_MODE=validate` (the production default) in `sangam-demo.env`, then `... up -d sangam-postgres sangam-server sangam-front`. `validate` serves on stock postgres:17.12 after the migrate-only one-shot: the optional pgvector changeset 0044-08 no longer stops it (register L-4; `PresentationSeedOnLaneDatabaseIT` migrates a PostgreSQL schema and then starts on it in `validate` mode). | IFMS | public service | owner yes (step 4 of 4) |
| 5 | Demo data. Run this only after `sangam-server` is healthy: `docker inspect -f '{{.State.Health.Status}}' <project>-sangam-server-1` must print `healthy`, and `https://demo.ifms.dev/sangam/health` must return UP. Python: tested with Python 3.13.2. The scripts use the standard library only, so no pip packages are needed. The scripts are self-contained in the hand-over folder `loader/`. Its files are: the two scripts; the data set JSON; `generate_realm.py`, which holds the persona and seat lists; and, for each Sangam pack, its read-only task form definitions (`forms/*.form.json`) and its `seed.json`. The loader reads those to answer each task, and nothing in the folder is executable apart from the three .py files. The folder and does not need a checkout of the repo; check it first with `sha256sum -c SHA256SUMS`. Then, from `sangam-demo-handover/loader`: (a) `python3 -I deploy/presentation/seed_presentation.py --dry-run` and then `python3 -I deploy/presentation/seed_presentation.py`. Environment: `SANGAM_API_BASE=https://demo.ifms.dev/sangam/api/v1`, `SANGAM_ADMIN_TOKEN`, `SANGAM_PRESENTATION_TENANT=GOI.FIN`, `KEYCLOAK_URL`, `KEYCLOAK_REALM=oneifms`, the Keycloak admin variables and `SANGAM_PRESENTATION_PASSWORD_<USER>`; all secrets come from the secret store. Each persona signs in once, then (a) runs again. (b) `python3 -I deploy/presentation/load_presentation_data.py --dry-run` and then `python3 -I deploy/presentation/load_presentation_data.py`. Environment: `SANGAM_API_BASE` and one `SANGAM_PRESENTATION_TOKEN_<USERNAME>` per persona. Both scripts are idempotent, and a load that stopped half way (a killed process, a dropped connection, an expired token) is continued by the next run: a case whose business key exists is never started again (`LoaderResumeTest`, `LoaderWalkthroughAgainstServerTest`). The loader cancels and removes nothing; a case it reports as NOT AT TARGET stays as it is for an operator to look at. The data set is `data/ifms-jaipur-fy2026-27.json` (DEMO-DATA daf62275). It is a script on the host, not part of the images: the server image has no Python. | IFMS | writes only to Sangam's own demo DB | part of step 4; Keycloak users need step 2 |
| 6 | Smoke test. `https://demo.ifms.dev/sangam/health` returns UP. `/sangam/` shows the sign-in. A DDO signs in and sees the Start page. Audit verify shows a non-zero count. The banner shows the Sandbox badge for a user of GOI.FIN. The payment step of a GOI.FIN bill is answered by the in-process simulator (paid, statement matched, marked Sandbox; owner point (d)); nothing is held for want of a rail, and no real money moves. | IFMS + Sangam | read only | none |

Money: no payment rail is configured. The connector URLs are empty and the simulators are off (production mode refuses simulators anyway). No payment can therefore leave Sangam. For GOI.FIN, the sandbox tenant (owner point (d)), the payee check, the payment and the bank statement are answered inside the server by its simulator and marked Sandbox; for any other tenant a payment step has no connector and stops as an incident. Never set a `SANGAM_CONNECTORS_*` URL on the demo without a new owner yes.

## Owner points (EV-M-0890)

- (a) DECIDED by the owner on 2026-11-19: a written demo waiver. The fragment sets `SANGAM_MALWARE_PRODUCTIONWAIVER="demo.ifms.dev, synthetic data only, behind Cloudflare Access"` and `SANGAM_MALWARE_SCANNER=none`.
- (b) DECIDED: key-environment separation follows decision 387(4) (deploy/README.md section 5.1). See "Key attestation" below. The "local-dev fingerprints" item was withdrawn by the owner.
- (c) GL export: DECIDED, gl-export merged (integration tip 456e3c06).
  - The fragment leaves `sangam.ledger.gl-connector` unset and sets the audited waiver `SANGAM_LEDGER_GLEXPORTWAIVER="demo.ifms.dev, IFMS GL connector not built yet; entries kept in LDG_ACCOUNTING unexported"`.
  - The start records `LEDGER_GL_EXPORT_WAIVED`. Accounting entries are stored in `LDG_ACCOUNTING` with `exported_at` NULL and are exported later, once a connector is set. Schema changeset 0049-01 is applied by the migrate-only one-shot (step 4c).
  - The images listed in the hand-over README are built from the merged code and know this setting.
- (d) Sandbox tenant: DECIDED, owner 2026-11-19 "Yes, enable for GOI.FIN". The fragment sets `SANGAM_DEMO_SANDBOXTENANTS=GOI.FIN` (`sangam.demo.sandbox-tenants`). Bills for GOI.FIN pass the payee check and are paid and statement-matched by the in-process simulator, marked Sandbox (UI badge, audit `sandbox=true`), with no real money. No production guard is relaxed; other tenants are unchanged. The earlier known limit "bills stop at `bl_payeeHold`" no longer applies to GOI.FIN.

- (e) Front container (EV-M-0916): every sangam-front tmpfs is mounted with `uid=101,gid=101` (the image user), so nginx can write conf.d; no host-side ownership fix is needed.

## Key attestation (decision 387(4), deploy/README.md section 5.1; tool `sangam-key-attestation.sh` in this folder; needs sh, awk and OpenSSL 3, no JDK)

The custodian is the IFMS operator. The custodian private key is created on the host, never leaves it, and is never printed.

0. Once, the custodian key:
   - `install -d -m 700 /etc/sangam/custodian`
   - `sh sangam-key-attestation.sh generate-custodian-key /etc/sangam/custodian/custodian-ed25519.pem`
   - It prints only the PUBLIC key, which is the value of `SANGAM_KEYS_CUSTODIAN_PUBLIC_KEY`. Keep an offline backup of the .pem.
1. First start (part of step 4): `SANGAM_KEYS_BOOTSTRAP=true`, with `SANGAM_KEYS_ATTESTATION` and `SANGAM_KEYS_CUSTODIAN_PUBLIC_KEY` empty.
   - The start records `KEY_ENVIRONMENT_BOOTSTRAPPED` and writes the export `/var/lib/sangam/key-fingerprints.txt` in the `sangam-server-state` volume. The export holds fingerprints only.
   - Copy it out with `docker cp <server container>:/var/lib/sangam/key-fingerprints.txt <host path>`.
2. The custodian signs, on the host:
   - `sh sangam-key-attestation.sh sign /etc/sangam/custodian/custodian-ed25519.pem demo-ifms <export> $SANGAM_SECRETS_DIR/attestation/key-attestation.json`
   - Then check it with `sh sangam-key-attestation.sh verify "<public key>" $SANGAM_SECRETS_DIR/attestation/key-attestation.json`. Compare the key count with the export.
3. Every later start: in `sangam-demo.env` set
   - `SANGAM_KEYS_BOOTSTRAP=false`
   - `SANGAM_KEYS_ATTESTATION=/opt/sangam/attestation/key-attestation.json` (the mounted `$SANGAM_SECRETS_DIR/attestation`)
   - `SANGAM_KEYS_CUSTODIAN_PUBLIC_KEY=<the line from 0>`
   
   Then run `up -d sangam-server`. A second bootstrap, or a start without a valid attestation, is refused before any port opens. A key rotation means a new export and a new signature (section 5.1).
4. Proven by `DemoComposeFragmentStartupTest`: a bootstrap start and then an attested restart, using exactly the fragment's env names. KeyAttestationEndToEndTest covers the shell tool itself.
## Tenant GOI.FIN and its first administrators (EV-M-0916; FirstAdminBootstrap; no hand-written DB rows)

The tenant is created by the server itself at start-up, from two settings the fragment carries:

```
SANGAM_SECURITY_FIRSTADMINS_TENANT: GOI.FIN
SANGAM_SECURITY_FIRSTADMINS_SUBJECTS: ${SANGAM_SECURITY_FIRSTADMINS_SUBJECTS:-rajendra.sharma:rajendra.sharma:Rajendra Prasad Sharma,meenakshi.kanwar:meenakshi.kanwar:Meenakshi Kanwar}
```

- Each entry is `person-id:external-subject:Display name`. The external subject is matched against the token's PRINCIPAL claim (`sangam.security.oidc.claims.principal`, default `preferred_username`), not against `sub`. Realm oneifms's sangam-ui tokens carry no `sub` (Keycloak 25+ puts it in the `basic` scope), and none is needed. Do not use the Keycloak `sub` UUIDs here.
- The deployer sets them (the defaults above already name the two realm administrators) for the attested restart (Key attestation step 3). The start creates GOI.FIN if it is missing and appoints both as tenant administrators.
- Check: `GET https://demo.ifms.dev/sangam/api/v1/me` with rajendra.sharma's token answers 200 with `"tenantId":"GOI.FIN"` and administrator permissions, not 401 `TENANT_UNAVAILABLE`.
- The tenant starts in status CREATED, which serves requests; ACTIVE follows when the administrators activate it (AJ-01).
- It is idempotent: once GOI.FIN has a tenant-admin role, later starts change nothing. The two lines may be removed afterwards.
## Hard preconditions before the data load (step 5). Each one stops the load if it fails.

P1. Money must be held or sandbox. Run `python3 -I deploy/presentation/demo_preflight.py money` with `SANGAM_API_BASE`, `SANGAM_ADMIN_TOKEN` and `SANGAM_CONNECTORS_ENV=<file>`, where the file is the output of `docker exec <sangam-server container> env > /tmp/sangam-env; chmod 600`. Delete that file afterwards. The check asks the server whether the tenant is one of its sandbox tenants (`GET /me` with the administrator's token, field `sandbox`; the UI shows the same fact as the Sandbox badge) and reads the server's connector variables from the file. It stops unless the answer is `sandbox: true` and no payment connector URL is set. The sandbox tenant list is the fragment's `SANGAM_DEMO_SANDBOXTENANTS` (owner point (d)); changing it needs the owner's yes.

P2. Persona tokens (`SANGAM_PRESENTATION_TOKEN_<USER>`, where `<USER>` is the username in upper case with `.` and `-` replaced by `_`). Realm `oneifms` has no client with a password grant, and none is created for the load. Each persona's token comes from the standard authorization-code flow with PKCE of the client `sangam-ui`, driven on the host by the IFMS browser-flow helper (joint ledger, runbook P2 redesign).
  - No extra client, no change to the shared realm.
  - The helper exports each token as `SANGAM_PRESENTATION_TOKEN_<USER>` in the shell only. No token or password is ever written to a file or printed.
  - Tokens live for 15 minutes (`access.token.lifespan` 900), so run P3 and the full load inside that window, or get the tokens again. A load that a token's expiry interrupts is continued by the next run (see step 5).
  - Run `unset` on every `SANGAM_PRESENTATION_TOKEN_*` variable right after the load.

P3. First-load smoke test: run `python3 -I deploy/presentation/demo_preflight.py smoke` with `SANGAM_API_BASE` and the tokens set. It loads only BILL-0005 (which should stop at `pc_dealing`) and GPF-0002 (which should stop at `sn_sanction`). Neither case pays anything, and both carry `claimPeriod` and `qualifyingService` as strings over REST. The script stops unless both report that they wait at their task. Only then run `load_presentation_data.py` for the rest; it finds the two smoke cases by their business keys and continues them, so nothing is started twice. The check can be run again: it passes as long as both cases still wait at their task.

P4. Funds for GIA-0001: before the load, the funds/ledger connector (`ledger.available`, the bill's `bl_funds` step) must answer at least ₹25,00,000 (25 lakh, 250000000 paise) available to DDO-1 on head 2210-00-101-01-00-31, at the DDO's consuming stage. Otherwise GIA-0001 is correctly held at `bl_hold` and waits for the message `sangam.ifms.fundsAllotted`.
  - How the funds get there (OWNER-QUESTIONS Q3): through the ledger API, by a tenant administrator, before the load. `POST /api/v1/ledger/by-ledger/operations?ledger=BUDGET` (each call with its own `Idempotency-Key`): one `ALLOCATE` of 2500000.00 at the first stage, then one `DISTRIBUTE` of the same amount per further stage, through every stage (APPROPRIATION, ALLOCATION, RELEASE, AUTHORISATION, SUB_ALLOTMENT), period `2026-27`, line `2210-00-101-01-00-31`, holder `-` up to the last stage and `DDO-1` at the last, consuming stage. Check: `GET /api/v1/ledger/by-ledger/accounts?ledger=BUDGET&period=2026-27&line=2210-00-101-01-00-31&holder=DDO-1&stage=SUB_ALLOTMENT` shows 2500000.00 available.
  - No simulator and no connector setting is involved: in production mode `ledger.available` is answered by the tenant's own ledger. Stopping the distribution at an earlier stage leaves nothing at the consuming stage, and the bill is then correctly held at `bl_hold`.
  - Proven by `DemoLoaderSequenceAgainstServerTest` (step 3a, the calls above) and `GiaFundsThroughEveryStageAgainstServerTest` (the bill's funds step with GIA-0001's values on the server funded this way).
  - OPEN, the other bills: P4 funds one head for one holder. The contingent bills of the data set ask the same ledger for head `2054-00-001-01-00-13` and holder `JPR-DDO-0420` (their `ddoCode`), where nothing is distributed: `GiaFundsThroughEveryStageAgainstServerTest` prints, for BILL-0001's own values, 0 available and the bill held at `bl_hold` (CI run 44859482581). Until those heads are funded the same way (amounts and holders are a decision, not taken here), a bill that passes its checks waits at `bl_hold` for `sangam.ifms.fundsAllotted`.
  - Check after the smoke test: GIA-0001 shows an open `bl_hold` wait if funds are short, and passes `bl_funds` if they are sufficient.

Case notes for the demo (data set at DEMO-DATA 78d350bb):
- GRV-0001 (grievance) closes only through a `POST /api/v1/messages` step (`sangam.grievanceFeedback`, the citizen's rating), which the loader sends from the data set's `events`. Until that runs it waits at `gv_feedbackWait`.
- GIA-0001 and GIA-0002: the sanction (`sn_sanction`) is routed to the role `CAA` (`ifms-gia-authority.dmn`). No persona of the data set holds a CAA seat, so on the source of this runbook both cases wait there and GIA-0001 does not reach the bill's funds step by itself (seen in the per-case stop list of `DemoLoaderSequenceAgainstServerTest`). The funds of P4 are in place for the moment it does.
- RTI-0001 stays open by design: the reply is issued and the 30-day first-appeal window (`rt_appealWait`) runs to 2026-12-19. It is not a stuck case.
## Rollback (each step undone on its own, newest first)

1. Stop Sangam with `docker compose -f <ifms> -f sangam-compose-fragment.yml stop sangam-front sangam-server sangam-postgres`. IFMS is not touched, and nothing else in the IFMS stack depends on Sangam.
2. Remove the cloudflared `/sangam` rule and reload cloudflared. `demo.ifms.dev/sangam/` then falls through to the IFMS catch-all.
3. Keycloak: delete the clients `sangam-ui` and `sangam-server`. Keep or delete the `sangam-*` roles; this does not affect IFMS clients.
4. Data: `docker compose ... rm -f` the three services. To drop the data as well, run `docker volume rm <project>_sangam-pg-data <project>_sangam-server-state`. This cannot be undone, so take a `pg_dump` first (DB-REQUIREMENT.md) and get the owner's yes.
5. Images: `docker image rm sangam-server:demo-<tag> sangam-front:demo-<tag>`.

To roll back to an earlier hand-over instead, load the earlier tarballs, set `SANGAM_IMAGE_TAG` to the earlier tag and run `up -d`. If the schema moved forward, first restore the `pg_dump` taken before the redeploy.

## Run record

(filled in below)

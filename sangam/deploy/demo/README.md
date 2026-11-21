# Sangam demo hand-over for demo.ifms.dev

This hand-over is for the IFMS go-live lane, which is the only deployer. Sangam hands over the artifacts listed here
and does not deploy anything itself.

| File | What it is |
|---|---|
| `sangam-compose-fragment.yml` | The services `sangam-postgres`, `sangam-server` and `sangam-front`, merged with the IFMS compose stack. `sangam-front` serves the UI at `/sangam/` and the API at `/sangam/api/v1`. |
| `trusted-proxies.env` | B9: the `sangam-internal` subnet plus the Cloudflare ranges, and `CF-Connecting-IP` as the client address header |
| `keycloak-clients-sangam.json` | The `sangam-ui` client (public, PKCE) and the `sangam-server` client (bearer-only) for the existing realm `oneifms`. It contains no secrets. |
| `generate-demo-keys.sh` | Host-side key generation (openssl only, idempotent, prints no key material) into `SANGAM_SECRETS_DIR` |
| `DB-REQUIREMENT.md` | The database engine, version, size and backup |
| `DEMO-GO-LIVE-RUNBOOK.md` | The steps, the owner's yes for each one, the hard preconditions of the data load, and the rollback. This is the source; the hand-over folder carries a copy |

## Images (no registry)

GHCR publishing happens only from a signed v-tag, and there is no tag yet. So the CI job "Server image build (no
push)" (`.github/workflows/ci-build.yml`) does the following on a `ci/demo-*` branch:

- builds `sangam-server` from `deploy/docker/server.Dockerfile` (target `runtime`);
- builds `sangam-front` from `deploy/presentation/Dockerfile`, which contains the UI built with base `/sangam/`;
- tags both images `demo-<first 12 characters of the commit>`;
- exports both with `docker save | gzip`;
- writes `SHA256SUMS` (the tarballs) and `IMAGES.txt` (the image id `sha256:...` and the commit of each image);
- uploads all of it as the workflow artifact `sangam-demo-images`, kept for 7 days.

Nothing is pushed to any registry.

On the EC2 host, as the IFMS deployer:

```
gh run download <RUN_ID> --repo sqamentor/sangam --name sangam-demo-images --dir sangam-demo-images
cd sangam-demo-images
sha256sum -c SHA256SUMS                      # each line must say OK; stop if one does not
docker load -i sangam-server-demo-<tag>.tar.gz
docker load -i sangam-front-demo-<tag>.tar.gz
cat IMAGES.txt
docker image inspect -f '{{.Id}}' sangam-server:demo-<tag>   # must equal the image-id in IMAGES.txt
docker image inspect -f '{{.Id}}' sangam-front:demo-<tag>    # must equal the image-id in IMAGES.txt
```

The `<RUN_ID>` and `<tag>` of the current hand-over are given in the runbook. Set `SANGAM_IMAGE_TAG=demo-<tag>` in
`sangam-demo.env`.

## Workflow packs of GOI.FIN (register E-25)

`FirstAdminBootstrap` creates the tenant `GOI.FIN` and deploys no workflow pack, and the demo has no platform
administrator who could onboard the tenant. The fragment therefore carries two settings on `sangam-server`:

```
SANGAM_PACKS_DEPLOY_ON_START: "true"
SANGAM_PACKS_TENANTS: GOI.FIN
```

At every start the server then makes sure each pack shipped in the image is deployed for `GOI.FIN`, one engine
deployment per pack named `pack:<id>@<version>`. The first such start deploys them; a later start deploys nothing
again and leaves running cases on the definitions they were started on. Both lines are needed (outside profile `h2`
the default of `sangam.packs.deploy-on-start` is false), and they need no new image: on a host that already runs,
add the two lines and recreate the server container (`up -d sangam-server`). Without them every process start of the
tenant answers `404` (no process definition). Proven by `PacksOfBootstrapTenantAgainstServerTest` (H2) and
`PresentationSeedOnLaneDatabaseIT` (PostgreSQL, the fragment's own profiles).

Read-only check, as any signed-in user of the tenant (role `sangam-user`; `<token>` is that user's bearer token):

```
curl -sS -H "Authorization: Bearer <token>" "https://demo.ifms.dev/sangam/api/v1/deployments?page=0&size=500" \
  | grep -o '"name":"pack:[^"]*"' | sort -u | wc -l     # the number of packs in the image
curl -sS -H "Authorization: Bearer <token>" \
  "https://demo.ifms.dev/sangam/api/v1/process-definitions?key=sangam-ifms-bill&latest=true"   # one definition
```

Before the packs are deployed the first command prints `0` and the second an empty list; afterwards the first prints
the number of packs the image ships (the server log of the start says `Deployed <n> packs for tenant GOI.FIN` with
the same number) and the second one definition. The tenant's audit chain `sec:GOI.FIN` gets one `PACKS_DEPLOYED`
event per start.

Connectors are a separate matter (register E-26): with the packs deployed, cases still stop where a pack calls a
connector that nothing answers on the demo. No `SANGAM_CONNECTORS_*` value is part of this change.

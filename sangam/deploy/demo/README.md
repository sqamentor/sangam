# Sangam demo hand-over for demo.ifms.dev

This hand-over is for the IFMS go-live lane, which is the only deployer. Sangam hands over the artifacts listed here
and does not deploy anything itself.

| File | What it is |
|---|---|
| `sangam-compose-fragment.yml` | The services `sangam-postgres`, `sangam-server` and `sangam-front`, merged with the IFMS compose stack. `sangam-front` serves the UI at `/sangam/` and the API at `/sangam/api/v1`. |
| `trusted-proxies.env` | B9: the `sangam-internal` subnet plus the Cloudflare ranges, and `CF-Connecting-IP` as the client address header |
| `keycloak-clients-sangam.json` | The `sangam-ui` client (public, PKCE) and the `sangam-server` client (bearer-only) for the existing realm `oneifms`. It contains no secrets. |
| `DB-REQUIREMENT.md` | The database engine, version, size and backup |
| `../../../sangam-handoff/2026-11-19/DEMO-GO-LIVE-RUNBOOK.md` (outside the repo) | The steps, the owner's yes for each one, and the rollback |

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

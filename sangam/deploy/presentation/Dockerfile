# syntax=docker/dockerfile:1.7
# SPDX-License-Identifier: Apache-2.0
#
# R8-present: the Sangam web front served under one path prefix (/sangam/) for a host that forwards
# <public host>/sangam/* here with the path unchanged. It is deploy/docker/ui.Dockerfile with two differences only:
# the Vite build gets SANGAM_UI_BASE=/sangam/ (assets at /sangam/assets/...), and nginx uses
# deploy/presentation/sangam-path.conf.template (UI, /sangam/config.json, /sangam/api/* and /sangam/health).
# The API itself is the unchanged server image (deploy/docker/server.Dockerfile, default target); this container
# proxies to it at SANGAM_API_UPSTREAM. See deploy/presentation/README.md.
#
# Build context: the `sangam/` directory, checked out at the green sha:
#   git -C <repo> checkout <sha>
#   docker build -f deploy/presentation/Dockerfile -t sangam-front:<sha> .
#   docker build -f deploy/docker/server.Dockerfile -t sangam-server:<sha> .
#
# Base images: the same digest-pinned images as deploy/docker/ui.Dockerfile (decisions 253, 261). Pulling them is a
# download: the owner approves it first. At run time nothing is fetched from the internet (all assets are bundled,
# R56); the only outbound connections are to SANGAM_API_UPSTREAM (and the browser's to the OIDC issuer).

FROM node:26.13.1-alpine3.24@sha256:6bd9ab43c0ec0190ca7b2d193906cb8b7edf6e7f4afa43ddeccd6452710bffb5 AS build
WORKDIR /src
COPY sangam-ui/package.json sangam-ui/package-lock.json* ./
ARG NPM_CONFIG_REGISTRY
RUN --mount=type=cache,target=/root/.npm \
    npm ci --ignore-scripts --no-audit --no-fund
COPY sangam-ui/ ./
COPY deploy/fonts/*.ttf deploy/fonts/OFL-*.txt deploy/fonts/README.md /deploy/fonts/
ARG SANGAM_UI_BASE=/sangam/
ENV SANGAM_UI_BASE=${SANGAM_UI_BASE}
RUN npm run build && test -f dist/index.html && grep -q "${SANGAM_UI_BASE}assets/" dist/index.html \
 && mkdir -p /out && cp -r dist/. /out/

FROM nginxinc/nginx-unprivileged:1.30.6-alpine3.24@sha256:f5116ed4606f3eb015dd4a8e28cb9b351476dc0ad4bc6e9f93b19cec130cdf03 AS runtime
LABEL org.opencontainers.image.title="sangam-front" \
      org.opencontainers.image.description="Sangam web UI and API proxy under /sangam/" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.source="https://github.com/sqamentor/sangam"
COPY --from=build --chown=101:0 /out/ /usr/share/nginx/html/
COPY --chown=101:0 deploy/presentation/sangam-path.conf.template /etc/nginx/templates/default.conf.template
COPY --chown=101:0 deploy/docker/ui/security-headers.inc.template /etc/nginx/templates/security-headers.inc.template
COPY --chown=101:0 deploy/docker/ui/teams-headers.inc.template /etc/nginx/templates/teams-headers.inc.template
COPY --chmod=0555 deploy/docker/ui/16-sangam-teams.envsh /docker-entrypoint.d/16-sangam-teams.envsh
COPY --chmod=0555 deploy/docker/ui/17-sangam-dsc.envsh /docker-entrypoint.d/17-sangam-dsc.envsh
COPY --chmod=0555 deploy/docker/ui/18-sangam-session.envsh /docker-entrypoint.d/18-sangam-session.envsh
ENV SANGAM_TEAMS_ENABLED=false \
    SANGAM_TEAMS_FRAME_ANCESTORS="" \
    SANGAM_DSC_AGENT_ORIGIN="" \
    SANGAM_UI_IDLE_MINUTES=30 \
    SANGAM_API_UPSTREAM=http://sangam-server:8080 \
    SANGAM_PUBLIC_API_BASE=/sangam/api/v1 \
    SANGAM_OIDC_ISSUER="" \
    SANGAM_OIDC_CLIENT_ID=sangam-ui \
    SANGAM_DEFAULT_LOCALE=en \
    SANGAM_LOCALES=en \
    SANGAM_ENV_LABEL="" \
    SANGAM_CSP_CONNECT_EXTRA="" \
    NGINX_ENVSUBST_FILTER=^SANGAM_
USER 101
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --retries=3 CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1

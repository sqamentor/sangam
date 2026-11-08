# syntax=docker/dockerfile:1.7
# SPDX-License-Identifier: Apache-2.0
#
# Sangam UI image: the Vite build of sangam-ui served by unprivileged nginx (uid 101, port 8080).
#
# Build context: the `sangam/` directory.
#   docker build -f deploy/docker/ui.Dockerfile -t sangam-ui:dev .
#   # prebuilt dist (CI artefact / air-gapped build host):
#   docker build -f deploy/docker/ui.Dockerfile --build-arg DIST_SOURCE=prebuilt -t sangam-ui:dev .
#
# Runtime configuration (no rebuild per environment): the env variables below are rendered into
# /etc/nginx/conf.d/default.conf by the nginx image's envsubst step at start-up; GET /config.json returns them
# to the browser (the UI reads /config.json at boot).
#
# LIC-8: bpmn-js/dmn-js/form-js are bundled unmodified with the bpmn.io watermark visible.

ARG NODE_IMAGE=node:22.18.0-alpine3.22
ARG NGINX_IMAGE=nginxinc/nginx-unprivileged:1.28.0-alpine3.21
# build | prebuilt
ARG DIST_SOURCE=build

FROM ${NODE_IMAGE} AS build
WORKDIR /src
COPY sangam-ui/package.json sangam-ui/package-lock.json* ./
# npm ci honours an offline mirror through NPM_CONFIG_REGISTRY / .npmrc (see deploy/README.md "Offline builds").
ARG NPM_CONFIG_REGISTRY
RUN --mount=type=cache,target=/root/.npm \
    npm ci --no-audit --no-fund
COPY sangam-ui/ ./
RUN npm run build && test -f dist/index.html && mkdir -p /out && cp -r dist/. /out/

FROM ${NODE_IMAGE} AS prebuilt
COPY sangam-ui/dist/ /out/

FROM ${DIST_SOURCE} AS dist

FROM ${NGINX_IMAGE} AS runtime
LABEL org.opencontainers.image.title="sangam-ui" \
      org.opencontainers.image.description="Sangam web UI (tasklist, cockpit, admin, modeller)" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.source="https://github.com/sqamentor/sangam"
COPY --from=dist --chown=101:0 /out/ /usr/share/nginx/html/
COPY --chown=101:0 deploy/docker/ui/default.conf.template /etc/nginx/templates/default.conf.template
COPY --chown=101:0 deploy/docker/ui/security-headers.inc.template /etc/nginx/templates/security-headers.inc.template
ENV SANGAM_API_UPSTREAM=http://sangam-server:8080 \
    SANGAM_PUBLIC_API_BASE=/api/v1 \
    SANGAM_OIDC_ISSUER=http://localhost:8180/realms/sangam \
    SANGAM_OIDC_CLIENT_ID=sangam-ui \
    SANGAM_DEFAULT_LOCALE=en \
    SANGAM_LOCALES=en,hi \
    SANGAM_ENV_LABEL=dev \
    SANGAM_CSP_CONNECT_EXTRA="" \
    NGINX_ENVSUBST_FILTER=^SANGAM_
USER 101
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --retries=3 CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1

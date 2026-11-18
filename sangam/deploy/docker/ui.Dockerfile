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

# Base images are pinned by index digest in the FROM lines (decisions 253, 261; lookup in DOWNLOADS-WAVE4.md).
# build | prebuilt
ARG DIST_SOURCE=build

FROM node:26.13.1-alpine3.24@sha256:6bd9ab43c0ec0190ca7b2d193906cb8b7edf6e7f4afa43ddeccd6452710bffb5 AS build
WORKDIR /src
COPY sangam-ui/package.json sangam-ui/package-lock.json* ./
# npm ci honours an offline mirror through NPM_CONFIG_REGISTRY / .npmrc (see deploy/README.md "Offline builds").
ARG NPM_CONFIG_REGISTRY
# Decision 130 (RM-25, CR-14): no install-time scripts, and since Vite 8 (M-117) none is needed, so nothing is rebuilt
# after npm ci. rolldown and lightningcss load their native binary from a prebuilt optional package
# (@rolldown/binding-linux-*, lightningcss-linux-*; plain optionalDependencies selected by os and cpu, no
# hasInstallScript). esbuild, whose postinstall used to be rebuilt here by name, is no longer in package-lock.json.
# The packages that do have an install script are not run: @carbon/grid and @carbon/layout (ibmtelemetry, install-time
# telemetry), inferno (opencollective banner) and fsevents (macOS only, optional).
RUN --mount=type=cache,target=/root/.npm \
    npm ci --ignore-scripts --no-audit --no-fund
COPY sangam-ui/ ./
# Decision 120 (RM-24): scripts/deploy-fonts.mjs reads the approved Noto TTFs, their OFL texts and the SHA-256 table
# from ../deploy/fonts relative to the UI root (/src), so they go to /deploy/fonts; a missing or changed file fails the build.
COPY deploy/fonts/*.ttf deploy/fonts/OFL-*.txt deploy/fonts/README.md /deploy/fonts/
RUN npm run build && test -f dist/index.html && mkdir -p /out && cp -r dist/. /out/

FROM node:26.13.1-alpine3.24@sha256:6bd9ab43c0ec0190ca7b2d193906cb8b7edf6e7f4afa43ddeccd6452710bffb5 AS prebuilt
COPY sangam-ui/dist/ /out/

FROM ${DIST_SOURCE} AS dist

FROM nginxinc/nginx-unprivileged:1.30.6-alpine3.24@sha256:f5116ed4606f3eb015dd4a8e28cb9b351476dc0ad4bc6e9f93b19cec130cdf03 AS runtime
LABEL org.opencontainers.image.title="sangam-ui" \
      org.opencontainers.image.description="Sangam web UI (tasklist, cockpit, admin, modeller)" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.source="https://github.com/sqamentor/sangam"
COPY --from=dist --chown=101:0 /out/ /usr/share/nginx/html/
COPY --chown=101:0 deploy/docker/ui/default.conf.template /etc/nginx/templates/default.conf.template
COPY --chown=101:0 deploy/docker/ui/security-headers.inc.template /etc/nginx/templates/security-headers.inc.template
# Decisions 162 and 213: /teams.html headers (teams-headers.inc) and the start-up check of the two Teams variables,
# sourced by the nginx entrypoint before envsubst (a bad value stops the container).
COPY --chown=101:0 deploy/docker/ui/teams-headers.inc.template /etc/nginx/templates/teams-headers.inc.template
COPY --chmod=0555 deploy/docker/ui/16-sangam-teams.envsh /docker-entrypoint.d/16-sangam-teams.envsh
# Decision 165: the DSC loopback agent origin (empty = not set up), checked at start by 17-sangam-dsc.envsh.
COPY --chmod=0555 deploy/docker/ui/17-sangam-dsc.envsh /docker-entrypoint.d/17-sangam-dsc.envsh
# Decision 349: the UI idle period in minutes (1-480, default 15), checked at start by 18-sangam-session.envsh.
COPY --chmod=0555 deploy/docker/ui/18-sangam-session.envsh /docker-entrypoint.d/18-sangam-session.envsh
ENV SANGAM_TEAMS_ENABLED=false \
    SANGAM_TEAMS_FRAME_ANCESTORS="" \
    SANGAM_DSC_AGENT_ORIGIN="" \
    SANGAM_UI_IDLE_MINUTES=15
ENV SANGAM_API_UPSTREAM=http://sangam-server:8080 \
    SANGAM_PUBLIC_API_BASE=/api/v1 \
    SANGAM_OIDC_ISSUER=http://localhost:8180/realms/sangam \
    SANGAM_OIDC_CLIENT_ID=sangam-ui \
    SANGAM_DEFAULT_LOCALE=en \
    SANGAM_LOCALES=en,hi \
    SANGAM_ENV_LABEL=dev \
    SANGAM_CSP_CONNECT_EXTRA="" \
    SANGAM_TRACE_URL_TEMPLATE="" \
    NGINX_ENVSUBST_FILTER=^SANGAM_
USER 101
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --retries=3 CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1

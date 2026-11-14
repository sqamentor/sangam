# syntax=docker/dockerfile:1.7
# SPDX-License-Identifier: Apache-2.0
#
# Sangam server in TEST MODE (R18 layer 2, M-021): a throw-away server for process-level SDK and worker tests.
# NEVER for production: in-memory H2 (every restart starts empty), the static development token, the dev bootstrap
# tenant, the built-in simulators. Docs: deploy/docker/test-mode/README.md.
#
# It is a thin layer on the server image built by server.Dockerfile, so it pulls no base image of its own:
#
#   docker build -f deploy/docker/server.Dockerfile -t sangam-server:dev .
#   docker build -f deploy/docker/server-test-mode.Dockerfile -t sangam-server:dev-test-mode .
#   docker run --rm -p 8080:8080 -p 8081:8081 sangam-server:dev-test-mode
#
# Build context: `sangam/` (nothing is copied from it; the per-Dockerfile ignore file sends an empty context).
#
# Every setting below is an existing server key (sangam-server application.yaml / application-h2.yaml); nothing is
# invented here. Clock steering and time-skip (BusinessClock + engine clock) need a server endpoint that does not
# exist yet: see "Pending" in the README. Until it lands, timers fire on the real clock.

ARG SERVER_IMAGE=sangam-server:dev
FROM ${SERVER_IMAGE}

LABEL org.opencontainers.image.title="sangam-server-test-mode" \
      org.opencontainers.image.description="Sangam server in test mode (in-memory H2, dev token, simulators). Not for production." \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.source="https://github.com/sqamentor/sangam" \
      org.sangam.test-mode="true"

# h2: application-h2.yaml (in-memory H2 in PostgreSQL mode, schema created on start, dev token, dev bootstrap,
#     simulators on, debug on). SANGAM_DB_URL is left to the profile's in-memory default on purpose.
# Job executor: shorter acquisition waits (application.yaml operaton.bpm.job-execution.*), so due timers and async
#     continuations run within about half a second instead of up to five; the test harness waits less.
# SANGAM_DEV_TOKEN: application-h2.yaml's development token; tests read it from the same variable. Override it with
#     -e SANGAM_DEV_TOKEN=... ; it is a test credential, never a production secret.
# SANGAM_SECURITY_PRODUCTION=false: set explicitly (decisions 143 and 198): the server image does not set the switch,
#     EnvironmentPolicy refuses a missing one, and production mode refuses the dev token, dev bootstrap and simulators
#     that test mode is made of. This layer is never production.
ENV SPRING_PROFILES_ACTIVE=h2 \
    SANGAM_SECURITY_PRODUCTION=false \
    SANGAM_JOB_WAIT_TIME_IN_MILLIS=100 \
    SANGAM_JOB_MAX_WAIT=500

EXPOSE 8080 8081

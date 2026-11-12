# syntax=docker/dockerfile:1.7
# SPDX-License-Identifier: Apache-2.0
#
# Sangam script worker (M-061; ADR-D-8 script sandbox; owner decision 53 = Q-07 option A). One container: a Java SDK
# worker on the lease protocol that runs every BPMN script task as a child process of Node.js or CPython inside
# nsjail (no network, own namespaces, seccomp, cgroup v2 limits). No script engine in any JVM (GraalJS, Rhino,
# Nashorn, Jython stay banned). Only Docker or Podman is needed on a VM.
#
# Build context: `sangam/`.
#   docker build -f deploy/docker/script-worker.Dockerfile \
#       --build-arg NSJAIL_COMMIT=<the approved 40-hex commit of nsjail tag 3.4, decision 77> -t sangam-script-worker:dev .
#   docker run --rm --cgroupns=private --cap-add SYS_ADMIN --security-opt seccomp=unconfined \
#       --security-opt apparmor=unconfined --security-opt systempaths=unconfined \
#       -e SANGAM_URL=http://sangam-server:8080 -e SANGAM_TOKEN=... sangam-script-worker:dev
#   (container privileges: tools/sangam-script-worker/README.md; approved by owner decision 78)
# Escape tests inside the image:
#   docker run --rm <same flags> --entrypoint sh sangam-script-worker:test -c \
#       'cd /src && mvn -B -Pscript-sandbox -f tools/sangam-script-worker/pom.xml verify'   (target "test" below)
#
# Base images: MAVEN_IMAGE and JRE_IMAGE are the server's (deploy/docker/server.Dockerfile). NODE_IMAGE is new to the
# repository; only its node binary is copied. All image inputs are approved for the test phase only (decision 79).

ARG MAVEN_IMAGE=maven:3.9.11-eclipse-temurin-25
ARG JRE_IMAGE=eclipse-temurin:25.0.1_8-jre-noble
ARG NODE_IMAGE=node:22.18.0-bookworm-slim

# Stage 1: the SDK and the worker jar with its runtime dependencies.
FROM ${MAVEN_IMAGE} AS jars
ARG MAVEN_ARGS="-B -ntp"
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn ${MAVEN_ARGS} -DskipTests -pl sdks/java -am install \
 && mvn ${MAVEN_ARGS} -DskipTests -f tools/sangam-script-worker/pom.xml package \
 && mvn ${MAVEN_ARGS} -q -f tools/sangam-script-worker/pom.xml dependency:copy-dependencies \
        -DincludeScope=runtime -DoutputDirectory=/out/lib \
 && cp tools/sangam-script-worker/target/sangam-script-worker-*.jar /out/sangam-script-worker.jar \
 && ls -l /out /out/lib

# Stage 2: nsjail from source at a pinned commit (kafel is a git submodule, so no release tarball).
FROM ${JRE_IMAGE} AS nsjail
ARG NSJAIL_TAG=3.4
ARG NSJAIL_COMMIT
RUN test -n "${NSJAIL_COMMIT}" || { echo "NSJAIL_COMMIT is required (the approved commit of tag ${NSJAIL_TAG})"; exit 1; } \
 && apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      autoconf bison flex gcc g++ git ca-certificates libnl-route-3-dev libprotobuf-dev make pkg-config \
      protobuf-compiler \
 && git clone --branch "${NSJAIL_TAG}" --depth 1 --recurse-submodules --shallow-submodules \
      https://github.com/google/nsjail.git /nsjail \
 && test "$(git -C /nsjail rev-parse HEAD)" = "${NSJAIL_COMMIT}" \
 && make -C /nsjail -j"$(nproc)" \
 && install -m 0755 /nsjail/nsjail /usr/local/bin/nsjail

FROM ${NODE_IMAGE} AS node

# Stage 3: runtime.
FROM ${JRE_IMAGE} AS runtime
LABEL org.opencontainers.image.title="sangam-script-worker" \
      org.opencontainers.image.description="Sangam script worker: BPMN script tasks in an nsjail sandbox (ADR-D-8)" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.source="https://github.com/sqamentor/sangam"
RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      python3 libprotobuf32t64 libnl-route-3-200 \
 && rm -rf /var/lib/apt/lists/*
COPY --from=nsjail /usr/local/bin/nsjail /usr/local/bin/nsjail
COPY --from=node /usr/local/bin/node /usr/bin/node
COPY --chmod=0555 tools/sangam-script-worker/src/main/runtime/ /opt/sangam/script-runtime/
COPY --from=jars /out/ /opt/sangam/script-worker/
COPY --chmod=0555 deploy/docker/script-worker/entrypoint.sh /usr/local/bin/sangam-script-worker
ENV TZ=UTC \
    SANGAM_SCRIPT_NSJAIL=/usr/local/bin/nsjail \
    SANGAM_SCRIPT_RUNTIME=/opt/sangam/script-runtime \
    SANGAM_SCRIPT_SECCOMP=/opt/sangam/script-runtime/sangam-script.kafel \
    SANGAM_SCRIPT_NODE=/usr/bin/node \
    SANGAM_SCRIPT_PYTHON=/usr/bin/python3
# Root inside the container is needed to create namespaces and cgroups for each jail; every script itself runs as
# nobody (65534) in its own user namespace with no capabilities and no_new_privs (NsjailSandbox).
ENTRYPOINT ["/usr/local/bin/sangam-script-worker"]

# Test target: the runtime plus the sources and Maven, for ScriptSandboxEscapeIT (-Pscript-sandbox). Never deployed.
FROM runtime AS test
COPY --from=jars /usr/share/maven /usr/share/maven
COPY --from=jars /opt/java/openjdk /opt/java/openjdk-build
COPY --from=jars /src /src
ENV JAVA_HOME=/opt/java/openjdk-build PATH=/opt/java/openjdk-build/bin:/usr/share/maven/bin:$PATH

FROM runtime

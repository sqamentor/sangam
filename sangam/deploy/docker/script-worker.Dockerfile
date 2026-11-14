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
#       --build-arg NSJAIL_COMMIT=<the approved 40-hex commit of nsjail tag 3.4, decision 77> \
#       <and the other required build args listed under "Release requirements" below> -t sangam-script-worker:dev .
#   docker run --rm --cgroupns=private --cap-add SYS_ADMIN --security-opt seccomp=unconfined \
#       --security-opt apparmor=unconfined --security-opt systempaths=unconfined \
#       -e SANGAM_URL=http://sangam-server:8080 -e SANGAM_TOKEN=... sangam-script-worker:dev
#   (container privileges: tools/sangam-script-worker/README.md; approved by owner decision 78)
# Escape tests inside the image:
#   docker run --rm <same flags> --entrypoint sh sangam-script-worker:test -c \
#       'cd /src && mvn -B -Pscript-sandbox -f tools/sangam-script-worker/pom.xml verify'   (target "test" below)
#
# Base images: the Maven and JRE images are the server's (deploy/docker/server.Dockerfile). The Node image is new to
# the repository; only its node binary and its LICENSE are copied. All image inputs are approved for the test phase only
# (decision 79).
#
# Release requirements (owner decision 128 = C-13, RM-32; the image is distributed in 0.1.0):
#   - every build arg below without a default is REQUIRED and the build fails without it:
#       NSJAIL_COMMIT          the approved 40-hex commit of nsjail tag NSJAIL_TAG (decision 77), checked after clone;
#       KAFEL_COMMIT           the 40-hex commit of the kafel submodule that NSJAIL_COMMIT pins, checked after clone;
#       UBUNTU_SNAPSHOT        the dated Ubuntu snapshot archive ID (YYYYMMDDTHHMMSSZ, snapshot.ubuntu.com) that every
#                              apt call of both the build and the runtime stage resolves against (owner decision 157):
#                              the build packages and the shipped packages (python3, libprotobuf32t64, libnl-3-200,
#                              libnl-route-3-200) are the versions of that one moment, so a rebuild with the same ID
#                              gets the same versions. The exact apt snapshot syntax below is NOT_RUN until the first
#                              test-phase build verifies it;
#     the release job records the values it used, and the resulting package versions (/licenses/OS-PACKAGES.txt), next
#     to the image SBOM (W4-C, release.yml);
#   - /licenses in the image holds Node.js's LICENSE, the nsjail and kafel LICENSE files, COMMITS (the nsjail and kafel
#     commits built) and the OS-package source offer (libnl is LGPL-2.1: shipped unmodified as separate shared libraries,
#     with the source offer; decision 118 for OS packages);
#   - the image SBOM and the licence gate run in the release job (M-147, W4-C).
# Example (test phase):
#   docker build -f deploy/docker/script-worker.Dockerfile --build-arg NSJAIL_COMMIT=<40-hex> \
#       --build-arg KAFEL_COMMIT=<40-hex> --build-arg UBUNTU_SNAPSHOT=<YYYYMMDDTHHMMSSZ> -t sangam-script-worker:dev .

# Base images are pinned by index digest in the FROM lines (decisions 253, 261; lookup in DOWNLOADS-WAVE4.md).

# Stage 1: the SDK and the worker jar with its runtime dependencies.
FROM maven:3.9.11-eclipse-temurin-25@sha256:407c4423cec0cf2981055bc2c6c0dc211d9605b6669279b95997f2d1c7e91e2c AS jars
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
FROM eclipse-temurin:25.0.1_8-jre-noble@sha256:ecbdcdbfae44ee61794a8ad36042b6b8e3c3124e5e9c171c3630fcd5ab856e33 AS nsjail
ARG NSJAIL_TAG=3.4
ARG NSJAIL_COMMIT
ARG KAFEL_COMMIT
ARG UBUNTU_SNAPSHOT
RUN test -n "${NSJAIL_COMMIT}" || { echo "NSJAIL_COMMIT is required (the approved commit of tag ${NSJAIL_TAG})"; exit 1; } \
 && test -n "${KAFEL_COMMIT}" || { echo "KAFEL_COMMIT is required (the kafel submodule commit of ${NSJAIL_COMMIT})"; exit 1; } \
 && printf '%s\n' "${UBUNTU_SNAPSHOT}" | grep -Eqx '[0-9]{8}T[0-9]{6}Z' \
      || { echo "UBUNTU_SNAPSHOT is required as YYYYMMDDTHHMMSSZ (decision 157)"; exit 1; } \
 && apt-get update --snapshot "${UBUNTU_SNAPSHOT}" \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends --snapshot "${UBUNTU_SNAPSHOT}" \
      autoconf bison flex gcc g++ git ca-certificates libnl-route-3-dev libprotobuf-dev make pkg-config \
      protobuf-compiler \
 && git clone --branch "${NSJAIL_TAG}" --depth 1 --recurse-submodules --shallow-submodules \
      https://github.com/google/nsjail.git /nsjail \
 && test "$(git -C /nsjail rev-parse HEAD)" = "${NSJAIL_COMMIT}" \
 && test "$(git -C /nsjail/kafel rev-parse HEAD)" = "${KAFEL_COMMIT}" \
 && make -C /nsjail -j"$(nproc)" \
 && install -m 0755 /nsjail/nsjail /usr/local/bin/nsjail \
 && mkdir -p /licenses/nsjail /licenses/kafel \
 && cp /nsjail/LICENSE /licenses/nsjail/LICENSE \
 && cp /nsjail/kafel/LICENSE /licenses/kafel/LICENSE \
 && printf 'nsjail %s %s\nkafel %s (submodule of nsjail)\n' "${NSJAIL_TAG}" "${NSJAIL_COMMIT}" "${KAFEL_COMMIT}" \
        > /licenses/nsjail/COMMITS

FROM node:26.13.1-bookworm-slim@sha256:5df79989ed685e09f0a98019db4cebfb22fb9df1a53c0161664a011f7ff6b0f1 AS node

# Stage 3: runtime.
FROM eclipse-temurin:25.0.1_8-jre-noble@sha256:ecbdcdbfae44ee61794a8ad36042b6b8e3c3124e5e9c171c3630fcd5ab856e33 AS runtime
ARG NSJAIL_TAG=3.4
ARG NSJAIL_COMMIT
ARG KAFEL_COMMIT
ARG UBUNTU_SNAPSHOT
LABEL org.opencontainers.image.title="sangam-script-worker" \
      org.opencontainers.image.description="Sangam script worker: BPMN script tasks in an nsjail sandbox (ADR-D-8)" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.source="https://github.com/sqamentor/sangam" \
      org.sangam.nsjail.commit="${NSJAIL_COMMIT}" \
      org.sangam.kafel.commit="${KAFEL_COMMIT}" \
      org.sangam.ubuntu.snapshot="${UBUNTU_SNAPSHOT}"
# The shipped OS packages come from one dated Ubuntu snapshot (decisions 128 and 157); no build without it. The
# resolved versions are recorded in /licenses/OS-PACKAGES.txt.
RUN test -n "${NSJAIL_COMMIT}" && test -n "${KAFEL_COMMIT}" \
      && printf '%s\n' "${UBUNTU_SNAPSHOT}" | grep -Eqx '[0-9]{8}T[0-9]{6}Z' \
      || { echo "NSJAIL_COMMIT, KAFEL_COMMIT and UBUNTU_SNAPSHOT (YYYYMMDDTHHMMSSZ) are required (decisions 128, 157; RM-32)"; exit 1; } \
 && apt-get update --snapshot "${UBUNTU_SNAPSHOT}" \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends --snapshot "${UBUNTU_SNAPSHOT}" \
      python3 libprotobuf32t64 libnl-3-200 libnl-route-3-200 \
 && rm -rf /var/lib/apt/lists/* \
 && dpkg-query -W -f='${Package} ${Version}\n' python3 libprotobuf32t64 libnl-3-200 libnl-route-3-200 \
        > /tmp/os-packages.txt
COPY --from=nsjail /usr/local/bin/nsjail /usr/local/bin/nsjail
COPY --from=nsjail /licenses/ /licenses/
COPY --from=node /usr/local/bin/node /usr/bin/node
# Node.js's licence travels with its binary (RM-32 acceptance: /licenses holds Node's LICENSE).
COPY --from=node /usr/local/LICENSE /licenses/node/LICENSE
# The OS-package source offer (decision 118; libnl LGPL-2.1, RM-32). One shared text for every image, owned by W4-C.
COPY deploy/licence/OS-PACKAGES-SOURCE-OFFER.txt /licenses/OS-PACKAGES-SOURCE-OFFER.txt
RUN mv /tmp/os-packages.txt /licenses/OS-PACKAGES.txt
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

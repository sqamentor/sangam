# syntax=docker/dockerfile:1.7
# SPDX-License-Identifier: Apache-2.0
#
# Native-light Java SDK worker (SANGAM.md R7): GraalVM CE 25 native executable of the SDK worker runtime
# (deploy/native/sdk-worker), on distroless base (glibc), non-root. No GraalJS / polyglot (LIC-1, D-8).
# The server and the SCALE apps are JVM-only; see deploy/SUPPORT-MATRIX.md section 5 for the supported APIs.
#
# Build context: `sangam/`.
#   docker build -f deploy/docker/sdk-worker-native.Dockerfile -t sangam-sdk-worker:dev .
#   docker run --rm sangam-sdk-worker:dev --smoke         # offline compatibility check, exit 0
#   docker run --rm -e SANGAM_URL=http://sangam-server:8080 -e SANGAM_TOKEN=... sangam-sdk-worker:dev

ARG MAVEN_IMAGE=maven:3.9.11-eclipse-temurin-25
ARG GRAALVM_IMAGE=ghcr.io/graalvm/native-image-community:25.0.1
ARG RUNTIME_IMAGE=gcr.io/distroless/base-debian12:nonroot

# Stage 1: SDK + worker jars and their runtime dependencies (plain JDK).
FROM ${MAVEN_IMAGE} AS jars
ARG MAVEN_ARGS="-B -ntp"
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn ${MAVEN_ARGS} -DskipTests -pl sdks/java -am install \
 && mvn ${MAVEN_ARGS} -DskipTests -f deploy/native/sdk-worker/pom.xml package \
 && mvn ${MAVEN_ARGS} -q -f deploy/native/sdk-worker/pom.xml dependency:copy-dependencies \
        -DincludeScope=runtime -DoutputDirectory=/out/lib \
 && find deploy/native/sdk-worker/target -maxdepth 1 -name 'sangam-sdk-worker-native-*.jar' \
        ! -name '*-jar-with-dependencies.jar' -exec cp {} /out/ \; \
 && ls -l /out /out/lib

# Stage 2: native-image (GraalVM CE). Metadata comes from the jars' META-INF/native-image directories.
FROM ${GRAALVM_IMAGE} AS native
WORKDIR /build
COPY --from=jars /out/ /build/
RUN native-image -cp "$(ls /build/*.jar | head -n1):/build/lib/*" \
      -o /build/sangam-sdk-worker org.sangam.sdk.nativeimage.NativeWorkerMain \
 && /build/sangam-sdk-worker --smoke

# Stage 3: runtime.
FROM ${RUNTIME_IMAGE} AS runtime
LABEL org.opencontainers.image.title="sangam-sdk-worker" \
      org.opencontainers.image.description="Sangam native-light SDK worker (R7)" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.source="https://github.com/sqamentor/sangam"
COPY --from=native --chown=65532:0 /build/sangam-sdk-worker /usr/local/bin/sangam-sdk-worker
ENV TZ=UTC
USER 65532:0
ENTRYPOINT ["/usr/local/bin/sangam-sdk-worker"]

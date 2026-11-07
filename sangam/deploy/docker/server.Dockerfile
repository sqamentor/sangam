# syntax=docker/dockerfile:1.7
# SPDX-License-Identifier: Apache-2.0
#
# Image for every Sangam Spring Boot application (Spring Boot 4.1.2), non-root, JDK 25 LTS runtime by default (R5).
#
#   APP_MODULE            image                       ports (app / management)
#   sangam-server         sangam-server               8080 / 8081   (default)
#   sangam-control-plane  sangam-control-plane        8090 / 8091   (SCALE control plane, own DB)
#   sangam-gateway        sangam-gateway              8000 / 8001   (SCALE routing edge, no DB)
#   sangam-rollup         sangam-rollup               8095 / 8096   (SCALE national roll-up, own DB)
#
# Build context: the `sangam/` directory (the Maven reactor root).
#
#   docker build -f deploy/docker/server.Dockerfile -t sangam-server:dev .
#   docker build -f deploy/docker/server.Dockerfile --build-arg APP_MODULE=sangam-gateway -t sangam-gateway:dev .
#   docker build -f deploy/docker/server.Dockerfile --target runtime-distroless -t sangam-server:dev-distroless .
#   # JDK 21 runtime (still certified, deploy/SUPPORT-MATRIX.md):
#   docker build -f deploy/docker/server.Dockerfile --build-arg JRE_IMAGE=eclipse-temurin:21.0.8_9-jre-noble \
#       --build-arg DISTROLESS_IMAGE=gcr.io/distroless/java21-debian12:nonroot -t sangam-server:dev-jdk21 .
#   # a jar built outside Docker (CI, air-gapped build host):
#   docker build -f deploy/docker/server.Dockerfile --build-arg JAR_SOURCE=prebuilt \
#       --build-arg PREBUILT_JAR=sangam-server/target/sangam-server-0.1.0-SNAPSHOT.jar .
#
# Licence rules (SANGAM.md X.3a):
#   LIC-2/LIC-3/LIC-4  no Oracle, Db2 or MySQL Connector/J driver in the image. Customers put their own
#                      driver jar into /opt/sangam/drivers (a volume / initContainer), "bring your own driver".
#   LIC-5              the MariaDB driver ships ONLY as a separate, replaceable jar in /opt/sangam/lib-ext with a
#                      source offer, never inside the Spring Boot fat jar (the server, control-plane and rollup poms
#                      exclude it from repackage).
#   LIC-1/LIC-10       no GraalJS / Rhino / Nashorn / Jython / JRuby (checked by deploy/licence/check_licences.py).
#
# Both extra directories are put on the class path through Spring Boot's PropertiesLauncher (loader.path), so a
# driver can be replaced without rebuilding the image. Writable state lives under /var/lib/sangam (compliance log
# archive, cell exports); mount a volume there (R51: 180-day India-resident logs).

ARG MAVEN_IMAGE=maven:3.9.11-eclipse-temurin-25
ARG JRE_IMAGE=eclipse-temurin:25.0.1_8-jre-noble
ARG DISTROLESS_IMAGE=gcr.io/distroless/java25-debian13:nonroot
# build | prebuilt
ARG JAR_SOURCE=build
ARG APP_MODULE=sangam-server

# ---------------------------------------------------------------------------------------------------------------
# Stage: build the fat jar from source with the reactor (needs Maven Central or an offline mirror, see README).
# ---------------------------------------------------------------------------------------------------------------
FROM ${MAVEN_IMAGE} AS build
ARG MAVEN_ARGS="-B -ntp"
ARG MARIADB_VERSION=3.5.3
ARG APP_MODULE
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn ${MAVEN_ARGS} -DskipTests -pl "${APP_MODULE}" -am package \
 && mkdir -p /out/lib-ext \
 && jar=$(ls "${APP_MODULE}"/target/"${APP_MODULE}"-*.jar | grep -v -e '\.original$' -e '-plain\.jar$' -e '-sources\.jar$' | head -n 1) \
 && test -n "$jar" \
 && cp "$jar" /out/app.jar \
 && mvn ${MAVEN_ARGS} -q dependency:copy \
        -Dartifact=org.mariadb.jdbc:mariadb-java-client:${MARIADB_VERSION} -DoutputDirectory=/out/lib-ext \
 && mvn ${MAVEN_ARGS} -q dependency:copy \
        -Dartifact=org.mariadb.jdbc:mariadb-java-client:${MARIADB_VERSION}:jar:sources -DoutputDirectory=/out/lib-ext \
        || echo "WARN: MariaDB sources jar not fetched; the source offer text still applies"

# ---------------------------------------------------------------------------------------------------------------
# Stage: take a jar built elsewhere (CI artefact, air-gapped build host).
# ---------------------------------------------------------------------------------------------------------------
FROM ${MAVEN_IMAGE} AS prebuilt
ARG PREBUILT_JAR=sangam-server/target/sangam-server.jar
ARG PREBUILT_LIB_EXT=deploy/docker/lib-ext
COPY ${PREBUILT_JAR} /out/app.jar
# Optional directory holding mariadb-java-client-*.jar (CI copies it there with dependency:copy).
COPY ${PREBUILT_LIB_EXT}/ /out/lib-ext/

FROM ${JAR_SOURCE} AS jar

# ---------------------------------------------------------------------------------------------------------------
# Stage: common file layout + licence guard.
# ---------------------------------------------------------------------------------------------------------------
FROM ${MAVEN_IMAGE} AS layout
COPY --from=jar /out/ /layout/opt/sangam/
COPY deploy/docker/server/SOURCE-OFFER.txt /layout/opt/sangam/lib-ext/SOURCE-OFFER.txt
COPY deploy/docker/server/drivers-README.txt /layout/opt/sangam/drivers/README.txt
# The last command is the licence guard (LIC-1/2/3/4/5/10): the build fails if a forbidden driver or script runtime
# ended up inside the fat jar.
RUN mkdir -p /layout/opt/sangam/config /layout/var/lib/sangam/compliance-logs /layout/var/lib/sangam/cell-exports \
 && chown -R 10001:0 /layout/opt/sangam /layout/var/lib/sangam \
 && chmod -R g=u /layout/opt/sangam /layout/var/lib/sangam \
 && if jar tf /layout/opt/sangam/app.jar | grep -E -i 'BOOT-INF/lib/(ojdbc|ucp|db2jcc|jcc-|mysql-connector|mariadb-java-client|js-[0-9]|polyglot|rhino|nashorn|jython|jruby)'; then \
        echo "ERROR: forbidden driver/runtime inside the fat jar (SANGAM.md X.3a)"; exit 1; fi

# ---------------------------------------------------------------------------------------------------------------
# Runtime (default): Eclipse Temurin JRE 25 on Ubuntu, non-root uid 10001. Has a shell for ops/debug and the
# compose health check.
# ---------------------------------------------------------------------------------------------------------------
FROM ${JRE_IMAGE} AS runtime
ARG APP_MODULE
LABEL org.opencontainers.image.title="${APP_MODULE}" \
      org.opencontainers.image.description="Sangam workflow platform (${APP_MODULE}), based on Operaton 2.2.0" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.source="https://github.com/sqamentor/sangam"
RUN groupadd --system --gid 10001 sangam \
 && useradd --system --uid 10001 --gid 0 --home-dir /opt/sangam --shell /usr/sbin/nologin sangam
COPY --from=layout /layout/opt/sangam /opt/sangam
COPY --from=layout /layout/var/lib/sangam /var/lib/sangam
ENV TZ=UTC \
    LANG=C.UTF-8 \
    SANGAM_HOME=/opt/sangam \
    SANGAM_APP=${APP_MODULE} \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -XX:+UseG1GC -Duser.timezone=UTC -Dfile.encoding=UTF-8 -Djava.io.tmpdir=/tmp" \
    SANGAM_COMPLIANCE_LOG_ARCHIVE=/var/lib/sangam/compliance-logs \
    SANGAM_CELL_EXPORT_DIR=/var/lib/sangam/cell-exports \
    SPRING_CONFIG_ADDITIONAL_LOCATION=optional:file:/opt/sangam/config/
WORKDIR /opt/sangam
USER 10001:0
EXPOSE 8080 8081 8000 8001 8090 8091 8095 8096
# Spring Boot's PropertiesLauncher reads Start-Class from the manifest and adds loader.path to the class path.
ENTRYPOINT ["java", "-Dloader.path=/opt/sangam/lib-ext,/opt/sangam/drivers,/opt/sangam/config", "-cp", "/opt/sangam/app.jar", "org.springframework.boot.loader.launch.PropertiesLauncher"]

# ---------------------------------------------------------------------------------------------------------------
# Runtime (distroless): no shell, no package manager, uid 65532 ("nonroot"). Use with Kubernetes httpGet probes.
# ---------------------------------------------------------------------------------------------------------------
FROM ${DISTROLESS_IMAGE} AS runtime-distroless
ARG APP_MODULE
LABEL org.opencontainers.image.title="${APP_MODULE}" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.source="https://github.com/sqamentor/sangam"
COPY --from=layout --chown=65532:0 /layout/opt/sangam /opt/sangam
COPY --from=layout --chown=65532:0 /layout/var/lib/sangam /var/lib/sangam
ENV TZ=UTC \
    LANG=C.UTF-8 \
    SANGAM_HOME=/opt/sangam \
    SANGAM_APP=${APP_MODULE} \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -XX:+UseG1GC -Duser.timezone=UTC -Dfile.encoding=UTF-8 -Djava.io.tmpdir=/tmp" \
    SANGAM_COMPLIANCE_LOG_ARCHIVE=/var/lib/sangam/compliance-logs \
    SANGAM_CELL_EXPORT_DIR=/var/lib/sangam/cell-exports \
    SPRING_CONFIG_ADDITIONAL_LOCATION=optional:file:/opt/sangam/config/
WORKDIR /opt/sangam
USER 65532:0
EXPOSE 8080 8081 8000 8001 8090 8091 8095 8096
ENTRYPOINT ["java", "-Dloader.path=/opt/sangam/lib-ext,/opt/sangam/drivers,/opt/sangam/config", "-cp", "/opt/sangam/app.jar", "org.springframework.boot.loader.launch.PropertiesLauncher"]

# The last stage is the default target.
FROM runtime

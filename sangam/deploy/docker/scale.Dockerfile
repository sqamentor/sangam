# syntax=docker/dockerfile:1.7
# SPDX-License-Identifier: Apache-2.0
#
# Images of the SCALE apps: control plane, gateway, national roll-up. Build context: sangam/.
#   docker build -f deploy/docker/scale.Dockerfile --build-arg MODULE=sangam-control-plane -t sangam-control-plane:dev .
#   docker build -f deploy/docker/scale.Dockerfile --build-arg MODULE=sangam-gateway       -t sangam-gateway:dev .
#   docker build -f deploy/docker/scale.Dockerfile --build-arg MODULE=sangam-rollup        -t sangam-rollup:dev .
# Licence rules as server.Dockerfile: no Oracle/Db2/Connector-J driver; MariaDB only via /opt/sangam/lib-ext.
# Base images are pinned by index digest in the FROM lines (decisions 253, 261; lookup in DOWNLOADS-WAVE4.md).

FROM maven:3.9-eclipse-temurin-26@sha256:b2c1ad85954592f9928e84327c65201f308ad9b5d8ed7d5b823717c97bf23fbb AS build
ARG MODULE
ARG MAVEN_ARGS="-B -ntp"
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    test -n "${MODULE}" \
 && mvn ${MAVEN_ARGS} -DskipTests -pl ${MODULE} -am package \
 && jar=$(ls ${MODULE}/target/${MODULE}-*.jar | grep -v -e '\.original$' -e '-plain\.jar$' -e '-sources\.jar$' | head -n 1) \
 && cp "$jar" /out.jar

FROM eclipse-temurin:25.0.5_8-jre-noble@sha256:e3b6f7887a50f20d8f07abf279d8a81a5953fb398a9555bd9edd56b9bd15968c AS runtime
ARG MODULE
LABEL org.opencontainers.image.title="${MODULE}" org.opencontainers.image.licenses="Apache-2.0"
RUN groupadd -g 10001 sangam && useradd -u 10001 -g sangam -M -d /opt/sangam sangam \
 && mkdir -p /opt/sangam/lib-ext /opt/sangam/drivers /opt/sangam/data && chown -R sangam:sangam /opt/sangam
COPY --from=build /out.jar /opt/sangam/app.jar
USER 10001
WORKDIR /opt/sangam
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC -Dfile.encoding=UTF-8" \
    LOADER_PATH=/opt/sangam/lib-ext,/opt/sangam/drivers
ENTRYPOINT ["java", "-jar", "/opt/sangam/app.jar"]

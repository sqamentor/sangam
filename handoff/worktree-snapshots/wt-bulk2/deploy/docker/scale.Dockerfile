# syntax=docker/dockerfile:1.7
# SPDX-License-Identifier: Apache-2.0
#
# Images of the SCALE apps: control plane, gateway, national roll-up. Build context: sangam/.
#   docker build -f deploy/docker/scale.Dockerfile --build-arg MODULE=sangam-control-plane -t sangam-control-plane:dev .
#   docker build -f deploy/docker/scale.Dockerfile --build-arg MODULE=sangam-gateway       -t sangam-gateway:dev .
#   docker build -f deploy/docker/scale.Dockerfile --build-arg MODULE=sangam-rollup        -t sangam-rollup:dev .
# Licence rules as server.Dockerfile: no Oracle/Db2/Connector-J driver; MariaDB only via /opt/sangam/lib-ext.
ARG MAVEN_IMAGE=maven:3.9.11-eclipse-temurin-21
ARG JRE_IMAGE=eclipse-temurin:21.0.8_9-jre-noble

FROM ${MAVEN_IMAGE} AS build
ARG MODULE
ARG MAVEN_ARGS="-B -ntp"
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    test -n "${MODULE}" \
 && mvn ${MAVEN_ARGS} -DskipTests -pl ${MODULE} -am package \
 && jar=$(ls ${MODULE}/target/${MODULE}-*.jar | grep -v -e '\.original$' -e '-plain\.jar$' -e '-sources\.jar$' | head -n 1) \
 && cp "$jar" /out.jar

FROM ${JRE_IMAGE} AS runtime
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

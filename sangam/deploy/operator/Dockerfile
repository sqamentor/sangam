# syntax=docker/dockerfile:1.7
# SPDX-License-Identifier: Apache-2.0
#
# M-090: the Sangam operator image. Build context: sangam/.
#   docker build -f deploy/operator/Dockerfile -t sangam-operator:dev .
# Same base images as deploy/docker/scale.Dockerfile, pinned by index digest in the FROM lines (decisions 253, 261).
# Built from the reactor (-pl deploy/operator -am) because the operator depends on sangam-kube (decision 155).
# Base images are pinned by index digest in the FROM lines (decisions 253, 261; lookup in DOWNLOADS-WAVE4.md).

FROM maven:3.9.11-eclipse-temurin-21@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237 AS build
ARG MAVEN_ARGS="-B -ntp"
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn ${MAVEN_ARGS} -DskipTests -pl deploy/operator -am package \
 && cp deploy/operator/target/sangam-operator.jar /out.jar

FROM eclipse-temurin:24.0.2_12-jre-noble@sha256:b416d02335e702b0403ff280de9475a3348e29382285969c9d4e17862ce632e7 AS runtime
LABEL org.opencontainers.image.title="sangam-operator" org.opencontainers.image.licenses="Apache-2.0"
RUN groupadd -g 10001 sangam && useradd -u 10001 -g sangam -M -d /opt/sangam sangam \
 && mkdir -p /opt/sangam && chown -R sangam:sangam /opt/sangam
COPY --from=build /out.jar /opt/sangam/operator.jar
USER 10001
WORKDIR /opt/sangam
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC -Dfile.encoding=UTF-8 -Djava.io.tmpdir=/tmp"
ENTRYPOINT ["java", "-jar", "/opt/sangam/operator.jar"]

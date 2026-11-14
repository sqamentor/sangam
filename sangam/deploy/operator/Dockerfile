# syntax=docker/dockerfile:1.7
# SPDX-License-Identifier: Apache-2.0
#
# M-090: the Sangam operator image. Build context: sangam/.
#   docker build -f deploy/operator/Dockerfile -t sangam-operator:dev .
# Same base images as deploy/docker/scale.Dockerfile, pinned by index digest in the FROM lines (decisions 253, 261).
# Built from the reactor (-pl deploy/operator -am) because the operator depends on sangam-kube (decision 155).
# Base images are pinned by index digest in the FROM lines (decisions 253, 261; lookup in DOWNLOADS-WAVE4.md).

FROM maven:3.9-eclipse-temurin-26@sha256:b2c1ad85954592f9928e84327c65201f308ad9b5d8ed7d5b823717c97bf23fbb AS build
ARG MAVEN_ARGS="-B -ntp"
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn ${MAVEN_ARGS} -DskipTests -pl deploy/operator -am package \
 && cp deploy/operator/target/sangam-operator.jar /out.jar

FROM eclipse-temurin:21.0.8_9-jre-noble@sha256:20e7f7288e1c18eebe8f06a442c9f7183342d9b022d3b9a9677cae2b558ddddd AS runtime
LABEL org.opencontainers.image.title="sangam-operator" org.opencontainers.image.licenses="Apache-2.0"
RUN groupadd -g 10001 sangam && useradd -u 10001 -g sangam -M -d /opt/sangam sangam \
 && mkdir -p /opt/sangam && chown -R sangam:sangam /opt/sangam
COPY --from=build /out.jar /opt/sangam/operator.jar
USER 10001
WORKDIR /opt/sangam
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC -Dfile.encoding=UTF-8 -Djava.io.tmpdir=/tmp"
ENTRYPOINT ["java", "-jar", "/opt/sangam/operator.jar"]

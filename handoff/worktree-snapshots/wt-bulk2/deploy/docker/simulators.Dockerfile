# syntax=docker/dockerfile:1.7
# SPDX-License-Identifier: Apache-2.0
#
# Government-system simulators (IFMS/JIT payment, PFMS SFTP files, eSign ESP, DLT SMS, DigiLocker issuer push,
# payment gateway, e-mail) for development, CI and pilot demos. NEVER deploy them in production.
#
# One image, one simulator per container, chosen with SANGAM_SIM_KIND. Build context: `sangam/`.
#   docker build -f deploy/docker/simulators.Dockerfile -t sangam-simulators:dev .
#
# Runtime contract (implemented by the simulator launcher in sangam-integration, see deploy/README.md
# "Integration notes"):
#   SANGAM_SIM_KIND   ifms | pfms | esign | dlt | digilocker | paygov | smtp
#   SANGAM_SIM_BIND   bind address (default 0.0.0.0 inside the container; the in-process test servers bind loopback)
#   SANGAM_SIM_PORT   HTTP port (default 9000)
#   SANGAM_SIM_DATA   working directory (PFMS inbound/outbound files, ledgers), default /data
#   SANGAM_SIM_FAULTS optional fault script (JSON) applied at start (FaultInjector)
#   GET /__sim/health, GET /__sim/ledger, POST /__sim/faults are the control endpoints.

ARG MAVEN_IMAGE=maven:3.9.11-eclipse-temurin-21
ARG JRE_IMAGE=eclipse-temurin:21.0.8_9-jre-noble
ARG JAR_SOURCE=build

FROM ${MAVEN_IMAGE} AS build
ARG MAVEN_ARGS="-B -ntp"
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn ${MAVEN_ARGS} -DskipTests -pl sangam-integration -am package -Psimulators-app \
 && mkdir -p /out \
 && jar=$(ls sangam-integration/target/*-simulators.jar | head -n 1) \
 && test -n "$jar" \
 && cp "$jar" /out/simulators.jar

FROM ${MAVEN_IMAGE} AS prebuilt
ARG PREBUILT_JAR=sangam-integration/target/sangam-integration-simulators.jar
COPY ${PREBUILT_JAR} /out/simulators.jar

FROM ${JAR_SOURCE} AS jar

FROM ${JRE_IMAGE} AS runtime
LABEL org.opencontainers.image.title="sangam-simulators" \
      org.opencontainers.image.description="Sangam government-system simulators (dev/test only)" \
      org.opencontainers.image.licenses="Apache-2.0"
RUN groupadd --system --gid 10001 sangam \
 && useradd --system --uid 10001 --gid 0 --home-dir /opt/sangam-sim --shell /usr/sbin/nologin sangam \
 && mkdir -p /data /opt/sangam-sim && chown 10001:0 /data /opt/sangam-sim && chmod g=u /data /opt/sangam-sim
COPY --from=jar --chown=10001:0 /out/simulators.jar /opt/sangam-sim/simulators.jar
ENV TZ=UTC LANG=C.UTF-8 \
    SANGAM_SIM_BIND=0.0.0.0 SANGAM_SIM_PORT=9000 SANGAM_SIM_DATA=/data \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -Duser.timezone=UTC -Dfile.encoding=UTF-8"
USER 10001:0
VOLUME ["/data"]
EXPOSE 9000
HEALTHCHECK --interval=15s --timeout=3s --retries=5 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/${SANGAM_SIM_PORT} && printf "GET /__sim/health HTTP/1.0\r\n\r\n" >&3 && grep -q "200" <&3' || exit 1
ENTRYPOINT ["java", "-jar", "/opt/sangam-sim/simulators.jar"]

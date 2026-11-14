# syntax=docker/dockerfile:1.7
# SPDX-License-Identifier: Apache-2.0
#
# Sangam PDF validator (M-081; owner decisions 94, 102, 114, 119, 153; RM-26; SL-178). A small, stateless HTTPS service
# that checks PDF bytes against PDF/A-2b and PDF/UA-1 with veraPDF and answers a report. The Sangam server calls it
# through RemotePdfValidator (SANGAM_PDF_VALIDATOR_URL=https://<this service>:8085 plus
# SANGAM_PDF_VALIDATOR_TOKEN_FILE); without it, PDF output answers 422 FORMAT_NOT_AVAILABLE.
#
# This image is the ONLY distribution that contains org.mozilla:rhino (veraPDF needs it to evaluate its profile rules;
# decision 119 scopes the LIC-10 Rhino entry to this image). The server image and its fat jar never contain Rhino or
# veraPDF (server.Dockerfile licence guard, PdfValidatorReactorScopeTest).
#
# Build context: `sangam/`.
#   docker build -f deploy/docker/pdf-validator.Dockerfile -t sangam-pdf-validator:dev .
#
# Access (owner decision 153): every call except GET /health carries "Authorization: Bearer <token>"; the accepted
# tokens are read from the mounted secret file SANGAM_PDF_VALIDATOR_TOKEN_FILE (one per line; list old and new during a
# rotation, remove a line to revoke; re-read without a restart). Without that file the service starts NOT_READY and
# refuses every validation. Tokens are never baked into the image, passed as arguments or put in URLs. One shared token
# cannot tell callers apart: prefer one line per caller and give the file only to the server. The token authenticates
# the server only; user permissions and tenant ownership stay in the server. Keep the port on the private network as
# well (network policy), and give the container no outbound network: it needs none.
# Transport: SANGAM_PDF_VALIDATOR_TRANSPORT=tls (default) needs SANGAM_PDF_VALIDATOR_TLS_KEYSTORE (PKCS#12) and
# SANGAM_PDF_VALIDATOR_TLS_KEYSTORE_PASSWORD_FILE, both mounted secrets; =mesh serves plain HTTP only behind an
# authenticated service mesh. TLS without a key store refuses to start.
# Limits: SANGAM_PDF_VALIDATOR_PORT (8085), SANGAM_PDF_VALIDATOR_BIND (0.0.0.0), SANGAM_PDF_VALIDATOR_THREADS
# (processors; more at once answer 503), SANGAM_PDF_VALIDATOR_MAX_BYTES (67108864; larger answer 413),
# SANGAM_PDF_VALIDATOR_PROCESSING_TIMEOUT_MS (120000; longer answer 503). Run the container read-only with a size-limited
# /tmp (tmpfs) for veraPDF's temporary files.
#
# Base images: the server's Maven and JRE images (deploy/docker/server.Dockerfile), pinned by index digest in the
# FROM lines (RM-33; decisions 253, 261).

# Base images are pinned by index digest in the FROM lines (decisions 253, 261; lookup in DOWNLOADS-WAVE4.md).

# Stage 1: the validator jar and its runtime class path (sangam-integration, veraPDF, Rhino, Jackson).
FROM maven:3.9.11-eclipse-temurin-25@sha256:407c4423cec0cf2981055bc2c6c0dc211d9605b6669279b95997f2d1c7e91e2c AS jars
ARG MAVEN_ARGS="-B -ntp"
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn ${MAVEN_ARGS} -DskipTests -pl sangam-pdf-validator -am install \
 && mvn ${MAVEN_ARGS} -q -pl sangam-pdf-validator dependency:copy-dependencies \
        -DincludeScope=runtime -DoutputDirectory=/out/lib \
 && cp sangam-pdf-validator/target/sangam-pdf-validator-*.jar /out/sangam-pdf-validator.jar \
 && test -n "$(ls /out/lib | grep -E '^validation-model-jakarta-')" \
 && if ls /out/lib | grep -E -i '^(js-[0-9]|polyglot|nashorn|jython|jruby|ojdbc|db2jcc|mysql-connector)'; then \
        echo "ERROR: a forbidden runtime or driver is on the validator class path (LIC-1/2/3/4/10)"; exit 1; fi \
 && if ls /out/lib | grep -E '^rhino-' | grep -v -x 'rhino-1.7.15.1.jar'; then \
        echo "ERROR: only the Rhino version veraPDF 1.30.4 declares is allowed (decision 119)"; exit 1; fi \
 && ls -l /out /out/lib

# Stage 2: runtime. Non-root, read-only friendly (no writable state; temporary files only under /tmp).
FROM eclipse-temurin:26.0.2.1_1-jre-noble@sha256:adfd44c114916dc79fa9a24087fbac34252096edb9892d1c2d9d82db3f8947b3 AS runtime
LABEL org.opencontainers.image.title="sangam-pdf-validator" \
      org.opencontainers.image.description="Sangam PDF validator: PDF/A-2b and PDF/UA-1 checks with veraPDF (owner decision 114)" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.source="https://github.com/sqamentor/sangam"
RUN groupadd --system --gid 10001 sangam \
 && useradd --system --uid 10001 --gid 0 --home-dir /opt/sangam --shell /usr/sbin/nologin sangam
COPY --from=jars /out/ /opt/sangam/pdf-validator/
COPY deploy/docker/pdf-validator/NOTICE.txt /licenses/sangam-pdf-validator/NOTICE.txt
ENV TZ=UTC \
    LANG=C.UTF-8 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC -Dfile.encoding=UTF-8 -Djava.io.tmpdir=/tmp" \
    SANGAM_PDF_VALIDATOR_PORT=8085
WORKDIR /opt/sangam/pdf-validator
USER 10001:0
EXPOSE 8085
ENTRYPOINT ["java", "-jar", "/opt/sangam/pdf-validator/sangam-pdf-validator.jar"]

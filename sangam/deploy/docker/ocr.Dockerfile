# syntax=docker/dockerfile:1.7
# SPDX-License-Identifier: Apache-2.0
#
# Sangam OCR sidecar (M-103; owner decisions 153, 157, 167, on the decision-114 pattern). A small, stateless HTTPS
# service that reads text from PNG, JPEG and TIFF images with the Tesseract command-line program and answers JSON. The
# Sangam server calls it through org.sangam.integration.document.ocr.RemoteOcrClient (SANGAM_OCR_URL=https://<this
# service>:8086 plus SANGAM_OCR_TOKEN_FILE); without it OCR is unavailable and nothing is guessed. Optional: nothing in
# the server needs this image.
#
# Tesseract runs as a child process per image (no shell, fixed arguments, one thread, a time limit, killed on timeout);
# no OCR library or native binding enters any JVM.
#
# Build context: `sangam/`.
#   docker build -f deploy/docker/ocr.Dockerfile --build-arg UBUNTU_SNAPSHOT=<YYYYMMDDTHHMMSSZ> -t sangam-ocr:dev .
# UBUNTU_SNAPSHOT is REQUIRED (owner decision 157): every apt call resolves against that dated snapshot of the Ubuntu
# archive (snapshot.ubuntu.com), so a rebuild with the same ID gets the same Tesseract, Leptonica and language-data
# versions; the resolved versions are written to /licenses/OS-PACKAGES.txt. The apt snapshot syntax is NOT_RUN until
# the first test-phase build verifies it. Decision 106 approved the Tesseract image inputs for the test phase only.
#
# Access (owner decision 153): every call except GET /health carries "Authorization: Bearer <token>"; the accepted tokens
# are read from the mounted secret file SANGAM_OCR_TOKEN_FILE (one per line; list old and new during a rotation, remove a
# line to revoke; re-read without a restart). Without it the service starts NOT_READY and refuses every call. Tokens are
# never baked into the image, passed as arguments or put in URLs. Prefer one token line per caller; give the file only to
# the server. The token authenticates the server only: user permissions and tenant ownership stay in the server. Keep the
# port on the private network (network policy) and give the container no outbound network: it needs none.
# Transport: SANGAM_OCR_TRANSPORT=tls (default) needs SANGAM_OCR_TLS_KEYSTORE (PKCS#12) and
# SANGAM_OCR_TLS_KEYSTORE_PASSWORD_FILE, both mounted secrets; =mesh serves plain HTTP only behind an authenticated
# service mesh. TLS without a key store refuses to start.
# Limits: SANGAM_OCR_PORT (8086), SANGAM_OCR_BIND (0.0.0.0), SANGAM_OCR_THREADS (processors; more at once answer 503),
# SANGAM_OCR_MAX_BYTES (33554432; larger answer 413), SANGAM_OCR_MAX_PIXELS (100000000; larger answer 413),
# SANGAM_OCR_PROCESSING_TIMEOUT_MS (120000; longer answer 503), SANGAM_OCR_LANGUAGES (eng,hin; each must be installed,
# or the start stops), SANGAM_OCR_TEMP (/tmp). Run the container read-only with a size-limited /tmp (tmpfs): each request
# writes one image there and removes it afterwards.
#
# Base images: the server's Maven and JRE images (deploy/docker/server.Dockerfile), pinned by index digest in the
# FROM lines (RM-33; decisions 253, 261).

# Base images are pinned by index digest in the FROM lines (decisions 253, 261; lookup in DOWNLOADS-WAVE4.md).

# Stage 1: the sidecar jar and its runtime class path (sangam-integration, Jackson).
FROM maven:3.9-eclipse-temurin-26@sha256:b2c1ad85954592f9928e84327c65201f308ad9b5d8ed7d5b823717c97bf23fbb AS jars
ARG MAVEN_ARGS="-B -ntp"
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn ${MAVEN_ARGS} -DskipTests -pl sangam-ocr-sidecar -am install \
 && mvn ${MAVEN_ARGS} -q -pl sangam-ocr-sidecar dependency:copy-dependencies \
        -DincludeScope=runtime -DoutputDirectory=/out/lib \
 && cp sangam-ocr-sidecar/target/sangam-ocr-sidecar-*.jar /out/sangam-ocr-sidecar.jar \
 && if ls /out/lib | grep -E -i '^(rhino-|js-[0-9]|polyglot|nashorn|jython|jruby|tess4j|jna-|ojdbc|db2jcc|mysql-connector)'; then \
        echo "ERROR: a forbidden runtime, native binding or driver is on the OCR class path"; exit 1; fi \
 && ls -l /out /out/lib

# Stage 2: runtime. Non-root, read-only friendly (no writable state; temporary files only under /tmp).
FROM eclipse-temurin:25.0.1_8-jre-noble@sha256:ecbdcdbfae44ee61794a8ad36042b6b8e3c3124e5e9c171c3630fcd5ab856e33 AS runtime
ARG UBUNTU_SNAPSHOT
LABEL org.opencontainers.image.title="sangam-ocr" \
      org.opencontainers.image.description="Sangam OCR sidecar: text from images with the Tesseract program (owner decision 167)" \
      org.opencontainers.image.source="https://github.com/sqamentor/sangam" \
      org.sangam.ubuntu.snapshot="${UBUNTU_SNAPSHOT}"
RUN printf '%s\n' "${UBUNTU_SNAPSHOT}" | grep -Eqx '[0-9]{8}T[0-9]{6}Z' \
      || { echo "UBUNTU_SNAPSHOT is required as YYYYMMDDTHHMMSSZ (decision 157)"; exit 1; } \
 && dpkg-query -W -f='${Package}\n' | sort > /tmp/packages-before.txt \
 && apt-get update --snapshot "${UBUNTU_SNAPSHOT}" \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends --snapshot "${UBUNTU_SNAPSHOT}" \
      tesseract-ocr tesseract-ocr-eng tesseract-ocr-hin \
 && rm -rf /var/lib/apt/lists/* \
 && dpkg-query -W -f='${Package}\n' | sort > /tmp/packages-after.txt \
 && mkdir -p /licenses/sangam-ocr/os-packages \
 && for p in $(comm -13 /tmp/packages-before.txt /tmp/packages-after.txt); do \
        if [ -f "/usr/share/doc/$p/copyright" ]; then cp "/usr/share/doc/$p/copyright" "/licenses/sangam-ocr/os-packages/$p.copyright"; fi; \
    done \
 && dpkg-query -W -f='${Package} ${Version}\n' $(comm -13 /tmp/packages-before.txt /tmp/packages-after.txt) \
        > /licenses/OS-PACKAGES.txt \
 && rm -f /tmp/packages-before.txt /tmp/packages-after.txt \
 && tesseract --version \
 && tesseract --list-langs
RUN groupadd --system --gid 10001 sangam \
 && useradd --system --uid 10001 --gid 0 --home-dir /opt/sangam --shell /usr/sbin/nologin sangam
COPY --from=jars /out/ /opt/sangam/ocr/
COPY deploy/docker/ocr/NOTICE.txt /licenses/sangam-ocr/NOTICE.txt
# The OS-package source offer (decision 118). One shared text for every image, owned by W4-C.
COPY deploy/licence/OS-PACKAGES-SOURCE-OFFER.txt /licenses/OS-PACKAGES-SOURCE-OFFER.txt
ENV TZ=UTC \
    LANG=C.UTF-8 \
    OMP_THREAD_LIMIT=1 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC -Dfile.encoding=UTF-8 -Djava.io.tmpdir=/tmp" \
    SANGAM_OCR_PORT=8086 \
    SANGAM_OCR_TESSERACT=/usr/bin/tesseract \
    SANGAM_OCR_TEMP=/tmp
WORKDIR /opt/sangam/ocr
USER 10001:0
EXPOSE 8086
ENTRYPOINT ["java", "-jar", "/opt/sangam/ocr/sangam-ocr-sidecar.jar"]

# SPDX-License-Identifier: Apache-2.0
# ClusterFuzzLite build image. Pulled and built only inside GitHub Actions (cflite.yml), never on a workstation.
# Base pinned by digest (Scorecard PinnedDependencies). The base ships an older JDK; Sangam compiles for Java 25, so
# Temurin 25.0.5+8 (the JDK the build uses everywhere else) is added, checked against its published SHA-256.
FROM gcr.io/oss-fuzz-base/base-builder-jvm@sha256:bd13a763de1a0fb22099442094e7e0049657a8c47f01e9d7af8bb849e0ad4699
ARG JDK_URL=https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5%2B8/OpenJDK25U-jdk_x64_linux_hotspot_25.0.5_8.tar.gz
ARG JDK_SHA256=68982529869257d0ee6b824ec5aa270ff8b118bb438670da0b97ebdab178d7fb
RUN curl -fsSL -o /tmp/jdk.tgz "$JDK_URL" \
 && echo "$JDK_SHA256  /tmp/jdk.tgz" | sha256sum -c - \
 && mkdir -p /opt/jdk25 && tar -xzf /tmp/jdk.tgz -C /opt/jdk25 --strip-components=1 && rm /tmp/jdk.tgz
COPY . $SRC/sangam-repo
WORKDIR $SRC/sangam-repo
COPY .clusterfuzzlite/build.sh $SRC/

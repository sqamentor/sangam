#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Print the Actions cache key of the pinned Maven distribution: derived only from distributionUrl and
# distributionSha256Sum, so it changes exactly when the pinned version (or its checksum) does.
set -euo pipefail
props=sangam/.mvn/wrapper/maven-wrapper.properties
url=$(sed -n 's/^distributionUrl=//p' "$props" | tr -d '\r')
sum=$(sed -n 's/^distributionSha256Sum=//p' "$props" | tr -d '\r')
[ -n "$url" ] && [ -n "$sum" ] || { echo "::error::$props lacks distributionUrl or distributionSha256Sum" >&2; exit 1; }
echo "key=sangam-maven-dist-v1-$(printf '%s\n%s\n' "$url" "$sum" | sha256sum | cut -c1-64)"

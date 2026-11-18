#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Print how many artifacts this job fetched from the remote repositories after the cache restore (evidence for 429 fix).
set -uo pipefail
m="$RUNNER_TEMP/sangam-m2-restored"
[ -f "$m" ] || { echo "maven fetch count: no restore marker (maven-cache action not run)"; exit 0; }
n=$(find "$HOME/.m2/repository" -newer "$m" -type f \( -name '*.jar' -o -name '*.pom' \) ! -path '*/org/sangam/*' 2>/dev/null | wc -l)
echo "maven fetch count: $n jar/pom files fetched from remote repositories in this job" | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"

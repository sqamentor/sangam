#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# The single cold fetch of the shared Maven cache (ci-lanes `deps` job only). Serial (no -T) to keep the burst on
# repo.maven.apache.org small. Transient answers are retried, within bounds: the resolver retries HTTP 429/503 itself
# and waits as Retry-After asks (retryHandler.*), and this script retries the whole resolution at most 3 times when the
# log shows a 429/503 or an artifact Central reported as absent. Before each retry it deletes the resolver's cached
# "not found" markers (*.lastUpdated), so a transient 404 is not remembered. Any other failure, or a third failure,
# fails the job with the Maven log. Nothing is skipped and no checksum policy changes.
set -uo pipefail
args=("$@")
retry=(-Daether.connector.http.retryHandler.count=5
       -Daether.connector.http.retryHandler.interval=10000
       -Daether.connector.http.retryHandler.intervalMax=300000
       -Daether.connector.http.retryHandler.serviceUnavailable=429,503)
transient='status code: (429|503)|Too Many Requests|\(absent\)|Could not (find|transfer) artifact|The POM for \S+ is missing'
for attempt in 1 2 3; do
  log="$RUNNER_TEMP/maven-resolve-$attempt.log"
  if bash ./mvnw $MAVEN_ARGS "${retry[@]}" "${args[@]}" 2>&1 | tee "$log" && ! grep -Eq "The POM for \S+ is missing" "$log"; then
    echo "maven resolution: done on attempt $attempt"
    exit 0
  fi
  if [ "$attempt" -lt 3 ] && grep -Eq "$transient" "$log"; then
    n=$(find "$HOME/.m2/repository" -name '*.lastUpdated' 2>/dev/null | wc -l)
    find "$HOME/.m2/repository" -name '*.lastUpdated' -delete 2>/dev/null || true
    s=$(( attempt * 60 ))
    echo "::warning title=maven resolution::attempt $attempt failed on a transient Maven Central answer (429/503/absent); cleared $n not-found markers, retrying in ${s}s"
    grep -E "$transient" "$log" | head -5
    sleep "$s"
    continue
  fi
  echo "::error title=maven resolution::failed on attempt $attempt (not transient, or the last attempt); see the Maven log above"
  exit 1
done

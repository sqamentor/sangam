#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# R52: produces the sangam-compliance evidence report for a release. Starts the release jar as a local process
# (profile h2, dev token, loopback only, no container), generates the report with POST /api/v1/compliance/reports
# (JSON body, one Idempotency-Key per call; generation writes CMP_REPORT and an audit event, so it is not a GET), once
# as JSON and once as HTML, stops.
# The result describes a fresh instance with no tenant data; see the scope note in the pack's INDEX.md.
#
# Usage: generate_compliance_report.sh <server-jar> <output-dir>
set -euo pipefail
JAR="$1"; OUT="$2"
mkdir -p "$OUT"
WORK="$(mktemp -d)"
TOKEN="evidence-$(date +%s)-$$-dev-token"
PORT=18080; MGMT=18081

( cd "$WORK" && SANGAM_PORT=$PORT SANGAM_MANAGEMENT_PORT=$MGMT SANGAM_DEV_TOKEN="$TOKEN" SANGAM_TENANT_DEFAULT=dev \
    SERVER_ADDRESS=127.0.0.1 SPRING_PROFILES_ACTIVE=h2 SANGAM_COMPLIANCE_ORGANISATION="Sangam release pipeline" \
    exec java -Duser.timezone=UTC -jar "$(realpath "$JAR")" ) > "$OUT/server-start.log" 2>&1 &
PID=$!
# guard-allow: EXIT cleanup is best effort: the server may already have exited (kill fails) and wait returns its kill status; under set -e either would stop the trap before rm -rf, and the script's own exit status is already decided
trap 'kill "$PID" 2>/dev/null || true; wait "$PID" 2>/dev/null || true; rm -rf "$WORK"' EXIT

for _ in $(seq 1 90); do
  curl -fsS "http://127.0.0.1:$PORT/readyz" >/dev/null 2>&1 && break
  kill -0 "$PID" 2>/dev/null || { echo "server exited; see $OUT/server-start.log" >&2; exit 1; }
  sleep 2
done
curl -fsS "http://127.0.0.1:$PORT/readyz" >/dev/null

FROM="$(date -u -d '1 day ago' +%Y-%m-%dT%H:%M:%SZ)"; TO="$(date -u -d '1 hour' +%Y-%m-%dT%H:%M:%SZ)"
for fmt in json html; do
  curl -fsS -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -H "Idempotency-Key: evidence-report-$fmt-$(date +%s)-$$" \
    --data "{\"from\":\"$FROM\",\"to\":\"$TO\",\"verifyChains\":true,\"format\":\"$fmt\"}" \
    "http://127.0.0.1:$PORT/api/v1/compliance/reports" \
    -o "$OUT/compliance-evidence-report.$fmt"
done
test -s "$OUT/compliance-evidence-report.json" && test -s "$OUT/compliance-evidence-report.html"
echo "compliance evidence report written to $OUT"

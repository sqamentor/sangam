#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Registers the two local cells and keeps heartbeating them, until the in-server CellRegistrationAgent is wired
# (sangam-cell README IR-2). Development tokens only.
set -u
CP=${CP:-http://control-plane:8090}
TOKEN=${TOKEN:-dev-cell-token}
register() {
  curl -fsS -X PUT -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d "{\"region\":\"$1\",\"jurisdiction\":\"$2\",\"residency\":\"$2\",\"apiBaseUrl\":\"$3\",\"adminBaseUrl\":\"$3\",\"dbVendor\":\"postgres\",\"capacityStartsPerSec\":500,\"capacityTenants\":2000}" \
    "$CP/api/v1/cells/$1" >/dev/null
}
beat() {
  curl -fsS -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d '{"status":"UP","startsPerSec":0,"p99CommandMs":0,"activeNodes":1,"dbPoolUsagePct":0,"outboxFillPct":0,"tenants":0}' \
    "$CP/api/v1/cells/$1/heartbeat" >/dev/null
}
# A failed heartbeat is reported, never hidden. The usual cause is a control plane that restarted and forgot the cell
# (or is not reachable yet), so the cell is registered again; the next beat, 10 s later, shows whether that worked.
keep() {
  beat "$1" && return 0
  echo "register-cells: heartbeat of $1 failed; registering it again" >&2
  register "$1" "$2" "$3" || echo "register-cells: registration of $1 failed; retrying in 10 s" >&2
}
until register in-ra-01 IN-RA http://cell-a:8080 && register in-rb-01 IN-RB http://cell-b:8080; do sleep 5; done
echo "cells registered"
while true; do
  keep in-ra-01 IN-RA http://cell-a:8080
  keep in-rb-01 IN-RB http://cell-b:8080
  sleep 10
done

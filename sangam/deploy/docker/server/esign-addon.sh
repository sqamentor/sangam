#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# Owner decision 217: builds the opt-in eSign and DSC add-on of the server (module sangam-esign, the only holder of
# Bouncy Castle). The add-on is the sangam-esign jar plus every runtime dependency of it that the server fat jar does
# not already ship, so nothing is on the class path twice. A dependency that the fat jar ships in another version is
# NOT copied (the server's copy is the one on the class path) and is listed, so a reviewer sees it.
#
#   esign-addon.sh <server fat jar> <dependency dir> <esign jar> <out dir>
#
#   <dependency dir>  the output of `mvn -pl sangam-esign dependency:copy-dependencies -DincludeScope=runtime`
#   <out dir>         receives the esign jar and the missing dependencies, plus ESIGN-ADDON.txt (the list)
# Used by deploy/docker/server.Dockerfile (build arg WITH_ESIGN=true; out dir /opt/sangam/lib-ext, on loader.path)
# and by .github/workflows/release.yml (distribution "esign"). Needs `jar` (JDK) or `unzip`. POSIX sh.
# Exit codes: 0 done, 1 an input is missing or the fat jar cannot be listed.
set -eu

[ "$#" -eq 4 ] || { echo "usage: esign-addon.sh <server fat jar> <dependency dir> <esign jar> <out dir>" >&2; exit 1; }
fat=$1 deps=$2 esign=$3 out=$4
[ -f "$fat" ] || { echo "esign-addon: server fat jar $fat not found" >&2; exit 1; }
[ -d "$deps" ] || { echo "esign-addon: dependency directory $deps not found" >&2; exit 1; }
[ -f "$esign" ] || { echo "esign-addon: sangam-esign jar $esign not found" >&2; exit 1; }
mkdir -p "$out"

# artifactId of a jar file name: the part before the first "-<digit>" (bcprov-jdk18on-1.84.jar -> bcprov-jdk18on)
artifact() {
  basename "$1" .jar | sed -E 's/-[0-9].*$//'
}

listing=$(mktemp)
trap 'rm -f "$listing"' EXIT
if command -v jar >/dev/null 2>&1; then
  jar tf "$fat" > "$listing"
elif command -v unzip >/dev/null 2>&1; then
  unzip -Z1 "$fat" > "$listing"
else
  echo "esign-addon: neither jar nor unzip is available" >&2; exit 1
fi
grep -q '^BOOT-INF/lib/' "$listing" || { echo "esign-addon: $fat is not a Spring Boot fat jar (no BOOT-INF/lib)" >&2; exit 1; }

record="$out/ESIGN-ADDON.txt"
{
  echo "# Sangam eSign/DSC add-on (owner decision 217): jars added to the server class path"
  echo "$(basename "$esign")"
} > "$record"
cp "$esign" "$out/"
for j in "$deps"/*.jar; do
  [ -f "$j" ] || continue
  name=$(basename "$j")
  a=$(artifact "$name")
  if grep -qx "BOOT-INF/lib/$name" "$listing"; then
    continue                                   # the server ships this exact jar
  fi
  # no pipefail: a grep without a match ends in head's status 0 and leaves shipped empty
  shipped=$(grep -E "^BOOT-INF/lib/$a-[0-9][^/]*\.jar$" "$listing" | head -n 1)
  if [ -n "$shipped" ]; then
    echo "# not added, the server ships another version: $name (server: ${shipped#BOOT-INF/lib/})" >> "$record"
    echo "esign-addon: WARNING $name not added, the server ships ${shipped#BOOT-INF/lib/}" >&2
    continue
  fi
  cp "$j" "$out/"
  echo "$name" >> "$record"
done
cat "$record"

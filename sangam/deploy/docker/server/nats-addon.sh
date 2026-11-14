#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# Owner decision 232: builds the opt-in NATS add-on of the server (NATS channel adapters and the NATS wake-up bus).
# jnats left the server fat jar; the add-on is exactly the jnats client and the Bouncy Castle jar it needs for nkey
# signing (bcprov-lts8on), both at the versions the reactor resolves (root pom pins jnats). Nothing else: the server
# fat jar already ships the rest, and a jar outside this pair fails the build (fail closed).
#
#   nats-addon.sh <server fat jar> <dependency dir> <out dir>
#
#   <dependency dir>  the output of `mvn -pl sangam-integration dependency:copy-dependencies -DincludeScope=runtime
#                     -DincludeArtifactIds=jnats,bcprov-lts8on` (jnats is an optional dependency there)
#   <out dir>         receives the two jars and NATS-ADDON.txt (the list)
# Used by deploy/docker/server.Dockerfile (build arg WITH_NATS=true; out dir /opt/sangam/lib-ext, on loader.path)
# and by .github/workflows/release.yml (distribution "nats"). Needs `jar` (JDK) or `unzip`. POSIX sh.
# Exit codes: 0 done, 1 an input is missing, the pair is incomplete or extra, or the server fat jar still ships jnats
# or a Bouncy Castle jar (decisions 217 and 232: the server pom exclusion has not landed).
set -eu

[ "$#" -eq 3 ] || { echo "usage: nats-addon.sh <server fat jar> <dependency dir> <out dir>" >&2; exit 1; }
fat=$1 deps=$2 out=$3
[ -f "$fat" ] || { echo "nats-addon: server fat jar $fat not found" >&2; exit 1; }
[ -d "$deps" ] || { echo "nats-addon: dependency directory $deps not found" >&2; exit 1; }

listing=$(mktemp)
trap 'rm -f "$listing"' EXIT
if command -v jar >/dev/null 2>&1; then
  jar tf "$fat" > "$listing"
elif command -v unzip >/dev/null 2>&1; then
  unzip -Z1 "$fat" > "$listing"
else
  echo "nats-addon: neither jar nor unzip is available" >&2; exit 1
fi
grep -q '^BOOT-INF/lib/' "$listing" || { echo "nats-addon: $fat is not a Spring Boot fat jar (no BOOT-INF/lib)" >&2; exit 1; }
if grep -E '^BOOT-INF/lib/(jnats-|bc[a-z0-9]*-(jdk|lts)[0-9]+on-)[^/]*\.jar$' "$listing" >&2; then
  echo "nats-addon: the server fat jar still ships jnats or Bouncy Castle (decisions 217 and 232); the add-on would" \
       "put a second copy on the class path" >&2
  exit 1
fi

jnats="" bc="" extra=""
for j in "$deps"/*.jar; do
  [ -f "$j" ] || continue
  case "$(basename "$j")" in
    jnats-[0-9]*.jar) [ -z "$jnats" ] || extra="$extra $j"; jnats=$j ;;
    bcprov-lts8on-[0-9]*.jar) [ -z "$bc" ] || extra="$extra $j"; bc=$j ;;
    *) extra="$extra $j" ;;
  esac
done
[ -n "$jnats" ] || { echo "nats-addon: no jnats jar in $deps" >&2; exit 1; }
[ -n "$bc" ] || { echo "nats-addon: no bcprov-lts8on jar in $deps" >&2; exit 1; }
[ -z "$extra" ] || { echo "nats-addon: unexpected jar(s) in $deps:$extra (only jnats and bcprov-lts8on)" >&2; exit 1; }

mkdir -p "$out"
# bcprov-lts8on and the eSign add-on's bcprov-jdk18on both hold the org.bouncycastle packages; two copies on one class
# path load whichever comes first. The two add-ons are not combined until an owner decision says how.
for j in "$out"/bc*-jdk18on-*.jar; do
  [ -f "$j" ] || continue
  echo "nats-addon: $out already holds $(basename "$j") (eSign add-on); NATS and eSign add-ons cannot be combined" >&2
  exit 1
done
cp "$jnats" "$bc" "$out/"
{
  echo "# Sangam NATS add-on (owner decision 232): jars added to the server class path"
  basename "$jnats"
  basename "$bc"
} > "$out/NATS-ADDON.txt"
cat "$out/NATS-ADDON.txt"

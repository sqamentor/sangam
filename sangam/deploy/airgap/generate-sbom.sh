#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Generates CycloneDX SBOMs (JSON, spec 1.6) for everything Sangam ships, plus SHA256SUMS:
#   sangam-maven.cdx.json   Maven reactor (server + modules + Java SDK), aggregate, runtime + compile scopes
#   sangam-ui.cdx.json      sangam-ui production dependencies (npm)
#   sdk-typescript.cdx.json TypeScript SDK (if present)
#   <image>.cdx.json        container images (syft, if installed and --images given)
# Then runs the licence policy check (deploy/licence/check_licences.py) over the result.
#
# Usage: deploy/airgap/generate-sbom.sh [--out deploy/airgap/out/sbom] [--images "ref1 ref2"] [--offline]
# --offline: Maven -o and npm --offline (needs a populated local repository / npm cache, see deploy/README.md).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SANGAM="$(cd "$HERE/../.." && pwd)"
OUT="$HERE/out/sbom"
IMAGES=""
OFFLINE=0
CYCLONEDX_MAVEN="org.cyclonedx:cyclonedx-maven-plugin:2.9.1"
# CR-12: the npm generator comes from the committed tools lockfile (deploy/licence/tools), never from npx --yes.
CYCLONEDX_TOOLS="$SANGAM/deploy/licence/tools"
while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT="$2"; shift 2 ;;
    --images) IMAGES="$2"; shift 2 ;;
    --offline) OFFLINE=1; shift ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
mkdir -p "$OUT"
MVN_OFF=""; NPM_OFF=""
[ "$OFFLINE" = 1 ] && { MVN_OFF="-o"; NPM_OFF="--offline"; }

MVN="mvn"; [ -x "$SANGAM/mvnw" ] && MVN="$SANGAM/mvnw"
echo ">> Maven aggregate SBOM"
( cd "$SANGAM" && "$MVN" -B -ntp $MVN_OFF -DskipTests "$CYCLONEDX_MAVEN:makeAggregateBom" \
    -DoutputFormat=json -DoutputName=sangam-maven.cdx -DschemaVersion=1.6 \
    -DincludeTestScope=false -DincludeProvidedScope=false -DincludeSystemScope=false )
cp "$SANGAM/target/sangam-maven.cdx.json" "$OUT/"

[ -f "$CYCLONEDX_TOOLS/package-lock.json" ] || { echo "ERROR: $CYCLONEDX_TOOLS/package-lock.json is missing (CR-12)" >&2; exit 2; }
( cd "$CYCLONEDX_TOOLS" && npm ci --ignore-scripts --no-audit --no-fund $NPM_OFF )
npm_sbom() { # dir name
  [ -f "$1/package.json" ] || { echo "ERROR: $1/package.json is missing" >&2; exit 2; }
  echo ">> npm SBOM $2"
  # decision 130: install scripts off; esbuild (build-time only) is not needed for an SBOM
  ( cd "$1" && { [ -d node_modules ] || npm ci --ignore-scripts --no-audit --no-fund $NPM_OFF; } \
      && "$CYCLONEDX_TOOLS/node_modules/.bin/cyclonedx-npm" --omit dev --spec-version 1.6 --output-format JSON \
         --output-file "$OUT/$2.cdx.json" )
}
npm_sbom "$SANGAM/sangam-ui" sangam-ui
npm_sbom "$SANGAM/sdks/typescript" sdk-typescript

if [ -n "$IMAGES" ]; then
  if command -v syft >/dev/null 2>&1; then
    for ref in $IMAGES; do
      echo ">> image SBOM $ref"
      syft "docker:$ref" -o cyclonedx-json="$OUT/$(echo "$ref" | tr '/:@' '___').cdx.json" -q
    done
  else
    echo "ERROR: --images given but syft is not installed (CR-01: a requested SBOM is never skipped)" >&2
    exit 2
  fi
fi

# Licence policy before the checksums: --annotate records the SG-27 consumer licence-policy exception (reason, verified SPDX,
# evidence file) as CycloneDX properties on the exact excepted components and in metadata.properties.
echo ">> licence policy"
# Optional UI SBOM as an argument array, never an unquoted command substitution: an --out path with spaces would be
# split into several arguments (ShellCheck SC2046). ${arr[@]+"${arr[@]}"} keeps `set -u` safe on bash < 4.4 when empty.
UI_SBOM_ARGS=()
if [ -f "$OUT/sangam-ui.cdx.json" ]; then
  UI_SBOM_ARGS=(--sbom "$OUT/sangam-ui.cdx.json")
fi
python3 -I "$SANGAM/deploy/licence/check_licences.py" --policy "$SANGAM/deploy/licence/licence-policy.json" --strict --annotate \
  --sbom "$OUT/sangam-maven.cdx.json" ${UI_SBOM_ARGS[@]+"${UI_SBOM_ARGS[@]}"} \
  --report "$OUT/licence-report.md"
# Attribution the exception carries (Apache-2.0 section 4(d) NOTICE of scala-library; MIT notices of com.lihaoyi)
cp "$SANGAM/../NOTICE" "$OUT/NOTICE"
( cd "$OUT" && sha256sum *.json > SHA256SUMS )
echo "SBOMs in $OUT"

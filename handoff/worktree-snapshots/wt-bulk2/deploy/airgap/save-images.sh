#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Builds the air-gapped installation bundle (IX.6, R56, AC-11/T50) on a machine WITH registry access:
#   images (docker save, one tar.gz per image) + Helm chart + compose files + docs + SBOMs + SHA256SUMS
#   + images.lock (pinned digests) -> one tarball sangam-airgap-<version>.tar (+ .sha256).
#
# Usage:
#   deploy/airgap/save-images.sh --version 0.1.0 [--registry ghcr.io/sqamentor] [--out deploy/airgap/out]
#                                [--with-observability] [--with-dev-databases] [--platform linux/amd64]
#                                [--no-pull] [--sbom-dir <dir with maven/npm CycloneDX files>] [--allow-missing]
# --allow-missing: skip (with a warning) an image that cannot be pulled, e.g. sangam-simulators before it exists.
#
# Needs: docker, sha256sum, tar, gzip. Optional: syft (image SBOMs, CycloneDX JSON), helm (chart package).
# On the shared build machine wrap it in the heavy lock (deploy/README.md):
#   python -E -X utf8 "$TEMP/heavy_lock.py" run --owner customwf -- bash deploy/airgap/save-images.sh ...
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY="$(cd "$HERE/.." && pwd)"

VERSION=""
REGISTRY="ghcr.io/sqamentor"
OUT="$HERE/out"
WITH_OBS=0
WITH_DEVDB=0
PLATFORM="linux/amd64"
PULL=1
SBOM_DIR=""
ALLOW_MISSING=0

while [ $# -gt 0 ]; do
  case "$1" in
    --version) VERSION="$2"; shift 2 ;;
    --registry) REGISTRY="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --with-observability) WITH_OBS=1; shift ;;
    --with-dev-databases) WITH_DEVDB=1; shift ;;
    --platform) PLATFORM="$2"; shift 2 ;;
    --no-pull) PULL=0; shift ;;
    --sbom-dir) SBOM_DIR="$2"; shift 2 ;;
    --allow-missing) ALLOW_MISSING=1; shift ;;
    -h|--help) sed -n '2,21p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
[ -n "$VERSION" ] || { echo "--version is required" >&2; exit 2; }

if [ "$WITH_DEVDB" = 1 ]; then
  cat >&2 <<'EOF'
WARNING: --with-dev-databases saves Oracle Free, SQL Server Developer and MySQL Community images.
Sangam does NOT redistribute them (SANGAM.md X.3a). Use this only for your own organisation's air-gapped
test environment, under your own acceptance of the Oracle Free Use Terms, the Microsoft SQL Server EULA
and the MySQL (GPL-2.0) licence. Do not pass the resulting bundle to third parties.
EOF
fi

NAME="sangam-airgap-${VERSION}"
STAGE="$OUT/$NAME"
rm -rf "$STAGE"
mkdir -p "$STAGE/images" "$STAGE/sbom" "$STAGE/chart" "$STAGE/compose" "$STAGE/docs"

want_group() {
  case "$1" in
    product|runtime) return 0 ;;
    observability) [ "$WITH_OBS" = 1 ] ;;
    devdb) [ "$WITH_DEVDB" = 1 ] ;;
    *) return 1 ;;
  esac
}

LOCK="$STAGE/images.lock"
: > "$LOCK"
echo "# group image digest file" >> "$LOCK"

have_syft=0; command -v syft >/dev/null 2>&1 && have_syft=1

while read -r group ref; do
  case "$group" in ''|\#*) continue ;; esac
  want_group "$group" || continue
  ref="${ref//\$\{SANGAM_VERSION\}/$VERSION}"
  ref="${ref//\$\{SANGAM_REGISTRY\}/$REGISTRY}"
  echo ">> [$group] $ref"
  if [ "$PULL" = 1 ] && ! docker pull --platform "$PLATFORM" "$ref"; then
    if [ "$ALLOW_MISSING" = 1 ]; then echo "WARN: skipping $ref (pull failed)" >&2; echo "# missing $group $ref" >> "$LOCK"; continue; fi
    echo "ERROR: cannot pull $ref" >&2; exit 1
  fi
  digest="$(docker image inspect --format '{{if .RepoDigests}}{{index .RepoDigests 0}}{{end}}' "$ref" 2>/dev/null || true)"
  [ -n "$digest" ] || digest="none"
  file="$(echo "$ref" | tr '/:@' '___').tar.gz"
  docker save "$ref" | gzip -6 > "$STAGE/images/$file"
  echo "$group $ref $digest images/$file" >> "$LOCK"
  if [ "$have_syft" = 1 ]; then
    syft "docker:$ref" -o cyclonedx-json="$STAGE/sbom/$(echo "$ref" | tr '/:@' '___').cdx.json" -q || \
      echo "WARN: syft failed for $ref" >&2
  fi
done < "$HERE/images.txt"
[ "$have_syft" = 1 ] || echo "NOTE: syft not found - image SBOMs skipped (release CI produces them)" >&2

# Helm chart
if command -v helm >/dev/null 2>&1; then
  helm package "$DEPLOY/helm/sangam" --version "$VERSION" --app-version "$VERSION" -d "$STAGE/chart"
else
  tar -C "$DEPLOY/helm" -czf "$STAGE/chart/sangam-$VERSION.tgz" sangam
fi

# compose files (no .env, no data)
tar -C "$DEPLOY" --exclude='compose/.env' --exclude='compose/data' --exclude='compose/drivers/*.jar' \
    -cf - compose | tar -C "$STAGE" -xf -
cp "$DEPLOY/README.md" "$STAGE/docs/OPERATIONS.md"
cp "$DEPLOY/docker/server/SOURCE-OFFER.txt" "$STAGE/docs/SOURCE-OFFER-mariadb.txt"
cp "$HERE/load-images.sh" "$HERE/load-images.ps1" "$HERE/verify-bundle.sh" "$STAGE/"
for f in LICENSE NOTICE; do
  [ -f "$DEPLOY/../../$f" ] && cp "$DEPLOY/../../$f" "$STAGE/docs/" || true
done

# application SBOMs produced by generate-sbom.sh / the release workflow
if [ -n "$SBOM_DIR" ] && [ -d "$SBOM_DIR" ]; then
  cp "$SBOM_DIR"/*.json "$STAGE/sbom/" 2>/dev/null || true
fi

cat > "$STAGE/BUNDLE-INFO.txt" <<EOF
Sangam air-gapped bundle
version          : $VERSION
source registry  : $REGISTRY
platform         : $PLATFORM
created (UTC)    : $(date -u +%FT%TZ)
observability    : $([ "$WITH_OBS" = 1 ] && echo included || echo not included)
dev databases    : $([ "$WITH_DEVDB" = 1 ] && echo "included (NOT redistributable)" || echo not included)
Verify           : ./verify-bundle.sh   (checks SHA256SUMS)
Load             : ./load-images.sh --registry <internal-registry>
Docs             : docs/OPERATIONS.md ("Air-gapped install")
EOF

( cd "$STAGE" && find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS )
tar -C "$OUT" -cf "$OUT/$NAME.tar" "$NAME"
( cd "$OUT" && sha256sum "$NAME.tar" > "$NAME.tar.sha256" )
echo "bundle: $OUT/$NAME.tar"
cat "$OUT/$NAME.tar.sha256"

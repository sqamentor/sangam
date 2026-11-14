#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Builds the air-gapped installation bundle (IX.6, R56, AC-11/T50) on a machine WITH registry access:
#   images (docker save, one tar.gz per image) + image SBOMs + Helm chart + compose files + docs + licence
#   notices + SBOMs + SHA256SUMS + images.lock (pinned digests) -> one tarball sangam-airgap-<version>.tar (+ .sha256).
#
# Usage:
#   deploy/airgap/save-images.sh --version 0.1.0 --sbom-dir <dir> [--notices-dir <dir>] [--registry ghcr.io/sqamentor]
#                                [--out deploy/airgap/out] [--with-observability] [--with-dev-databases]
#                                [--platform linux/amd64] [--no-pull]
#   --sbom-dir     the application SBOMs (CycloneDX JSON) of this version: the release's sbom/ directory or the
#                  output of generate-sbom.sh. Required: a bundle never ships without its SBOMs (CR-03).
#   --notices-dir  the release's notices/ directory (LICENSE, NOTICE, THIRD-PARTY.txt and licence texts per
#                  distribution, notice_bundle.py); copied to docs/notices/.
# Every step is strict (RM-12: a skipped step is never a pass): an image that cannot be pulled, an image SBOM that
# syft cannot write, a missing LICENSE, NOTICE or source offer, or an empty SBOM directory stops the bundle. The
# image list first passes the bundle licence gate (bundle_licence_gate.py, RM-13: no forbidden licence ships).
#
# Needs: docker, python3, syft, sha256sum, tar, gzip. Optional: helm (chart package; tar of the chart otherwise).
# On the shared build machine wrap it in the heavy lock (deploy/README.md):
#   python -E -X utf8 "$TEMP/heavy_lock.py" run --owner customwf -- bash deploy/airgap/save-images.sh ...
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY="$(cd "$HERE/.." && pwd)"
ROOT="$(cd "$DEPLOY/../.." && pwd)"

VERSION=""
REGISTRY="ghcr.io/sqamentor"
OUT="$HERE/out"
WITH_OBS=0
WITH_DEVDB=0
PLATFORM="linux/amd64"
PULL=1
SBOM_DIR=""
NOTICES_DIR=""

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
    --notices-dir) NOTICES_DIR="$2"; shift 2 ;;
    # guard-allow: the removed option is refused here with an error, it no longer skips anything
    --allow-missing) echo "--allow-missing was removed: a bundle with a missing image is never built (RM-12)" >&2; exit 2 ;;
    -h|--help) sed -n '2,24p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
[ -n "$VERSION" ] || { echo "--version is required" >&2; exit 2; }
[ -n "$SBOM_DIR" ] || { echo "--sbom-dir is required (the application SBOMs of $VERSION)" >&2; exit 2; }
compgen -G "$SBOM_DIR/*.json" >/dev/null || { echo "ERROR: no SBOM (*.json) in $SBOM_DIR" >&2; exit 1; }
if [ -n "$NOTICES_DIR" ] && [ ! -d "$NOTICES_DIR" ]; then echo "ERROR: $NOTICES_DIR is not a directory" >&2; exit 1; fi
for tool in docker python3 syft sha256sum tar gzip; do
  command -v "$tool" >/dev/null 2>&1 || { echo "ERROR: $tool is required" >&2; exit 1; }
done
for f in "$ROOT/LICENSE" "$ROOT/NOTICE" "$DEPLOY/docker/server/SOURCE-OFFER.txt" "$DEPLOY/licence/OS-PACKAGES-SOURCE-OFFER.txt"; do
  [ -f "$f" ] || { echo "ERROR: $f is missing; the bundle cannot ship without it" >&2; exit 1; }
done

if [ "$WITH_DEVDB" = 1 ]; then
  cat >&2 <<'EOF'
WARNING: --with-dev-databases saves Oracle Free, SQL Server Developer and MySQL Community images.
Sangam does NOT redistribute them (SANGAM.md X.3a). Use this only for your own organisation's air-gapped
test environment, under your own acceptance of the Oracle Free Use Terms, the Microsoft SQL Server EULA
and the MySQL (GPL-2.0) licence. Do not pass the resulting bundle to third parties.
EOF
fi

# RM-13, CR-06: the image list passes the licence policy before anything is saved.
python3 -I "$HERE/bundle_licence_gate.py" "$HERE/images.txt" "$DEPLOY/licence/licence-policy.json"

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
echo "# group image digest file" > "$LOCK"

while IFS= read -r raw || [ -n "$raw" ]; do
  line="${raw%%#*}"                       # drop the "# licence: ..." annotation and comment lines
  read -r group ref extra <<<"$line"      # a here-string always ends in a newline, so read returns 0
  [ -n "${group:-}" ] || continue
  [ -n "${ref:-}" ] && [ -z "${extra:-}" ] || { echo "ERROR: images.txt: bad line: $raw" >&2; exit 1; }
  want_group "$group" || continue
  ref="${ref//\$\{SANGAM_VERSION\}/$VERSION}"
  ref="${ref//\$\{SANGAM_REGISTRY\}/$REGISTRY}"
  echo ">> [$group] $ref"
  case "$ref" in
    *@sha256:*)
      # Decision 261: <name>:<tag>@sha256:<index digest>. Pull by the pinned digest (the tag alone may have moved), check
      # that exactly that digest is in the local store, then tag it <name>:<tag>, so the archive, the lock, the SBOM and
      # load-images.sh all use the human tag. Never saved by digest alone: docker load would restore an untagged image.
      tagref="${ref%@*}"
      pinned="${ref#*@}"
      case "${tagref##*/}" in
        *:*) repo="${tagref%:*}" ;;
        *) echo "ERROR: images.txt: $ref pins a digest but names no tag" >&2; exit 1 ;;
      esac
      if [ "$PULL" = 1 ]; then
        docker pull --platform "$PLATFORM" "$repo@$pinned" || { echo "ERROR: cannot pull $repo@$pinned" >&2; exit 1; }
      fi
      docker image inspect "$repo@$pinned" >/dev/null 2>&1 \
        || { echo "ERROR: $repo@$pinned is not in the local image store" >&2; exit 1; }
      docker tag "$repo@$pinned" "$tagref"
      digest="$repo@$pinned"
      ;;
    *)
      tagref="$ref"
      if [ "$PULL" = 1 ]; then
        docker pull --platform "$PLATFORM" "$ref" || { echo "ERROR: cannot pull $ref" >&2; exit 1; }
      fi
      digest="$(docker image inspect --format '{{if .RepoDigests}}{{index .RepoDigests 0}}{{end}}' "$ref")"
      [ -n "$digest" ] || digest="none(local-build)"
      ;;
  esac
  safe="$(echo "$tagref" | tr '/:@' '___')"
  docker save "$tagref" | gzip -6 > "$STAGE/images/$safe.tar.gz"
  echo "$group $tagref $digest images/$safe.tar.gz" >> "$LOCK"
  syft "docker:$tagref" -o cyclonedx-json="$STAGE/sbom/$safe.cdx.json" -q || { echo "ERROR: syft failed for $tagref" >&2; exit 1; }
done < "$HERE/images.txt"

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
cp "$DEPLOY/licence/OS-PACKAGES-SOURCE-OFFER.txt" "$STAGE/docs/"
cp "$HERE/load-images.sh" "$HERE/load-images.ps1" "$HERE/verify-bundle.sh" "$STAGE/"
cp "$ROOT/LICENSE" "$ROOT/NOTICE" "$STAGE/docs/"
if [ -n "$NOTICES_DIR" ]; then
  mkdir -p "$STAGE/docs/notices"
  cp -R "$NOTICES_DIR"/. "$STAGE/docs/notices/"
fi

# application SBOMs produced by generate-sbom.sh / the release workflow
cp "$SBOM_DIR"/*.json "$STAGE/sbom/"

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
Docs             : docs/OPERATIONS.md ("Air-gapped install"); licence notices in docs/ and docs/notices/
EOF

( cd "$STAGE" && find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS )
tar -C "$OUT" -cf "$OUT/$NAME.tar" "$NAME"
( cd "$OUT" && sha256sum "$NAME.tar" > "$NAME.tar.sha256" )
echo "bundle: $OUT/$NAME.tar"
cat "$OUT/$NAME.tar.sha256"

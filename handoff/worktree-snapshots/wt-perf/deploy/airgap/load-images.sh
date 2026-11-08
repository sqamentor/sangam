#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Loads the bundle's images on the air-gapped side and (optionally) pushes them to the internal registry.
#   ./load-images.sh                                   # docker load only (single VM, docker compose)
#   ./load-images.sh --registry registry.sdc.local     # load, retag as registry.sdc.local/<repo>:<tag>, push
#   ./load-images.sh --registry registry.sdc.local --keep-path   # keep the full source path in the new name
# Verifies SHA256SUMS first (refuses to load a modified bundle).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

REGISTRY=""
KEEP_PATH=0
while [ $# -gt 0 ]; do
  case "$1" in
    --registry) REGISTRY="$2"; shift 2 ;;
    --keep-path) KEEP_PATH=1; shift ;;
    -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

sha256sum --strict --quiet -c SHA256SUMS
echo "checksums OK"

while read -r group ref digest file; do
  case "$group" in ''|\#*) continue ;; esac
  echo ">> load $ref ($group)"
  gunzip -c "$file" | docker load
  if [ -n "$REGISTRY" ]; then
    path="${ref#*/}"                       # strip the source registry host
    case "$ref" in */*) ;; *) path="$ref" ;; esac
    [ "$KEEP_PATH" = 1 ] || path="sangam/${path##*/}"
    target="$REGISTRY/$path"
    docker tag "$ref" "$target"
    docker push "$target"
    echo "   pushed $target"
  fi
done < images.lock
echo "done. Helm: set global.imageRegistry=$REGISTRY (see chart/examples/values-airgap.yaml)"

#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Verifies an extracted air-gapped bundle: every file against SHA256SUMS. Run inside the bundle directory,
# after checking the outer tarball with: sha256sum -c sangam-airgap-<version>.tar.sha256
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
sha256sum --strict -c SHA256SUMS
echo "bundle verified: $(grep -c . SHA256SUMS) files"

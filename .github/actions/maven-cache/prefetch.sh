#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Cache writer only (ci-lanes `deps`, ci-build leg 0 on main): resolve once everything the workflows' Maven steps
# fetch later, so the restore-only jobs find it in the shared cache. The list comes from maven_inventory.py, which
# reads it from the workflows (explicit plugin goals, -Dartifact, -P profiles, -f POMs) and the root POM
# (surefire/failsafe junit-platform providers). Each command goes through resolve-once.sh: serial, bounded retries on
# 429/503/absent only; any other failure fails this job. Run from sangam/.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
mapfile -t cmds < <(python3 -I "$here/maven_inventory.py")
[ "${#cmds[@]}" -gt 0 ] || { echo "::error title=maven prefetch::the inventory listed nothing"; exit 1; }
for c in "${cmds[@]}"; do
  echo "::group::mvnw $c"
  # shellcheck disable=SC2086
  bash "$here/resolve-once.sh" $c
  echo "::endgroup::"
done
echo "maven prefetch: ${#cmds[@]} resolutions done"

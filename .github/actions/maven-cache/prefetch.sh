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
  if [[ "$c" == "@provider "* ]]; then
    # the junit-platform provider of the surefire version this POM effectively uses
    eff="$RUNNER_TEMP/effective-pom.xml"
    # shellcheck disable=SC2086
    bash "$here/resolve-once.sh" ${c#@provider } help:effective-pom -Doutput="$eff"
    v=$(python3 -I - "$eff" <<'PY'
import sys, xml.etree.ElementTree as ET
ns = "{http://maven.apache.org/POM/4.0.0}"
root = ET.parse(sys.argv[1]).getroot()
vs = {p.findtext(ns + "version") for p in root.iter(ns + "plugin")
      if p.findtext(ns + "artifactId") in ("maven-surefire-plugin", "maven-failsafe-plugin")}
vs.discard(None)
out = [f"org.apache.maven.surefire:surefire-junit-platform:{v}" for v in sorted(vs)]
# surefire loads the junit-platform launcher aligned with the POM's JUnit: platform 1.x for Jupiter 5.x, same for 6.x
for d in root.iter(ns + "dependency"):
    if d.findtext(ns + "groupId") == "org.junit.jupiter" and d.findtext(ns + "version"):
        j = d.findtext(ns + "version")
        out.append("org.junit.platform:junit-platform-launcher:" + ("1" + j[1:] if j.startswith("5.") else j))
print("\n".join(sorted(set(out))))
PY
)
    [[ "$v" == *surefire-junit-platform* ]] || { echo "::error title=maven prefetch::no surefire version in the effective POM of ${c#@provider }"; exit 1; }
    for gav in $v; do
      echo "::group::mvnw dependency:get $gav (${c#@provider })"
      bash "$here/resolve-once.sh" dependency:get -Dartifact="$gav"
      echo "::endgroup::"
    done
    continue
  fi
  echo "::group::mvnw $c"
  # shellcheck disable=SC2086
  bash "$here/resolve-once.sh" $c
  echo "::endgroup::"
done
echo "maven prefetch: ${#cmds[@]} resolutions done"

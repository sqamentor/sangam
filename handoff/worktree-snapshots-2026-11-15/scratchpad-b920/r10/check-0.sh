set -euo pipefail
case "$GITHUB_REF" in refs/tags/v*) ;; *) echo "::error::run this workflow from a release tag vX.Y.Z"; exit 1 ;; esac
v="${GITHUB_REF_NAME#v}"
echo "$v" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$' || { echo "::error::bad version $v"; exit 1; }
gh release view "v$v" --repo "$GITHUB_REPOSITORY" > /dev/null \
  || { echo "::error::no GitHub release v$v: release.yml did not pass its gate for this tag"; exit 1; }
echo "version=$v" >> "$GITHUB_OUTPUT"

set -euo pipefail
$CB finish "$STAGING" --unsigned-dry-run
$CB zip "$STAGING" --out "$GITHUB_WORKSPACE/central-bundle-dry-run.zip"

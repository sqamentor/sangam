set -euo pipefail
$CB finish "$STAGING"
$CB zip "$STAGING" --out "$RUNNER_TEMP/central-bundle.zip"

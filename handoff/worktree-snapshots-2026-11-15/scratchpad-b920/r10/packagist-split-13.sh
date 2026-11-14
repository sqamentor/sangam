set -euo pipefail
missing=""
echo "$SPLIT_URL" | grep -Eqx 'https://[A-Za-z0-9.-]+/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+(\.git)?' \
  || missing="$missing SANGAM_PHP_SPLIT_REPOSITORY(an https URL without credentials, got '${SPLIT_URL}')"
[ "$TOKEN_SET" = true ] || missing="$missing PHP_SPLIT_TOKEN"
if [ -n "$missing" ]; then echo "::error title=BLOCKED::package-release has no valid$missing"; exit 1; fi

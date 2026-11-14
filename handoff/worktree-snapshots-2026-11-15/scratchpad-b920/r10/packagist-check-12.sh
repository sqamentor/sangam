set -euo pipefail
mkdir -p "$RUNNER_TEMP/php-split"
git archive --format=tar "$GITHUB_SHA:sangam/sdks/php" | tar -x -C "$RUNNER_TEMP/php-split"
cd "$RUNNER_TEMP/php-split"
composer validate --strict
test "$(python3 -I -c 'import json; print(json.load(open("composer.json"))["name"])')" = sangam/sdk
test "$(python3 -I -c 'import json; print(json.load(open("composer.json"))["license"])')" = Apache-2.0
if ! cmp -s LICENSE "$GITHUB_WORKSPACE/LICENSE"; then echo "::error::sangam/sdks/php/LICENSE must be the repository LICENSE (Apache-2.0, decision 306)"; exit 1; fi
find . -type f -print0 | sort -z | xargs -0 sha256sum > "$RUNNER_TEMP/php-split.SHA256SUMS"
cat "$RUNNER_TEMP/php-split.SHA256SUMS"

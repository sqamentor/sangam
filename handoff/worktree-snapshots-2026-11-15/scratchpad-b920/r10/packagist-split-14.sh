set -euo pipefail
git config user.name "sangam-release"
git config user.email "sangam-release@users.noreply.github.com"
head=$(git subtree split --prefix=sangam/sdks/php "$GITHUB_SHA")
test "$(git show "$head:composer.json" | python3 -I -c 'import json,sys; print(json.load(sys.stdin)["name"])')" = sangam/sdk
git show "$head:LICENSE" > /dev/null
# a local name apart from this repository's own tag vX.Y.Z; it is pushed as vX.Y.Z to the split repository
git tag -a "php-split-v$V" -m "sangam/sdk $V (split of sangam/sdks/php at $GITHUB_SHA)" "$head"
echo "SPLIT_HEAD=$head" >> "$GITHUB_ENV"
echo "split head $head for v$V from $GITHUB_SHA"

set -euo pipefail
flag=--dry-run
if [ "$DRY_RUN" = false ]; then flag=; fi
# no --force: an existing tag or a diverged main is refused by the remote and fails the job
git -c credential.helper= \
    -c 'credential.helper=!f() { echo username=x-access-token; echo "password=${PHP_SPLIT_TOKEN}"; }; f' \
    push $flag --porcelain "$SPLIT_URL" "$SPLIT_HEAD:refs/heads/main" "refs/tags/php-split-v$V:refs/tags/v$V"
if [ -n "$flag" ]; then echo "dry run: nothing was pushed"; else echo "pushed main and v$V; registering on Packagist is the owner's act"; fi

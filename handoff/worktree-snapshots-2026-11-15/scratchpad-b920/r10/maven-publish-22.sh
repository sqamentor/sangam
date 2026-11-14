set -euo pipefail
export GNUPGHOME="$RUNNER_TEMP/gnupg"
mkdir -m 700 "$GNUPGHOME"
printf '%s\n' "$MAVEN_GPG_PRIVATE_KEY" | gpg --batch --quiet --import
find "$STAGING" -type f ! -name '*.asc' ! -name '*.md5' ! -name '*.sha1' ! -name '*.sha256' ! -name '*.sha512' -print0 |
  while IFS= read -r -d '' f; do
    printf '%s' "$MAVEN_GPG_PASSPHRASE" |
      gpg --batch --yes --quiet --pinentry-mode loopback --passphrase-fd 0 --armor --detach-sign --output "$f.asc" "$f"
    gpg --batch --quiet --verify "$f.asc" "$f"
  done
rm -rf "$GNUPGHOME"

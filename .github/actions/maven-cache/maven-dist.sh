#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Provide the pinned Maven distribution to mvnw from the shared cache. Downloads only on a cache miss, with curl's
# bounded retries (no fixed retry delay, so curl honours Retry-After on HTTP 429/503), and refuses any bytes whose SHA-256
# differs from distributionSha256Sum. mvnw then installs from file:// and checks the SHA-256 again itself.
set -euo pipefail
props=sangam/.mvn/wrapper/maven-wrapper.properties
url=$(sed -n 's/^distributionUrl=//p' "$props" | tr -d '\r')
sum=$(sed -n 's/^distributionSha256Sum=//p' "$props" | tr -d '\r')
base=https://repo.maven.apache.org/maven2
[ -n "$sum" ] || { echo "::error::$props has no distributionSha256Sum; refusing an unpinned Maven distribution"; exit 1; }
case "$url" in "$base"/org/apache/maven/*) ;; *) echo "::error::unexpected distributionUrl $url"; exit 1 ;; esac
root="$HOME/.cache/sangam-maven-dist"
dest="$root/${url#"$base"/}"
if [ -f "$dest" ] && echo "$sum  $dest" | sha256sum -c --status; then
  echo "maven distribution: cache hit (${dest##*/}, SHA-256 verified), nothing fetched from repo.maven.apache.org"
else
  rm -f "$dest"; mkdir -p "${dest%/*}"
  echo "maven distribution: cache miss, fetching ${url##*/} once"
  curl -fsSL --retry 6 --retry-max-time 600 --retry-connrefused -o "$dest.part" "$url"
  echo "$sum  $dest.part" | sha256sum -c --status || { rm -f "$dest.part"; echo "::error::SHA-256 mismatch for $url"; exit 1; }
  mv "$dest.part" "$dest"
fi
# Install it with mvnw now, from the verified file (mvnw re-checks the SHA-256). mvnw prefers wget, which has no file://
# support, so for this one call only a shim earlier on PATH copies file:// URLs and hands anything else to real wget.
shim="$RUNNER_TEMP/mvnw-file-wget"; mkdir -p "$shim"
cat > "$shim/wget" <<'SHIM'
#!/usr/bin/env bash
url=""; out=""; while [ $# -gt 0 ]; do case "$1" in -O) out="$2"; shift ;; file://*) url="$1" ;; esac; shift; done
[ -n "$url" ] && [ -n "$out" ] && exec cp "${url#file://}" "$out"
echo "mvnw-file-wget: only file:// URLs are served here" >&2; exit 1
SHIM
chmod +x "$shim/wget"
(cd sangam && PATH="$shim:$PATH" MVNW_REPOURL="file://$root" bash ./mvnw -B -ntp -v >/dev/null)
echo "MVNW_REPOURL=file://$root" >> "$GITHUB_ENV"
# Marker for the fetch count: Maven sets a fetched file's mtime to the remote Last-Modified, so count by ctime (set when the file is written).
touch "$RUNNER_TEMP/sangam-m2-restored"
echo "maven repository: $(find "$HOME/.m2/repository" -type f -name '*.jar' 2>/dev/null | wc -l) jars restored"

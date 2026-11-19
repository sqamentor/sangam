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
  # Bounded: at most 5 attempts. On 429/503 wait what Retry-After asks (capped at 300 s; 60 s x attempt when absent).
  # Any other HTTP error fails at once. Run 44448977445: curl gave up after its first 429 (Retry-After beyond its window).
  for attempt in 1 2 3 4 5; do
    code=$(curl -sSL -D "$dest.hdr" -o "$dest.part" -w '%{http_code}' "$url" || true)
    [ "$code" = 200 ] && break
    case "$code" in
      429|503) ;;
      *) rm -f "$dest.part" "$dest.hdr"; echo "::error::$url answered HTTP $code"; exit 1 ;;
    esac
    [ "$attempt" -lt 5 ] || { rm -f "$dest.part" "$dest.hdr"; echo "::error::$url still HTTP $code after 5 attempts"; exit 1; }
    ra=$(tr -d '\r' < "$dest.hdr" | sed -n 's/^[Rr]etry-[Aa]fter: *\([0-9]\+\).*/\1/p' | tail -1)
    s=${ra:-$(( attempt * 60 ))}; [ "$s" -le 300 ] || s=300
    echo "::warning::$url answered HTTP $code (attempt $attempt), Retry-After ${ra:-none}; waiting ${s}s"
    sleep "$s"
  done
  rm -f "$dest.hdr"
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
# One Maven everywhere: a plain `mvn` in any later step runs the pinned, SHA-256-verified wrapper distribution, not the
# runner image's Maven. A different Maven binds different default plugin versions (the runner's binds
# maven-resources-plugin 3.5.0, 3.9.9 binds 3.3.1), which the shared cache does not hold, so those jobs went back to
# Maven Central (the resources-plugin 3.5.0 / maven-filtering 3.5.0 failures, run 44332794608 JDK 27 leg).
bin="$RUNNER_TEMP/sangam-mvn-bin"; mkdir -p "$bin"
printf '#!/usr/bin/env bash\nexec bash "%s/sangam/mvnw" "$@"\n' "$GITHUB_WORKSPACE" > "$bin/mvn"
chmod +x "$bin/mvn"
echo "$bin" >> "$GITHUB_PATH"
# Marker for the fetch count: Maven sets a fetched file's mtime to the remote Last-Modified, so count by ctime (set when the file is written).
touch "$RUNNER_TEMP/sangam-m2-restored"
echo "maven repository: $(find "$HOME/.m2/repository" -type f -name '*.jar' 2>/dev/null | wc -l) jars restored"

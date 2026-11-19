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
  # A 404 on this exact pinned, checksum-verified file is retried the same way: the file exists (200, 9202456 bytes),
  # so a 404 is a transient answer of the CDN edge, not a missing artifact (runs 44489607242, 44473072992).
  # Any other HTTP error fails at once. Run 44448977445: curl gave up after its first 429 (Retry-After beyond its window).
  # On every non-200 answer an allow-list of response headers (which server and edge answered) goes to the job log;
  # never the whole header block, so nothing secret can be printed.
  # Run 44493122674 showed what the 404 is: 'HTTP/2 404, server: cloudflare, retry-after: 28304' (then 28004, 27704,
  # 27404): Central's edge blocks the runner egress for ~7.9 h. When a 404/429 asks for more than 300 s, waiting cannot
  # help, so the same pinned file is taken once from the official Apache distribution archive instead. The fallback URL
  # is derived from distributionUrl's version, and the SHA-256 check below applies to it unchanged (mvnw re-checks).
  ver=${url%/*}; ver=${ver##*/}
  case "$ver" in [0-9]*.*) ;; *) echo "::error::cannot read the Maven version from $url"; exit 1 ;; esac
  fallback="https://archive.apache.org/dist/maven/maven-${ver%%.*}/$ver/binaries/${url##*/}"
  hdrs() {
    echo "maven distribution: HTTP $1 from $2, response headers:"
    { tr -d '\r' < "$dest.hdr" | grep -iE '^(HTTP/|server:|x-cache|x-served-by|x-timer|cf-ray:|cf-cache-status:|via:|age:|date:|retry-after:|content-length:|content-type:)' | sed 's/^/  /'; } 2>/dev/null || true
  }
  src=$url
  for attempt in 1 2 3 4 5; do
    code=$(curl -sSL -D "$dest.hdr" -o "$dest.part" -w '%{http_code}' "$url" || true)
    [ "$code" = 200 ] && break
    hdrs "$code" "$url (attempt $attempt)"
    case "$code" in
      404|429|503) ;;
      *) rm -f "$dest.part" "$dest.hdr"; echo "::error::$url answered HTTP $code"; exit 1 ;;
    esac
    ra=$(tr -d '\r' < "$dest.hdr" | sed -n 's/^[Rr]etry-[Aa]fter: *\([0-9]\+\).*/\1/p' | tail -1)
    if [ "$code" != 503 ] && [ -n "$ra" ] && [ "$ra" -gt 300 ]; then
      echo "::warning::$url answered HTTP $code with Retry-After ${ra}s (> 300 s); fetching the same pinned file from $fallback"
      src=$fallback
      code=$(curl -sSL -D "$dest.hdr" -o "$dest.part" -w '%{http_code}' "$fallback" || true)
      [ "$code" = 200 ] && break
      hdrs "$code" "$fallback"
      rm -f "$dest.part" "$dest.hdr"; echo "::error::$fallback answered HTTP $code"; exit 1
    fi
    [ "$attempt" -lt 5 ] || { rm -f "$dest.part" "$dest.hdr"; echo "::error::$url still HTTP $code after 5 attempts"; exit 1; }
    s=${ra:-$(( attempt * 60 ))}; [ "$s" -le 300 ] || s=300
    echo "::warning::$url answered HTTP $code (attempt $attempt), Retry-After ${ra:-none}; waiting ${s}s"
    sleep "$s"
  done
  rm -f "$dest.hdr"
  echo "$sum  $dest.part" | sha256sum -c --status || { rm -f "$dest.part"; echo "::error::SHA-256 mismatch for $src"; exit 1; }
  mv "$dest.part" "$dest"
  echo "maven distribution: served by $src (SHA-256 verified), stored for the distribution cache"
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

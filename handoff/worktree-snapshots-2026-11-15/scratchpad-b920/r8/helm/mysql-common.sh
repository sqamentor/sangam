set -eu
ts() { date -u +%Y%m%dT%H%M%SZ; }
log() { echo "$(date -u +%FT%TZ) $*"; }
checksum_dir() { (cd "$1" && find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS); }
# Retention: a failed removal is a failed job (otherwise the volume fills silently until backups stop).
prune() {
  if ! find "$1" -mindepth 1 -maxdepth 1 -type d -mtime +"${RETENTION_DAYS}" -print -exec rm -rf {} +; then
    log "retention prune of $1 failed: backups older than ${RETENTION_DAYS} days were not all removed"; return 1
  fi
}

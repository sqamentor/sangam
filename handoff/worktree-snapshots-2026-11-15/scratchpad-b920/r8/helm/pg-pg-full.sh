. /scripts/common.sh
export PGPASSWORD="$DB_PASSWORD"
dest="$BACKUP_DIR/base/$(ts)"; mkdir -p "$dest"
log "pg_basebackup -> $dest"
pg_basebackup -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -D "$dest" -Ft -z -X stream \
  --checkpoint=fast --label="sangam-$(ts)" --manifest-checksums=SHA256 --progress --verbose
checksum_dir "$dest"
prune "$BACKUP_DIR/base"
# WAL files older than the oldest kept base backup are no longer needed. The base directory holds at least the
# backup just written; the WAL directory exists only where pg-receivewal runs. A failed delete fails the job.
oldest=$(ls -1 "$BACKUP_DIR/base" | head -n 1)
if [ -n "$oldest" ] && [ -d "$BACKUP_DIR/wal" ]; then
  if ! find "$BACKUP_DIR/wal" -type f ! -newer "$BACKUP_DIR/base/$oldest" -name '0000*' -delete; then
    log "WAL cleanup older than base backup $oldest failed"; exit 1
  fi
fi
log "done"

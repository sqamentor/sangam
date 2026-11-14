. /scripts/common.sh
export PGPASSWORD="$DB_PASSWORD"
mkdir -p "$BACKUP_DIR/wal"
# --if-not-exists already accepts an existing slot, so any failure here is real (connection, rights, slot limit).
if ! pg_receivewal -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -D "$BACKUP_DIR/wal" \
  --slot="$WAL_SLOT" --create-slot --if-not-exists; then
  log "could not create or confirm replication slot $WAL_SLOT; WAL is not being archived"; exit 1
fi
exec pg_receivewal -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -D "$BACKUP_DIR/wal" \
  --slot="$WAL_SLOT" --synchronous --compress=gzip --verbose

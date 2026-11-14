. /scripts/common.sh
dest="$BACKUP_DIR/full/$(ts)"; mkdir -p "$dest"
export MYSQL_PWD="$DB_PASSWORD"
log "mysqldump -> $dest"
mysqldump -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" --single-transaction --quick --routines --triggers \
  --events --hex-blob --set-gtid-purged=ON --source-data=2 --default-character-set=utf8mb4 \
  --databases "$DB_NAME" | gzip -6 > "$dest/sangam.sql.gz"
gzip -t "$dest/sangam.sql.gz"
mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -N -e "SELECT @@GLOBAL.gtid_executed" > "$dest/gtid_executed.txt"
checksum_dir "$dest"; prune "$BACKUP_DIR/full"; log "done"

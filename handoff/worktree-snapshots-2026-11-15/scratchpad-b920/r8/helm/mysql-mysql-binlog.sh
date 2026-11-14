. /scripts/common.sh
export MYSQL_PWD="$DB_PASSWORD"
dir="$BACKUP_DIR/binlog"; mkdir -p "$dir"
mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -e "FLUSH BINARY LOGS"
# every binlog except the newest (still open) one
mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -N -e "SHOW BINARY LOGS" | awk '{print $1}' | sed '$d' > /tmp/closed
while read -r f; do
  [ -s "$dir/$f" ] && continue
  log "copy $f"
  mysqlbinlog --read-from-remote-server --host="$DB_HOST" --port="$DB_PORT" --user="$DB_USER" \
    --raw --result-file="$dir/" "$f"
  sha256sum "$dir/$f" >> "$dir/SHA256SUMS"
done < /tmp/closed
if ! find "$dir" -type f -name '*-bin.*' -mtime +"${RETENTION_DAYS}" -delete; then
  log "binlog retention in $dir failed: files older than ${RETENTION_DAYS} days were not all removed"; exit 1
fi
log "done"

. /scripts/common.sh
export PGPASSWORD="$DB_PASSWORD"
dest="$BACKUP_DIR/logical/$(ts)"; mkdir -p "$dest"
pg_dump -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -Fc -Z 6 -f "$dest/sangam.dump"
pg_restore --list "$dest/sangam.dump" > "$dest/sangam.toc"
checksum_dir "$dest"; prune "$BACKUP_DIR/logical"; log "done"

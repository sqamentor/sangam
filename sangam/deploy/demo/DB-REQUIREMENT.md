# Sangam demo database (demo.ifms.dev)

Owner decision 2026-11-19: Sangam has its own PostgreSQL container with its own credentials. It never uses the
IFMS Oracle database.

| Item | Value |
|---|---|
| Engine | PostgreSQL 17.12, image `postgres:17.12@sha256:0d938e975177bef9f1f61f3f9dbcf2e0aa0a139568f66d3415ed8892eb97f6ac`. This is the digest in `deploy/compose/docker-compose.yml` and the Helm chart (decision 416). CI also tests 18. |
| Container | `sangam-postgres` in `sangam-compose-fragment.yml`. It is on the `sangam-internal` network only and has no host port. |
| Database / user | `sangam` / `sangam`. The password is `SANGAM_PG_PASSWORD`, taken from the host's secret store. It has no default. |
| Encoding | UTF8, locale C.UTF-8, data checksums on, time zone UTC |
| Schema | Liquibase creates the schema on the first start (`SANGAM_LIQUIBASE_MODE=startup`). After that, the mode is `validate`. |
| Size | Demo data plus the audit chain is under 2 GB. Reserve 10 GB on the EBS volume for the `sangam-pg-data` volume. The container has 1 vCPU and 1 GB RAM (limit), `max_connections=120` and `shared_buffers=256MB`. |
| Backup | Before every redeploy, and every night while the demo runs: `docker exec sangam-postgres pg_dump -U sangam -Fc sangam > sangam-$(date -u +%Y%m%dT%H%MZ).dump`. Keep the last 7 dumps on the host and also in IFMS's existing backup bucket, if there is one. To restore: `pg_restore -U sangam -d sangam --clean --if-exists <dump>`, with the server stopped. |
| Retention | Demo data only. There is no personal data from production. To drop the whole demo: `docker compose ... down` and then `docker volume rm <project>_sangam-pg-data`. This is an irreversible step and needs the owner's yes. |

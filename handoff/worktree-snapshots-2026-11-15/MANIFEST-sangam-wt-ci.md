# Worktree snapshots, 2026-11-15 (account switch)

Every file in a stray worktree copy whose bytes (raw, or with CRLF/LF folded) match no blob in the git object
store after checkpoint 71b2c66d. Files are copied byte-for-byte at their path inside the copy. Most are earlier
lane versions that were later integrated and edited further; they are kept so that no character is lost.

Left out on purpose (still on local disk at the source path): third-party `reference/` clones, build outputs
(`dist/`, `bin/`, `obj/`), local `.claude/` data, `.git` pointer files, JFR recordings and files over 20 MB.

| copy | source | kept | left out |
|---|---|---|---|
| sangam-wt-ci | `D:\sangam-wt-ci` | 23 | 34080 |

## sangam-wt-ci

- kept `out/dotnet/Sangam.Sdk.0.1.0.nupkg` (97257 bytes)
- kept `out/dotnet2/Sangam.Sdk.0.1.0.nupkg` (97258 bytes)
- kept `out/python/sangam_sdk-0.1.0-py3-none-any.whl` (35695 bytes)
- kept `out/python/sangam_sdk-0.1.0.tar.gz` (36196 bytes)
- kept `out/ts/sangam-sdk-0.1.0.tgz` (54193 bytes)
- kept `sangam/sangam-server/src/test/java/org/sangam/server/acceptance/fwt/FwtWorkerCompatIT.java` (13880 bytes)
- kept `sangam/sdks/python/src/sangam_sdk.egg-info/dependency_links.txt` (1 bytes)
- kept `sangam/sdks/python/src/sangam_sdk.egg-info/PKG-INFO` (10094 bytes)
- kept `sangam/sdks/python/src/sangam_sdk.egg-info/requires.txt` (18 bytes)
- kept `sangam/sdks/python/src/sangam_sdk.egg-info/SOURCES.txt` (498 bytes)
- kept `sangam/sdks/python/src/sangam_sdk.egg-info/top_level.txt` (11 bytes)
- kept `spike/results/run-queue.console.log` (5081 bytes)
- kept `spike/results/run-queue.log` (5084 bytes)
- kept `spike/results/run-queue/q1-adapter-flowable.err.log` (1528349 bytes)
- kept `spike/results/run-queue/q1-adapter-flowable.out.log` (7490 bytes)
- kept `spike/results/run-queue/q1-adapter-operaton.err.log` (1421863 bytes)
- kept `spike/results/run-queue/q1-adapter-operaton.out.log` (5413 bytes)
- kept `spike/results/run-queue/q2-e1-mysql-mariadb.out.log` (3556 bytes)
- kept `spike/results/run-queue/q2-e1-mysql.out.log` (3360 bytes)
- kept `spike/results/run-queue/q2-e1-oracle.out.log` (3360 bytes)
- kept `spike/results/run-queue/q2-e1-sqlserver.out.log` (3360 bytes)
- kept `spike/results/run-queue/q3-e2e7-oracle.out.log` (1551 bytes)
- kept `spike/results/sources/lmmh/LMMH-CS1127.pdf` (9222852 bytes)
- left out `.claude/data/healthcare/documents/data.sqlite` (4096 bytes)
- left out `.claude/data/healthcare/documents/data.sqlite-shm` (32768 bytes)
- left out `.claude/data/healthcare/documents/data.sqlite-wal` (185432 bytes)
- left out 34077 third-party or build-output files

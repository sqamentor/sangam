$ErrorActionPreference = 'Continue'
$ctx = "D:\GITHUB  Project\cwf-docs-wt\sangam"
$log = "$env:TEMP\sg14-run.log"
function L($m) { "[$(Get-Date -Format HH:mm:ss)] $m" | Tee-Object -FilePath $log -Append }
"" | Set-Content $log
if ($env:SG14_SKIP_BUILD) { L "reuse image" } else {
L "build image"
docker build -f "$ctx\deploy\docker\server.Dockerfile" --build-arg JAR_SOURCE=prebuilt `
  --build-arg PREBUILT_JAR=sangam-server/target-docs/sangam-server-0.1.0-SNAPSHOT.jar `
  --target runtime -t sangam-server:sg14 $ctx *>> $log
L "build rc=$LASTEXITCODE" }
docker image inspect sangam-server:sg14 --format '{{.Size}} {{.Config.User}} {{json .Config.Entrypoint}}' *>> $log
docker run --rm --entrypoint sh sangam-server:sg14 -c 'ls -l /opt/sangam /opt/sangam/lib-ext /opt/sangam/drivers; jar tf /opt/sangam/app.jar 2>/dev/null | grep -ciE "BOOT-INF/lib/(ojdbc|mariadb|mysql-connector)" ; true' *>> $log

docker network create sg14net *>> $log
docker run -d --name sg14-pg --network sg14net -e TZ=UTC -e PGTZ=UTC -e POSTGRES_DB=sangam -e POSTGRES_USER=sangam -e POSTGRES_PASSWORD=sg14-test-only postgres:17 -c timezone=UTC -c default_transaction_isolation='read committed' *>> $log
for ($i=0; $i -lt 60; $i++) { docker exec sg14-pg pg_isready -U sangam -d sangam *> $null; if ($LASTEXITCODE -eq 0) { break }; Start-Sleep 2 }
L "pg ready"
$kek = [Convert]::ToBase64String((1..32 | % { [byte](Get-Random -Max 256) }))
$keys = Get-Content "$env:TEMP\sg14-keys.txt"
$common = @('-e',"SANGAM_REGISTRY_SIGNER_PRIVATE_KEY=$($keys[0])",'-e',"SANGAM_REGISTRY_SIGNER_PUBLIC_KEY=$($keys[1])",'-e',"SANGAM_REGISTRY_VAULT_HMAC_PEPPER=$($keys[2])",'--network','sg14net','-e','TZ=UTC','-e','SPRING_PROFILES_ACTIVE=postgres,oidc',
  '-e','SANGAM_DB_URL=jdbc:postgresql://sg14-pg:5432/sangam','-e','SANGAM_DB_USER=sangam','-e','SANGAM_DB_PASSWORD=sg14-test-only',
  '-e','SANGAM_SECURITY_PRODUCTION=true','-e',"SANGAM_SECURITY_KEK_KEYS_K1=$kek",'-e','SANGAM_SECURITY_KEK_CURRENT_ID=k1',
  '-e','SANGAM_OIDC_ISSUER=https://sso.example.org/realms/sangam',
  '-e','SANGAM_COMPLIANCE_JURISDICTIONS=IN','-e','SANGAM_COMPLIANCE_LOG_ARCHIVE_COUNTRY=IN')
L "negative: production with loopback DB URL must refuse to start"
$neg = $common.Clone(); $neg[$neg.IndexOf('SANGAM_DB_URL=jdbc:postgresql://sg14-pg:5432/sangam')] = 'SANGAM_DB_URL=jdbc:postgresql://localhost:5432/sangam'
docker run --rm @neg -e SANGAM_LIQUIBASE_MODE=migrate-only sangam-server:sg14 *> "$env:TEMP\sg14-neg.log"
L "negative rc=$LASTEXITCODE; guard line: $((Select-String -Path "$env:TEMP\sg14-neg.log" -Pattern 'loopback host' | Select -First 1).Line)"
L "migrate-only job"
docker run --rm --name sg14-migrate @common -e SANGAM_LIQUIBASE_MODE=migrate-only sangam-server:sg14 *> "$env:TEMP\sg14-migrate.log"
L "migrate rc=$LASTEXITCODE"
L "server in validate mode"
docker run -d --name sg14-server @common -e SANGAM_LIQUIBASE_MODE=validate -p 18080:8080 -p 127.0.0.1:18081:8081 sangam-server:sg14 *>> $log
$ok = $false
for ($i=0; $i -lt 90; $i++) { try { $r = Invoke-WebRequest -UseBasicParsing http://127.0.0.1:18081/actuator/health/readiness -TimeoutSec 5; if ($r.StatusCode -eq 200) { $ok = $true; break } } catch {}; Start-Sleep 4 }
L "readiness ok=$ok"
try { L ("readiness body: " + (Invoke-WebRequest -UseBasicParsing http://127.0.0.1:18081/actuator/health/readiness).Content) } catch { L "readiness error $_" }
try { $r = Invoke-WebRequest -UseBasicParsing http://127.0.0.1:18080/api/v1/tasks -SkipHttpErrorCheck; L "unauthenticated /api/v1/tasks -> $($r.StatusCode)" } catch { L "api error $_" }
try { $r = Invoke-WebRequest -UseBasicParsing http://127.0.0.1:18080/actuator/prometheus -SkipHttpErrorCheck; L "app port /actuator/prometheus -> $($r.StatusCode)" } catch { L "prom error $_" }
docker exec sg14-pg psql -U sangam -d sangam -tAc "select count(*) from databasechangelog" *>> $log
docker exec sg14-pg psql -U sangam -d sangam -tAc "show timezone" *>> $log
docker logs sg14-server *> "$env:TEMP\sg14-server.log"
L "cleanup"
docker rm -f sg14-server sg14-pg *>> $log
docker network rm sg14net *>> $log
L "done"

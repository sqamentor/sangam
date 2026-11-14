cd /w; set +e
P="-f sangam/examples/values-postgres.yaml"
for o in examples/values-airgap.yaml values-india.example.yaml; do echo "== postgres+$o"; helm lint sangam $P -f sangam/$o --strict 2>&1|tail -1; helm template t sangam $P -f sangam/$o >/tmp/o 2>/tmp/e; echo "rc=$? docs=$(grep -c '^kind:' /tmp/o)"; head -3 /tmp/e; done
echo "== postgres strict"; helm lint sangam $P --strict 2>&1 | tail -3
N="--set cell-a.oidc.issuerUri=https://sso.example.gov.in/realms/a --set cell-b.oidc.issuerUri=https://sso.example.gov.in/realms/b --set cell-a.database.host=db-a --set cell-b.database.host=db-b --set cell-a.database.existingSecret=db-a --set cell-b.database.existingSecret=db-b --set controlPlane.database.jdbcUrl=jdbc:postgresql://cp/cp --set controlPlane.database.existingSecret=cp --set controlPlane.tokensSecret=cpt --set gateway.tokenSecret=gw --set rollup.database.jdbcUrl=jdbc:postgresql://ru/ru --set rollup.database.existingSecret=ru --set rollup.tokensSecret=rut"
echo "== national prod-like"; helm lint sangam-national $N --strict 2>&1|tail -2; helm template n sangam-national $N >/tmp/n 2>/tmp/e; echo "rc=$? docs=$(grep -c '^kind:' /tmp/n)"; head -3 /tmp/e
grep '^kind:' /tmp/n | sort | uniq -c
helm template t sangam -f sangam/examples/values-postgres.yaml --set 'server.trustedProxies={10.0.0.0/8}' --set 'server.clientIpHeaders={CF-Connecting-IP,X-Forwarded-For}' | grep -E 'SANGAM_(SECURITY_PRODUCTION|TRUSTED|CLIENT_IP)'

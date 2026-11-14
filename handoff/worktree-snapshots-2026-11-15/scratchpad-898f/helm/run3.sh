cd /w; N=$(cat /w/nset 2>/dev/null)
helm template n sangam-national --set cell-a.oidc.issuerUri=https://a --set cell-b.oidc.issuerUri=https://b --set cell-a.database.host=a --set cell-b.database.host=b > /tmp/n 2>&1; echo rc=$?; tail -2 /tmp/n | head -2
awk '/^kind:/{k=$2} /^  name:/{print k"/"$2}' /tmp/n | sort | uniq -d
helm template t sangam -f sangam/examples/values-postgres.yaml | awk '/^kind:/{k=$2} /^  name:/{print k"/"$2}' | sort | uniq -d
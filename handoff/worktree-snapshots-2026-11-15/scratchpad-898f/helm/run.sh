cd /w
set +e
for v in "" examples/values-postgres.yaml examples/values-oracle.yaml examples/values-sqlserver.yaml examples/values-mysql.yaml examples/values-airgap.yaml examples/values-scale.yaml values-india.example.yaml; do
  f=""; [ -n "$v" ] && f="-f sangam/$v"
  echo "=== sangam ${v:-defaults}"
  helm lint sangam $f 2>&1 | tail -4
  helm template t sangam $f > /tmp/out.yaml 2>/tmp/err; echo "template rc=$? docs=$(grep -c '^kind:' /tmp/out.yaml)"; head -5 /tmp/err
done
helm dependency build sangam-national 2>&1 | tail -2
echo "=== national defaults"; helm lint sangam-national 2>&1 | tail -4
helm template n sangam-national > /tmp/n.yaml 2>/tmp/err; echo "template rc=$? docs=$(grep -c '^kind:' /tmp/n.yaml)"; head -5 /tmp/err

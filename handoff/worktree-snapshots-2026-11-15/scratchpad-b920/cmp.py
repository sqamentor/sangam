import sys, filecmp, os
a, b = sys.argv[1], sys.argv[2]
for f in ["sangam-api.yaml","sangam-control-plane-api.yaml","sangam-rollup-api.yaml","sangam-gateway-api.yaml","sangam-events.asyncapi.yaml"]:
    print(f, "SAME" if filecmp.cmp(os.path.join(a,f), os.path.join(b,f), shallow=False) else "DIFF")

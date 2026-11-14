import sys, yaml, re
spec = yaml.safe_load(open(sys.argv[1], encoding="utf-8"))
plain = re.compile(r"^[a-z][a-z0-9-]*(\.[a-z0-9-]+)+$")
for path, item in spec["paths"].items():
    for method, op in item.items():
        if not isinstance(op, dict) or "x-sangam-command-type" not in op:
            continue
        p = op.get("x-sangam-permission")
        ct = op["x-sangam-command-type"]
        if p is None or not plain.match(str(p)) or ct == "power-table.transition":
            print(ct, "|", method.upper(), path, "|", p, "|", op.get("x-sangam-groups"), "| idem", op.get("x-sangam-idempotent"), "| tags", op.get("tags"))

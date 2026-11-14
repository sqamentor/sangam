import sys, yaml, json
d = yaml.safe_load(open(sys.argv[1], encoding="utf-8"))
S = d["components"]["schemas"]
def fmt(s, depth=0):
    if "$ref" in s: return "@" + s["$ref"].split("/")[-1]
    if "anyOf" in s: return "|".join(fmt(x) for x in s["anyOf"])
    t = s.get("type")
    if t == "array": return "[" + fmt(s.get("items", {}), depth) + "]"
    if t == "object" and s.get("properties") and depth < 3:
        return "{" + ", ".join(k + ":" + fmt(v, depth+1) for k, v in s["properties"].items()) + "}"
    return (t or "any") + ("/" + s["format"] if "format" in s else "")
for n, s in S.items():
    if "x-sangam-body-readers" in s:
        op = n[:-4]
        print(op, "=", fmt(s), (" OPAQUE:" + ",".join(o["use"] for o in s.get("x-sangam-body-opaque", []))) if s.get("x-sangam-body-opaque") else "")

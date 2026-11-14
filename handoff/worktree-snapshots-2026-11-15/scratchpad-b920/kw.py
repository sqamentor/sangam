import re, json, os, sys
S = os.path.dirname(os.path.abspath(__file__))
E = json.load(open(os.path.join(S, "entries.json"), encoding="utf-8"))
Q = json.load(open(sys.argv[1], encoding="utf-8"))
types = sys.argv[2].split(",") if len(sys.argv) > 2 else ["DONE", "CHANGE+DONE"]
for key, rx in Q:
    p = re.compile(rx, re.I)
    hits = [e for e in E if e["type"] in types and p.search(e["text"])]
    print("##", key, "hits", len(hits))
    for h in hits[-2:]:
        m = p.search(h["text"]); s = max(0, m.start() - 120)
        print("   %s %s %s %s: %s" % (h["board"], h["ts"][5:19], h["who"][:14], h["type"], h["text"][s:s + 260]))
